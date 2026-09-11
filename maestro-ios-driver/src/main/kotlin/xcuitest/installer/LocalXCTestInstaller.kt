package xcuitest.installer

import device.IOSDevice
import maestro.utils.HttpClient
import maestro.utils.MaestroTimer
import maestro.utils.Metrics
import maestro.utils.MetricsProvider
import maestro.utils.TempFileHandler
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import util.IOSDeviceType
import util.LocalIOSDeviceController
import util.GoIosCLIUtils
import util.LocalSimulatorUtils
import util.XCRunnerCLIUtils
import xcuitest.XCTestClient
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.time.Duration.Companion.seconds

class LocalXCTestInstaller(
    private val deviceId: String,
    private val host: String = "127.0.0.1",
    /**
     * Additional addresses to try if [host] never answers. The on-device runner binds
     * 0.0.0.0, so it can be reached either through a host port forward (iproxy/usbmux, on
     * loopback) or directly on the device's CoreDevice tunnel address — but which of the two
     * is live depends on how the device happens to be attached right now. Rather than commit
     * to one, poll the preferred address first and fall back to the rest.
     */
    private val fallbackHosts: List<String> = emptyList(),
    /**
     * Re-queried while waiting for the runner, to pick up addresses that do not exist yet at
     * construction time. A CoreDevice tunnel to a network-attached device is owned by whoever
     * opened it and disappears when that process exits, and each tunnel gets its own address —
     * so the address of the tunnel that `xcodebuild test-without-building` opens for this very
     * session can only be observed after it has started.
     */
    private val additionalHostsProvider: () -> List<String> = { emptyList() },
    private val deviceType: IOSDeviceType,
    private val defaultPort: Int,
    private val metricsProvider: Metrics = MetricsProvider.getInstance(),
    private val httpClient: OkHttpClient = HttpClient.build(
        name = "XCUITestDriverStatusCheck",
        connectTimeout = 1.seconds,
        readTimeout = 100.seconds,
    ),
    val reinstallDriver: Boolean = true,
    private val iOSDriverConfig: IOSDriverConfig,
    private val deviceController: IOSDevice,
    private val tempFileHandler: TempFileHandler = TempFileHandler(),
    private val logsDir: File,
) : XCTestInstaller {

    private val logger = LoggerFactory.getLogger(LocalXCTestInstaller::class.java)
    private val metrics = metricsProvider.withPrefix("xcuitest.installer").withTags(mapOf("kind" to "local", "deviceId" to deviceId, "host" to host))

    /**
     * If true, allow for using a xctest runner started from Xcode.
     *
     * When this flag is set, maestro will not install, run, stop or remove the xctest runner.
     * Make sure to launch the xctest runner from Xcode whenever maestro needs it.
     */
    private val useXcodeTestRunner = !System.getenv("USE_XCODE_TEST_RUNNER").isNullOrEmpty()
    private val tempDir = tempFileHandler.createTempDirectory(deviceId)
    private val localSimulatorUtils = LocalSimulatorUtils(tempFileHandler)
    private val iosBuildProductsExtractor = IOSBuildProductsExtractor(
        target = tempDir.toPath(),
        context = iOSDriverConfig.context,
        deviceType = deviceType,
    )
    private val xcRunnerCLIUtils = XCRunnerCLIUtils(tempFileHandler)

    /** Extracted once: [start] may launch the runner twice, but the products do not change. */
    private val buildProducts by lazy { iosBuildProductsExtractor.extract(iOSDriverConfig.sourceDirectory) }

    private var xcTestProcess: Process? = null

    /** Why xcodebuild refused the device, as of the last preflight or give-up. */
    private var destinationProblem: String? = null

    /** Exit code of the last `xcodebuild test-without-building`, if it has exited. */
    private var lastXcodebuildExit: Int? = null

    /** Host side of the port forward, per candidate address, as of the last preflight. */
    private val forwardStatusByHost = linkedMapOf<String, String>()

    /** An iproxy we started ourselves because nothing was listening; ours to clean up. */
    private var spawnedForwardProcess: Process? = null

    /** A go-ios runner we started as a fallback; ours to clean up. */
    private var goIosProcess: Process? = null

    /** The address that last answered a status check; [host] until a fallback wins. */
    @Volatile
    private var activeHost: String = host

    private var candidateHosts: List<String> = (listOf(host) + fallbackHosts).distinct()

    /** Whether each candidate name resolves; names that never resolve are not worth polling. */
    private val resolvableCache = linkedMapOf<String, Boolean>()

    /** Why each candidate last refused us, for the give-up message. */
    private val lastFailureByHost = linkedMapOf<String, String>()

    private fun refreshCandidateHosts() {
        val discovered = runCatching { additionalHostsProvider() }.getOrElse { e ->
            logger.debug("Could not refresh XCTest runner addresses", e)
            emptyList()
        }
        resolvableCache.entries.removeAll { !it.value }
        val added = discovered.filter { it.isNotBlank() && it !in candidateHosts }
        if (added.isNotEmpty()) {
            logger.info("Discovered additional XCTest runner address(es) while waiting: $added")
            candidateHosts = candidateHosts + added
        }
    }

    override fun uninstall(): Boolean {
        return metrics.measured("operation", mapOf("command" to "uninstall")) {
            // FIXME(bartekpacia): This method probably doesn't have to care about killing the XCTest Runner process.
            //  Just uninstalling should suffice. It automatically kills the process.

            if (useXcodeTestRunner || !reinstallDriver) {
                logger.trace("Skipping uninstalling XCTest Runner as USE_XCODE_TEST_RUNNER is set")
                return@measured false
            }

            if (!isChannelAlive()) return@measured false

            fun killXCTestRunnerProcess() {
                logger.trace("Will attempt to stop all alive XCTest Runner processes before uninstalling")

                if (xcTestProcess?.isAlive == true) {
                    logger.trace("XCTest Runner process started by us is alive, killing it")
                    xcTestProcess?.destroy()
                }
                xcTestProcess = null

                val pid = xcRunnerCLIUtils.pidForApp(UI_TEST_RUNNER_APP_BUNDLE_ID, deviceId)
                if (pid != null) {
                    logger.trace("Killing XCTest Runner process with the `kill` command")
                    ProcessBuilder(listOf("kill", pid.toString()))
                        .start()
                        .waitFor()
                }

                logger.trace("All XCTest Runner processes were stopped")
            }

            killXCTestRunnerProcess()

            logger.trace("Uninstalling XCTest Runner from device $deviceId")
            true
        }
    }

    override fun start(): XCTestClient {
        return metrics.measured("operation", mapOf("command" to "start")) {
            logger.info("start()")

            if (useXcodeTestRunner) {
                logger.info("USE_XCODE_TEST_RUNNER is set. Will wait for XCTest runner to be started manually")

                repeat(20) {
                    if (ensureOpen()) {
                        return@measured XCTestClient(activeHost, defaultPort)
                    }
                    logger.info("==> Start XCTest runner to continue flow")
                    Thread.sleep(500)
                }
                throw IllegalStateException("XCTest was not started manually")
            }


            val attempts = startupAttempts()
            // Retrying multiplies the worst case: each attempt can burn a full startup timeout
            // and each recovery can wait on the destination. Cap the lot, so a host where
            // everything is broken fails in bounded time instead of grinding for 15 minutes.
            val overallDeadline = System.currentTimeMillis() + totalStartupBudget()
            for (attempt in 1..attempts) {
                logger.info("[Start] Install XCUITest runner on $deviceId")
                startXCTestRunner(deviceId, iOSDriverConfig.prebuiltRunner)
                logger.info("[Done] Install XCUITest runner on $deviceId")

                awaitRunner()?.let {
                    if (attempt > 1) {
                        // Loud on success: a run that only went green on a retry still had a
                        // broken forward or an unavailable device, and that has to stay visible.
                        logger.warn("XCTest runner started on attempt $attempt of $attempts")
                    }
                    return@measured it
                }

                if (attempt == attempts || retryDisabled()) break
                if (System.currentTimeMillis() > overallDeadline) {
                    logger.warn("Overall driver startup budget of ${totalStartupBudget()} ms is spent; not retrying")
                    break
                }

                // Only retry against something we actually repaired. Relaunching into an
                // unchanged environment just burns another startup timeout and buries the
                // first (real) error under an identical second one.
                // Retry when we repaired something, and also when xcodebuild itself failed.
                // xcodebuild fails transiently on this path more often than it looks: a
                // CoreDevice hiccup surfaces as `Failed to install or launch the test runner
                // (Connection interrupted, IXRemoteErrorDomain code 6)` and exit 65, with the
                // forward and the destination both perfectly healthy. Relaunching clears it.
                val xcodebuildFailed = lastXcodebuildExit?.let { it != 0 } ?: false
                if (!recoverBeforeRetry() && !xcodebuildFailed) {
                    logger.warn(
                        "XCTest runner did not start, xcodebuild is still running, and neither the port " +
                            "forward nor the destination looks recoverable; not retrying"
                    )
                    break
                }
                if (xcodebuildFailed) {
                    logger.warn("xcodebuild exited with $lastXcodebuildExit; relaunching it")
                }
                lastXcodebuildExit = null

                // A runner left over from the previous attempt is worth keeping: restoring the
                // forward is often all that was ever wrong, and the runner is already up. Only
                // tear down one we still cannot reach.
                if (!isChannelAlive()) {
                    xcTestProcess?.takeIf { it.isAlive }?.destroy()
                    xcTestProcess = null
                }
                lastFailureByHost.clear()
                logger.warn("Retrying XCTest runner startup (attempt ${attempt + 1} of $attempts)")
            }

            goIosFallback()?.let { return@measured it }

            throw IOSDriverTimeoutException(driverStartupTimeoutMessage())
        }
    }

    /**
     * Last resort when `xcodebuild test-without-building` cannot bring the runner up: launch it
     * with go-ios instead.
     *
     * go-ios drives testmanagerd directly, so it does not care whether xcodebuild can resolve the
     * device as a test destination -- which is the failure this backs up. Verified not to disturb
     * a concurrent CoreDevice/xcodebuild session.
     */
    private fun goIosFallback(): XCTestClient? {
        if (iOSDriverConfig.prebuiltRunner || deviceType != IOSDeviceType.REAL) return null
        if (!System.getenv(MAESTRO_DISABLE_GOIOS_FALLBACK).isNullOrEmpty()) {
            logger.info("Skipping the go-ios fallback, $MAESTRO_DISABLE_GOIOS_FALLBACK is set")
            return null
        }

        val binary = GoIosCLIUtils.binary()
        if (binary == null) {
            logger.info("No go-ios binary found; not attempting the go-ios fallback")
            return null
        }
        if (!GoIosCLIUtils.hasTunnel(binary, deviceId)) {
            logger.warn(
                "go-ios is installed but has no tunnel for $deviceId, so it cannot reach an iOS 17+ " +
                    "device. Start one on this host with `sudo ios tunnel start` to enable this fallback."
            )
            return null
        }
        val xctestConfig = GoIosCLIUtils.xctestConfigName(buildProducts.uiRunnerPath)
        if (xctestConfig == null) {
            logger.warn("Could not find the .xctest bundle inside ${buildProducts.uiRunnerPath}; skipping the go-ios fallback")
            return null
        }

        logger.warn("xcodebuild could not bring the XCTest runner up on $deviceId; falling back to go-ios")

        // Stop the xcodebuild attempt first, so the two cannot fight over the same runner.
        xcTestProcess?.takeIf { it.isAlive }?.destroy()
        xcTestProcess = null
        lastFailureByHost.clear()

        goIosProcess = runCatching {
            GoIosCLIUtils.runWda(
                binary = binary,
                deviceId = deviceId,
                bundleId = UI_TEST_RUNNER_APP_BUNDLE_ID,
                xctestConfig = xctestConfig,
                port = defaultPort,
                logsDir = logsDir,
            )
        }.getOrElse { e ->
            logger.warn("Could not start the runner with go-ios", e)
            return null
        }

        val client = awaitRunner()
        if (client != null) {
            // Loud on success: the run is green, but xcodebuild is still broken on this host.
            logger.warn("XCTest runner started through the go-ios fallback, after xcodebuild failed")
        } else {
            goIosProcess?.takeIf { it.isAlive }?.destroy()
            goIosProcess = null
        }
        return client
    }

    /**
     * Repair what we can before retrying, and report whether anything actually changed.
     *
     * The two failures worth retrying are a dead host-side port forward and a device that was
     * not yet an available xcodebuild destination; both are transient and both leave the runner
     * itself perfectly fine.
     */
    private fun recoverBeforeRetry(): Boolean {
        var recovered = false

        val forward = forwardStatus(host)
        forwardStatusByHost[host] = forward
        if (forward != "listening") {
            logger.warn("Port forward $host:$defaultPort is $forward")
            recovered = restorePortForward()
        }

        recordDestinationProblem()
        destinationProblem?.let { problem ->
            logger.warn("$deviceId is not an available xcodebuild destination: $problem")
            logger.warn("Waiting up to $DESTINATION_RECOVERY_TIMEOUT_MS ms for it to become available")
            if (awaitAvailableDestination()) recovered = true
        }

        return recovered
    }

    /**
     * Start a forward of our own, but only when nothing is listening: whoever launched us
     * (a device-farm harness, typically) normally owns this forward and tears it down with a
     * `pkill iproxy` of its own. Filling a gap is safe; racing a forward somebody else manages
     * is not, so this never kills an existing one.
     */
    private fun restorePortForward(): Boolean {
        if (deviceType != IOSDeviceType.REAL) return false

        val binary = iproxyBinary()
        if (binary == null) {
            logger.warn("Cannot restore the port forward: no iproxy binary found (looked in $IPROXY_PATHS and on PATH)")
            return false
        }

        logger.warn("Starting $binary to forward $host:$defaultPort to $deviceId")
        val process = runCatching {
            ProcessBuilder(binary, "-u", deviceId, "$defaultPort:$defaultPort")
                .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
                .redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
                .start()
        }.getOrElse { e ->
            logger.warn("Could not start $binary", e)
            return false
        }
        spawnedForwardProcess = process

        val deadline = System.currentTimeMillis() + FORWARD_RESTART_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline && process.isAlive) {
            Thread.sleep(250)
            if (forwardStatus(host) == "listening") {
                logger.warn("Port forward $host:$defaultPort restored")
                forwardStatusByHost[host] = "listening"
                return true
            }
        }

        logger.warn("$binary did not bind $host:$defaultPort within $FORWARD_RESTART_TIMEOUT_MS ms")
        return false
    }

    /**
     * `iproxy` is a Homebrew binary, and PATH for a process spawned by a CI agent routinely
     * lacks the Homebrew prefix even though an interactive shell on the same host has it.
     */
    private fun iproxyBinary(): String? {
        IPROXY_PATHS.firstOrNull { File(it).canExecute() }?.let { return it }

        return runCatching {
            val which = ProcessBuilder("/usr/bin/which", "iproxy").start()
            val resolved = which.inputStream.bufferedReader().use { it.readText() }.trim()
            if (which.waitFor() == 0 && resolved.isNotEmpty()) resolved else null
        }.getOrNull()
    }

    private fun totalStartupBudget(): Long = runCatching {
        System.getenv(MAESTRO_DRIVER_STARTUP_TOTAL_TIMEOUT).toLong()
    }.getOrDefault(getStartupTimeout() * 2)

    private fun startupAttempts(): Int = runCatching {
        System.getenv(MAESTRO_DRIVER_STARTUP_ATTEMPTS).toInt().coerceAtLeast(1)
    }.getOrDefault(DEFAULT_STARTUP_ATTEMPTS)

    /**
     * Poll every candidate address until the runner answers, we run out of time, or xcodebuild
     * gives up. Returns null if the runner never answered.
     */
    private fun awaitRunner(): XCTestClient? {
        val startTime = System.currentTimeMillis()
        var nextRefresh = 0L

        while (System.currentTimeMillis() - startTime < getStartupTimeout()) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed >= nextRefresh) {
                refreshCandidateHosts()
                nextRefresh = elapsed + HOST_REFRESH_INTERVAL_MS
            }

            for (candidate in candidateHosts) {
                if (!isResolvable(candidate)) continue
                runCatching {
                    if (xcTestDriverStatusCheck(candidate)) {
                        if (candidate != activeHost) {
                            logger.info("XCTest runner answered on $candidate, using it for this session")
                        }
                        activeHost = candidate
                        return XCTestClient(candidate, defaultPort)
                    }
                }.onFailure { lastFailureByHost[candidate] = it.toString() }
            }

            // `xcodebuild test-without-building` owns the runner for as long as it runs, so once
            // it has exited the runner is never going to answer. Waiting out the rest of the
            // timeout only delays the error that xcodebuild has already written to its log.
            val process = xcTestProcess
            if (process != null && !process.isAlive) {
                lastXcodebuildExit = process.exitValue()
                logger.warn(
                    "`xcodebuild test-without-building` exited with ${process.exitValue()} after $elapsed ms; " +
                        "not waiting out the remaining ${getStartupTimeout() - elapsed} ms"
                )
                recordDestinationProblem()
                return null
            }

            Thread.sleep(500)
        }

        logger.warn("XCTest runner did not answer within ${getStartupTimeout()} ms")
        recordDestinationProblem()
        return null
    }

    /**
     * Skip candidates whose name does not resolve, instead of paying a DNS timeout for each on
     * every pass. The `*.coredevice.local` names devicectl reports do not resolve at all on a host
     * without a live tunnel, and each one cost a 5s lookup per polling cycle -- minutes of a
     * startup timeout spent on addresses that never had a chance of answering.
     */
    private fun isResolvable(targetHost: String): Boolean {
        if (isIpLiteral(targetHost)) return true
        resolvableCache[targetHost]?.let { return it }

        var resolved = false
        val probe = Thread {
            resolved = runCatching { InetAddress.getByName(targetHost) }.isSuccess
        }
        probe.isDaemon = true
        probe.start()
        probe.join(DNS_PROBE_TIMEOUT_MS)

        if (!resolved) {
            logger.debug("Skipping XCTest runner candidate $targetHost: does not resolve")
        }
        resolvableCache[targetHost] = resolved
        return resolved
    }

    /** IPv4 dotted-quad or anything containing a colon (IPv6); neither needs a DNS lookup. */
    private fun isIpLiteral(targetHost: String): Boolean =
        targetHost.contains(':') || targetHost.matches(IPV4_LITERAL)

    private fun retryDisabled(): Boolean = !System.getenv(MAESTRO_DISABLE_DRIVER_STARTUP_RETRY).isNullOrEmpty()

    /**
     * A raw connect distinguishes the two ways a forward can be wrong, which the driver status
     * check alone cannot: nothing listening on the host side at all (refused -- iproxy/usbmux is
     * not running) versus a live forward with nothing answering behind it (accepted, then reset
     * -- the runner is not up on the device).
     */
    private fun forwardStatus(targetHost: String): String {
        // InetSocketAddress resolves on construction, and that resolution is not covered by the
        // connect timeout: an unresolvable .coredevice.local name costs a full 5s mDNS lookup.
        // Bound the whole probe instead, or preflight would add ~5s per tunnel address to every
        // run just to report addresses that were never going to answer anyway.
        var result = "not probed (timed out after $FORWARD_PROBE_TIMEOUT_MS ms)"
        val probe = Thread {
            result = try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(targetHost, defaultPort), FORWARD_PROBE_TIMEOUT_MS)
                    "listening"
                }
            } catch (e: IOException) {
                "not listening (${e.message})"
            }
        }
        probe.isDaemon = true
        probe.start()
        probe.join(FORWARD_PROBE_TIMEOUT_MS.toLong())
        return result
    }

    private fun recordDestinationProblem() {
        if (iOSDriverConfig.prebuiltRunner) return
        destinationProblem = runCatching {
            xcRunnerCLIUtils.destinationProblem(deviceId, buildProducts.xctestRunPath.absolutePath)
        }.getOrElse { e ->
            logger.debug("Could not ask xcodebuild about destination $deviceId", e)
            null
        }
    }

    private fun awaitAvailableDestination(): Boolean {
        val deadline = System.currentTimeMillis() + DESTINATION_RECOVERY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(DESTINATION_RECOVERY_POLL_MS)
            recordDestinationProblem()
            val problem = destinationProblem
            if (problem == null) return true
            logger.info("$deviceId still unavailable as a destination: $problem")
        }
        return false
    }

    /**
     * Check what we can before committing to a multi-minute wait, so the reason a run is about
     * to fail is in the log at second 0 rather than at second 240.
     */
    private fun preflight(xcTestRunFilePath: String) {
        candidateHosts.forEach { candidate ->
            val status = forwardStatus(candidate)
            forwardStatusByHost[candidate] = status
            logger.info("Preflight: port forward $candidate:$defaultPort is $status")
        }

        destinationProblem = runCatching {
            xcRunnerCLIUtils.destinationProblem(deviceId, xcTestRunFilePath)
        }.getOrElse { e ->
            logger.debug("Could not ask xcodebuild about destination $deviceId", e)
            null
        }
        destinationProblem?.let {
            logger.warn("Preflight: xcodebuild will not accept $deviceId as a destination: $it")
        }
    }

    class IOSDriverTimeoutException(message: String): RuntimeException(message)

    private fun getStartupTimeout(): Long = runCatching {
        System.getenv(MAESTRO_DRIVER_STARTUP_TIMEOUT).toLong()
    }.getOrDefault(
        when (deviceType) {
            // Installing and launching the runner over a network tunnel (Wi-Fi attached
            // device) is several times slower than over USB.
            IOSDeviceType.REAL -> REAL_DEVICE_SERVER_LAUNCH_TIMEOUT_MS
            IOSDeviceType.SIMULATOR -> SERVER_LAUNCH_TIMEOUT_MS
        }
    )

    /**
     * The generic "not ready in time" message is ambiguous: the runner may have failed to
     * install/launch, or it may be running fine and unreachable at [host]:[defaultPort]
     * (a dead iproxy/usbmux forward, which is what happens when the device drops off USB
     * and is only attached over Wi-Fi). Surface the tail of the runner log so the two are
     * distinguishable without digging through ~/.maestro.
     */
    private fun driverStartupTimeoutMessage(): String {
        val logFile = runCatching {
            logsDir.listFiles { file -> file.name.startsWith("xctest_runner_") && file.extension == "log" }
                ?.maxByOrNull { it.lastModified() }
        }.getOrNull()
        val logTail = runCatching {
            logFile?.takeIf { it.exists() }
                ?.readLines()
                ?.takeLast(XCTEST_LOG_TAIL_LINES)
                ?.joinToString(System.lineSeparator())
        }.getOrNull()

        val tried = candidateHosts.joinToString(System.lineSeparator()) { candidate ->
            val forward = forwardStatusByHost[candidate]?.let { ", forward $it" } ?: ""
            "  $candidate:$defaultPort — ${lastFailureByHost[candidate] ?: "no response"}$forward"
        }
        return buildString {
            append("iOS driver not ready in time: no response from the XCTest runner after ")
            append("${getStartupTimeout()} ms. Either the runner failed to start on $deviceId, or it is ")
            append("running but unreachable (check your iproxy/usbmux port forward, and whether the device ")
            append("is attached over USB or the network). ")
            append("Consider increasing the timeout with the $MAESTRO_DRIVER_STARTUP_TIMEOUT env variable.")
            append(System.lineSeparator())
            append("Addresses tried:")
            append(System.lineSeparator())
            append(tried)
            destinationProblem?.let {
                append(System.lineSeparator())
                append("xcodebuild would not accept $deviceId as a destination: $it")
            }
            if (logFile != null && !logTail.isNullOrBlank()) {
                append(System.lineSeparator())
                append("Last $XCTEST_LOG_TAIL_LINES lines of ${logFile.absolutePath}:")
                append(System.lineSeparator())
                append(logTail)
            }
        }
    }

    override fun isChannelAlive(): Boolean {
        return metrics.measured("operation", mapOf("command" to "isChannelAlive")) {
        return@measured xcTestDriverStatusCheck()
        }
    }

    private fun ensureOpen(): Boolean {
        val timeout = 120_000L
        logger.info("ensureOpen(): Will spend $timeout ms waiting for the channel to become alive")
        val result = MaestroTimer.retryUntilTrue(timeout, 200, onException = {
            logger.error("ensureOpen() failed with exception: $it")
        }) { isChannelAlive() }
        logger.info("ensureOpen() finished, is channel alive?: $result")
        return result
    }

    private fun xcTestDriverStatusCheck(targetHost: String = activeHost): Boolean {
        logger.info("[Start] Perform XCUITest driver status check on $deviceId at $targetHost:$defaultPort")
        fun xctestAPIBuilder(pathSegment: String): HttpUrl.Builder {
            return HttpUrl.Builder()
                .scheme("http")
                .host(targetHost)
                .addPathSegment(pathSegment)
                .port(defaultPort)
        }

        val url by lazy {
            xctestAPIBuilder("status")
                .build()
        }

        val request by lazy {  Request.Builder()
            .get()
            .url(url)
            .build()
        }

        val checkSuccessful = try {
            httpClient.newCall(request).execute().use {
                logger.info("[Done] Perform XCUITest driver status check on $deviceId at $targetHost:$defaultPort")
                it.isSuccessful
            }
        } catch (ignore: IOException) {
            logger.info("[Failed] Perform XCUITest driver status check on $deviceId at $targetHost:$defaultPort, exception: $ignore")
            lastFailureByHost[targetHost] = ignore.toString()
            false
        }

        return checkSuccessful
    }

    private fun startXCTestRunner(deviceId: String, preBuiltRunner: Boolean) {
        if (isChannelAlive()) {
            logger.info("UI Test runner already running, returning")
            return
        }

        if (preBuiltRunner) {
            logger.info("Installing pre built driver without xcodebuild")
            installPrebuiltRunner(deviceId, buildProducts.uiRunnerPath)
        } else {
            logger.info("Installing driver with xcodebuild")
            preflight(buildProducts.xctestRunPath.absolutePath)
            logger.info("[Start] Running XcUITest with `xcodebuild test-without-building` with $defaultPort and config: $iOSDriverConfig")
            xcTestProcess = xcRunnerCLIUtils.runXcTestWithoutBuild(
                deviceId = this.deviceId,
                xcTestRunFilePath = buildProducts.xctestRunPath.absolutePath,
                port = defaultPort,
                snapshotKeyHonorModalViews = iOSDriverConfig.snapshotKeyHonorModalViews,
                logsDir = logsDir,
            )
            logger.info("[Done] Running XcUITest with `xcodebuild test-without-building`")
        }
    }

    private fun installPrebuiltRunner(deviceId: String, bundlePath: File) {
        logger.info("Installing prebuilt driver for $deviceId and type $deviceType")
        when (deviceType) {
            IOSDeviceType.REAL -> {
                LocalIOSDeviceController.install(deviceId, bundlePath.toPath())
                LocalIOSDeviceController.launchRunner(
                    deviceId = deviceId,
                    port = defaultPort,
                    snapshotKeyHonorModalViews = iOSDriverConfig.snapshotKeyHonorModalViews,
                    logsDir = logsDir,
                )
            }
            IOSDeviceType.SIMULATOR -> {
                localSimulatorUtils.install(deviceId, bundlePath.toPath())
                localSimulatorUtils.launchUITestRunner(
                    deviceId = deviceId,
                    port = defaultPort,
                    snapshotKeyHonorModalViews = iOSDriverConfig.snapshotKeyHonorModalViews,
                    logsDir = logsDir,
                )
            }
        }
    }

    @OptIn(ExperimentalPathApi::class)
    override fun close() {
        if (useXcodeTestRunner) {
            return
        }

        logger.info("[Start] Cleaning up the ui test runner files")
        spawnedForwardProcess?.takeIf { it.isAlive }?.let {
            logger.info("Stopping the port forward we started")
            it.destroy()
        }
        spawnedForwardProcess = null
        goIosProcess?.takeIf { it.isAlive }?.let {
            logger.info("Stopping the go-ios runner we started")
            it.destroy()
        }
        goIosProcess = null
        tempFileHandler.close()
        if(reinstallDriver) {
            uninstall()
            deviceController.close()
            logger.info("[Done] Cleaning up the ui test runner files")
        }
    }

    data class IOSDriverConfig(
        val prebuiltRunner: Boolean,
        val sourceDirectory: String,
        val context: Context,
        val snapshotKeyHonorModalViews: Boolean?
    )

    companion object {
        const val UI_TEST_RUNNER_APP_BUNDLE_ID = "dev.mobile.maestro-driver-iosUITests.xctrunner"

        private const val SERVER_LAUNCH_TIMEOUT_MS = 120000L
        private const val REAL_DEVICE_SERVER_LAUNCH_TIMEOUT_MS = 240000L
        private const val XCTEST_LOG_TAIL_LINES = 30
        private const val HOST_REFRESH_INTERVAL_MS = 5000L
        private const val FORWARD_PROBE_TIMEOUT_MS = 2000
        private const val DNS_PROBE_TIMEOUT_MS = 1000L
        private val IPV4_LITERAL = Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""")
        private const val DESTINATION_RECOVERY_TIMEOUT_MS = 60000L
        private const val DESTINATION_RECOVERY_POLL_MS = 5000L
        private const val FORWARD_RESTART_TIMEOUT_MS = 5000L
        private const val DEFAULT_STARTUP_ATTEMPTS = 3
        private val IPROXY_PATHS = listOf(
            "/opt/homebrew/bin/iproxy",
            "/usr/local/bin/iproxy",
            "/opt/local/bin/iproxy",
        )
        private const val MAESTRO_DRIVER_STARTUP_TIMEOUT = "MAESTRO_DRIVER_STARTUP_TIMEOUT"
        private const val MAESTRO_DRIVER_STARTUP_ATTEMPTS = "MAESTRO_DRIVER_STARTUP_ATTEMPTS"
        private const val MAESTRO_DRIVER_STARTUP_TOTAL_TIMEOUT = "MAESTRO_DRIVER_STARTUP_TOTAL_TIMEOUT"
        private const val MAESTRO_DISABLE_DRIVER_STARTUP_RETRY = "MAESTRO_DISABLE_DRIVER_STARTUP_RETRY"
        private const val MAESTRO_DISABLE_GOIOS_FALLBACK = "MAESTRO_DISABLE_GOIOS_FALLBACK"
    }

}