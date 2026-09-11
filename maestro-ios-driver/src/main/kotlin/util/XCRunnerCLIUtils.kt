package util

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.utils.TempFileHandler
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString

const val XCTEST_LOG_DATE_FORMAT = "yyyy-MM-dd_HHmmss"

internal fun xctestLogFile(logsDir: File, date: String): File {
    if (!logsDir.exists()) logsDir.mkdirs()
    return File(logsDir, "xctest_runner_$date.log")
}

class XCRunnerCLIUtils(private val tempFileHandler: TempFileHandler = TempFileHandler()) {

    private val dateFormatter by lazy { DateTimeFormatter.ofPattern(XCTEST_LOG_DATE_FORMAT) }

    companion object {
        private const val MAESTRO_XCODEBUILD_DESTINATION_TIMEOUT = "MAESTRO_XCODEBUILD_DESTINATION_TIMEOUT"
        private const val DEFAULT_DESTINATION_TIMEOUT_SECONDS = 120L
        private const val SHOW_DESTINATIONS_TIMEOUT_SECONDS = 60L
    }

    /**
     * Ask xcodebuild whether it can resolve [deviceId] as a destination for this xctestrun, and
     * if not, why. `test-without-building` only reports that after it has given up waiting for
     * the destination (two minutes by default), and it reports it into its own log file rather
     * than to the caller — so the device's own explanation for refusing the run ("Development
     * services need to be enabled", "is currently locked", a stale pairing) stays buried until
     * somebody goes digging in ~/.maestro.
     *
     * `-showdestinations` lists destinations and exits; it does not run the tests.
     *
     * Returns null when the device is offered as a destination without complaint.
     */
    fun destinationProblem(deviceId: String, xcTestRunFilePath: String): String? {
        val process = ProcessBuilder(
            "xcodebuild",
            "test-without-building",
            "-xctestrun",
            xcTestRunFilePath,
            "-destination",
            "id=$deviceId",
            "-showdestinations",
        ).redirectErrorStream(true).start()

        val output = StringBuilder()
        val drain = Thread { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }
        drain.isDaemon = true
        drain.start()

        if (!process.waitFor(SHOW_DESTINATIONS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return "`xcodebuild -showdestinations` did not answer within $SHOW_DESTINATIONS_TIMEOUT_SECONDS seconds"
        }
        drain.join(TimeUnit.SECONDS.toMillis(1))

        // Entries look like:
        //   { platform:iOS, arch:arm64e, id:<udid>, name:iPhone }
        //   { platform:iOS, arch:arm64e, id:<udid>, name:iPhone, error:iPhone is not available because ... }
        val entries = output.lines().filter { it.contains("id:$deviceId") }
        if (entries.isEmpty()) {
            return "xcodebuild does not offer $deviceId as a destination for this xctestrun"
        }

        val failing = entries.firstOrNull { it.contains("error:") } ?: return null
        return failing.substringAfter("error:").substringBeforeLast("}").trim()
    }

    fun listApps(deviceId: String): Set<String> {
        val process = Runtime.getRuntime().exec(arrayOf("bash", "-c", "xcrun simctl listapps $deviceId | plutil -convert json - -o -"))

        val json = String(process.inputStream.readBytes())

        if (json.isEmpty()) return emptySet()

        val mapper = jacksonObjectMapper()
        val appsMap = mapper.readValue(json, Map::class.java) as Map<String, Any>

        return appsMap.keys
    }

    fun setProxy(host: String, port: Int) {
        ProcessBuilder("networksetup", "-setwebproxy", "Wi-Fi", host, port.toString())
            .redirectErrorStream(true)
            .start()
            .waitFor()
        ProcessBuilder("networksetup", "-setsecurewebproxy", "Wi-Fi", host, port.toString())
            .redirectErrorStream(true)
            .start()
            .waitFor()
    }

    fun resetProxy() {
        ProcessBuilder("networksetup", "-setwebproxystate", "Wi-Fi", "off")
            .redirectErrorStream(true)
            .start()
            .waitFor()
        ProcessBuilder("networksetup", "-setsecurewebproxystate", "Wi-Fi", "off")
            .redirectErrorStream(true)
            .start()
            .waitFor()
    }

    fun uninstall(bundleId: String, deviceId: String) {
        CommandLineUtils.runCommand(
            listOf(
                "xcrun",
                "simctl",
                "uninstall",
                deviceId,
                bundleId
            )
        )
    }

    private fun runningApps(deviceId: String): Map<String, Int?> {
        val process = ProcessBuilder(
            "xcrun",
            "simctl",
            "spawn",
            deviceId,
            "launchctl",
            "list"
        ).start()

        val processOutput = process.inputStream.bufferedReader().readLines()

        if (!process.waitFor(3000, TimeUnit.MILLISECONDS)) {
            return emptyMap()
        }
        return processOutput
            .asSequence()
            .drop(1)
            .toList()
            .map { line -> line.split("\\s+".toRegex()) }
            .filter { parts -> parts.count() <= 3 }
            .associate { parts -> parts[2] to parts[0].toIntOrNull() }
            .mapKeys { (key, _) ->
                // Fixes issue with iOS 14.0 where process names are sometimes prefixed with "UIKitApplication:"
                // and ending with [stuff]
                key
                    .substringBefore("[")
                    .replace("UIKitApplication:", "")
            }
    }

    fun pidForApp(bundleId: String, deviceId: String): Int? {
        return runningApps(deviceId)[bundleId]
    }

    private fun destinationTimeoutSeconds(): Long = runCatching {
        System.getenv(MAESTRO_XCODEBUILD_DESTINATION_TIMEOUT).toLong()
    }.getOrDefault(DEFAULT_DESTINATION_TIMEOUT_SECONDS)

    fun runXcTestWithoutBuild(
        deviceId: String,
        xcTestRunFilePath: String,
        port: Int,
        snapshotKeyHonorModalViews: Boolean?,
        logsDir: File,
    ): Process {
        val date = dateFormatter.format(LocalDateTime.now())
        val outputFile = xctestLogFile(logsDir, date)
        val logOutputDir = tempFileHandler.createTempDirectory("maestro_xctestrunner_xcodebuild_output").toPath()
        val params = mutableMapOf("TEST_RUNNER_PORT" to port.toString())
        if (snapshotKeyHonorModalViews != null) {
            params["TEST_RUNNER_snapshotKeyHonorModalViews"] = snapshotKeyHonorModalViews.toString()
        }
        return CommandLineUtils.runCommand(
            listOf(
                "xcodebuild",
                "test-without-building",
                "-xctestrun",
                xcTestRunFilePath,
                "-destination",
                "id=$deviceId",
                // A network-attached device stays "unavailable" until Xcode has brought the
                // CoreDevice tunnel up, which routinely takes longer than xcodebuild's default
                // destination-resolution window.
                "-destination-timeout",
                destinationTimeoutSeconds().toString(),
                "-derivedDataPath",
                logOutputDir.absolutePathString()
            ),
            waitForCompletion = false,
            outputFile = outputFile,
            params = params,
        )
    }
}
