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
import util.LocalSimulatorUtils
import util.XCRunnerCLIUtils
import xcuitest.XCTestClient
import java.io.File
import java.io.IOException
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

    private var xcTestProcess: Process? = null

    /** The address that last answered a status check; [host] until a fallback wins. */
    @Volatile
    private var activeHost: String = host

    private var candidateHosts: List<String> = (listOf(host) + fallbackHosts).distinct()

    /** Why each candidate last refused us, for the give-up message. */
    private val lastFailureByHost = linkedMapOf<String, String>()

    private fun refreshCandidateHosts() {
        val discovered = runCatching { additionalHostsProvider() }.getOrElse { e ->
            logger.debug("Could not refresh XCTest runner addresses", e)
            emptyList()
        }
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


            logger.info("[Start] Install XCUITest runner on $deviceId")
            startXCTestRunner(deviceId, iOSDriverConfig.prebuiltRunner)
            logger.info("[Done] Install XCUITest runner on $deviceId")

            val startTime = System.currentTimeMillis()
            var nextRefresh = 0L

            while (System.currentTimeMillis() - startTime < getStartupTimeout()) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= nextRefresh) {
                    refreshCandidateHosts()
                    nextRefresh = elapsed + HOST_REFRESH_INTERVAL_MS
                }

                for (candidate in candidateHosts) {
                    runCatching {
                        if (xcTestDriverStatusCheck(candidate)) {
                            if (candidate != activeHost) {
                                logger.info("XCTest runner answered on $candidate, using it for this session")
                            }
                            activeHost = candidate
                            return@measured XCTestClient(candidate, defaultPort)
                        }
                    }.onFailure { lastFailureByHost[candidate] = it.toString() }
                }
                Thread.sleep(500)
            }

            throw IOSDriverTimeoutException(driverStartupTimeoutMessage())
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
            "  $candidate:$defaultPort — ${lastFailureByHost[candidate] ?: "no response"}"
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

        val buildProducts = iosBuildProductsExtractor.extract(iOSDriverConfig.sourceDirectory)

        if (preBuiltRunner) {
            logger.info("Installing pre built driver without xcodebuild")
            installPrebuiltRunner(deviceId, buildProducts.uiRunnerPath)
        } else {
            logger.info("Installing driver with xcodebuild")
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
        private const val MAESTRO_DRIVER_STARTUP_TIMEOUT = "MAESTRO_DRIVER_STARTUP_TIMEOUT"
    }

}