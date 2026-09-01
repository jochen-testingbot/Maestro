package util

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import util.CommandLineUtils.runCommand
import java.io.File
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object LocalIOSDeviceController {

    private val dateFormatter by lazy { DateTimeFormatter.ofPattern(XCTEST_LOG_DATE_FORMAT) }
    private val date = dateFormatter.format(LocalDateTime.now())
    private val mapper by lazy { jacksonObjectMapper() }

    private const val MAESTRO_DEVICECTL_TIMEOUT = "MAESTRO_DEVICECTL_TIMEOUT"
    private const val DEFAULT_DEVICECTL_TIMEOUT_SECONDS = 600L

    /**
     * devicectl's own per-operation deadline. Over a network tunnel (Wi-Fi attached device)
     * transferring and installing a bundle is several times slower than over USB.
     */
    private fun devicectlTimeoutSeconds(): Long = runCatching {
        System.getenv(MAESTRO_DEVICECTL_TIMEOUT).toLong()
    }.getOrDefault(DEFAULT_DEVICECTL_TIMEOUT_SECONDS)

    private val supportedFlags = mutableMapOf<String, Boolean>()

    /**
     * devicectl's flags differ across Xcode releases, so probe `--help` once per subcommand
     * rather than hard-coding a version check: an unsupported flag makes devicectl exit with
     * a usage error, which would look like a device problem.
     */
    @Synchronized
    private fun supportsFlag(subcommand: List<String>, flag: String): Boolean {
        val key = "${subcommand.joinToString(" ")} $flag"
        return supportedFlags.getOrPut(key) {
            runCatching {
                val process = ProcessBuilder(listOf("xcrun", "devicectl") + subcommand + "--help")
                    .redirectErrorStream(true)
                    .start()
                val help = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                help.contains(flag)
            }.getOrDefault(false)
        }
    }

    private fun timeoutArgs(subcommand: List<String>, timeout: Long): List<String> =
        if (supportsFlag(subcommand, "--timeout")) listOf("--timeout", timeout.toString()) else emptyList()

    fun install(deviceId: String, path: Path) {
        val timeout = devicectlTimeoutSeconds()
        runCommand(
            listOf("xcrun", "devicectl", "device", "install", "app")
                + timeoutArgs(INSTALL_SUBCOMMAND, timeout)
                + listOf(
                    "--device",
                    deviceId,
                    path.toAbsolutePath().toString(),
                ),
            // Give the JVM-side watchdog room beyond devicectl's own deadline so the
            // failure is reported by devicectl, with its diagnostics, rather than by us.
            timeoutSeconds = timeout + COMMAND_TIMEOUT_GRACE_SECONDS,
        )
    }

    fun launchRunner(deviceId: String, port: Int, snapshotKeyHonorModalViews: Boolean?, logsDir: File) {
        val outputFile = xctestLogFile(logsDir, date)

        // devicectl does not forward the caller's environment to the process it launches on
        // the device (unlike simctl's SIMCTL_CHILD_* convention), so the runner's port has to
        // be passed explicitly. Without this the runner falls back to its built-in default
        // port and the driver status check polls a port nobody is listening on.
        val environment = mutableMapOf("PORT" to port.toString())
        if (snapshotKeyHonorModalViews != null) {
            environment["snapshotKeyHonorModalViews"] = snapshotKeyHonorModalViews.toString()
        }

        val environmentArgs = if (supportsFlag(LAUNCH_SUBCOMMAND, "--environment-variables")) {
            listOf("--environment-variables", mapper.writeValueAsString(environment))
        } else {
            emptyList()
        }

        runCommand(
            listOf("xcrun", "devicectl", "device", "process", "launch")
                + timeoutArgs(LAUNCH_SUBCOMMAND, devicectlTimeoutSeconds())
                + environmentArgs
                + listOf(
                    "--terminate-existing",
                    "--device",
                    deviceId,
                    "dev.mobile.maestro-driver-iosUITests.xctrunner"
                ),
            // Belt and braces for devicectl builds without --environment-variables: any
            // DEVICECTL_CHILD_-prefixed variable in the caller's environment is forwarded to
            // the launched process. Ignored when --environment-variables is passed.
            params = environment.mapKeys { (key, _) -> "DEVICECTL_CHILD_$key" },
            waitForCompletion = false,
            outputFile = outputFile
        )
    }

    private const val COMMAND_TIMEOUT_GRACE_SECONDS = 60L
    private val INSTALL_SUBCOMMAND = listOf("device", "install", "app")
    private val LAUNCH_SUBCOMMAND = listOf("device", "process", "launch")
}
