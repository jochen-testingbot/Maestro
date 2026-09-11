package util

import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Launching the XCTest runner through [go-ios](https://github.com/danielpaulus/go-ios) instead of
 * `xcodebuild test-without-building`.
 *
 * go-ios speaks the testmanagerd/DTX protocol directly, so it starts a real XCUITest session --
 * unlike `devicectl device process launch`, which starts the runner's container app and never
 * runs the test bundle that binds the port.
 *
 * On iOS 17+ this needs a RemoteXPC tunnel. Creating one requires root, which is not something to
 * do from inside a test run, so this only ever *uses* a tunnel somebody else has already started
 * (`sudo ios tunnel start`, typically once at host boot). Without one, the fallback declines.
 */
object GoIosCLIUtils {

    private val logger = LoggerFactory.getLogger(GoIosCLIUtils::class.java)
    private val dateFormatter by lazy { DateTimeFormatter.ofPattern(XCTEST_LOG_DATE_FORMAT) }

    /**
     * go-ios is commonly installed as an npm package, so its binary is a symlink under a prefix
     * that is on an interactive shell's PATH but not on the PATH of a process spawned by a CI
     * agent. Look in the usual prefixes before falling back to PATH.
     */
    private val CANDIDATE_PATHS = listOf(
        "${System.getProperty("user.home")}/.npm-packages/bin/ios",
        "/usr/local/bin/ios",
        "/opt/homebrew/bin/ios",
    )

    private const val TUNNEL_CHECK_TIMEOUT_SECONDS = 20L

    fun binary(): String? {
        CANDIDATE_PATHS.firstOrNull { File(it).canExecute() }?.let { return it }

        return runCatching {
            val which = ProcessBuilder("/usr/bin/which", "ios").start()
            val resolved = which.inputStream.bufferedReader().use { it.readText() }.trim()
            if (which.waitFor() == 0 && resolved.isNotEmpty()) resolved else null
        }.getOrNull()
    }

    /**
     * `ios tunnel ls` exits non-zero when no tunnel agent is reachable, which is exactly the
     * case where `runwda` would fail on an iOS 17+ device.
     */
    fun hasTunnel(binary: String, deviceId: String): Boolean = runCatching {
        val process = ProcessBuilder(binary, "tunnel", "ls", "--udid=$deviceId")
            .redirectErrorStream(true)
            .start()
        val output = StringBuilder()
        val drain = Thread { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }
        drain.isDaemon = true
        drain.start()

        if (!process.waitFor(TUNNEL_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return false
        }
        drain.join(TimeUnit.SECONDS.toMillis(1))
        process.exitValue() == 0 && output.contains("udid")
    }.getOrDefault(false)

    /**
     * The test bundle inside the runner app, e.g. `maestro-driver-iosUITests.xctest`. go-ios needs
     * it by name, and reading it off the bundle avoids hard-coding a name the build could change.
     */
    fun xctestConfigName(uiRunnerPath: File): String? =
        File(uiRunnerPath, "PlugIns")
            .listFiles { file -> file.extension == "xctest" }
            ?.firstOrNull()
            ?.name

    fun runWda(
        binary: String,
        deviceId: String,
        bundleId: String,
        xctestConfig: String,
        port: Int,
        logsDir: File,
    ): Process {
        val outputFile = xctestLogFile(logsDir, dateFormatter.format(LocalDateTime.now()))
        val command = listOf(
            binary,
            "runwda",
            "--udid=$deviceId",
            "--bundleid=$bundleId",
            "--testrunnerbundleid=$bundleId",
            "--xctestconfig=$xctestConfig",
            "--env=PORT=$port",
        )
        logger.info("Running command line operation: $command")
        return ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(outputFile))
            .start()
    }
}
