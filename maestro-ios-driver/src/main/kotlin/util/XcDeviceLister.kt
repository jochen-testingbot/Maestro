package util

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One entry of `xcrun xcdevice list`.
 *
 * Unlike devicectl, `xcdevice` sits on Xcode's own device layer rather than on CoreDevice, so it
 * enumerates both device generations: iOS 17+ devices reachable over a CoreDevice tunnel, and
 * older ones reachable only over lockdown/usbmux. It reports the real 40-hex UDID (or the newer
 * `00008120-...` form) as [identifier], which is what every other part of Maestro keys devices by.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class XcDeviceEntry(
    val identifier: String? = null,
    val name: String? = null,
    val platform: String? = null,
    /** Version and build together, e.g. `16.7.16 (20H392)`. */
    val operatingSystemVersion: String? = null,
    val modelName: String? = null,
    val architecture: String? = null,
    /** `usb` or `wifi`. Named around Kotlin's reserved `interface`. */
    @param:JsonProperty("interface")
    val connectionInterface: String? = null,
    val simulator: Boolean = false,
    val available: Boolean = false,
    val ignored: Boolean = false,
    val error: XcDeviceError? = null,
) {

    /**
     * `xcdevice` lists everything Xcode has ever seen, including simulators, the host Mac, paired
     * watches and devices that are currently unplugged (`available: false`) or explicitly ignored.
     * Only entries that pass all of these are safe to hand back as connected devices.
     */
    val isUsablePhysicalIOSDevice: Boolean
        get() = !simulator &&
            available &&
            !ignored &&
            error == null &&
            platform == PLATFORM_IPHONEOS &&
            !identifier.isNullOrBlank()

    /** `16.7.16 (20H392)` -> `16.7.16`, to match what devicectl reports as `osVersionNumber`. */
    val osVersionNumber: String?
        get() = operatingSystemVersion?.substringBefore(" ")?.trim()?.takeIf { it.isNotBlank() }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class XcDeviceError(
        val code: Int? = null,
        val description: String? = null,
    )

    companion object {
        const val PLATFORM_IPHONEOS = "com.apple.platform.iphoneos"
    }
}

/**
 * Isolated from [XcDeviceLister] so tests can replay captured output without spawning Xcode.
 */
class XcDeviceProcess {

    /**
     * Writes to a temp file rather than draining the pipe: `xcdevice` waits on every attached
     * device before printing anything, so reading stdout inline would block past our own deadline.
     */
    fun xcdeviceListOutput(timeoutSeconds: Long): String {
        val tempOutput = File.createTempFile("xcdevice_response", ".json")
        try {
            val process = ProcessBuilder(
                listOf("xcrun", "xcdevice", "list", "-timeout", timeoutSeconds.toString())
            )
                .redirectOutput(tempOutput)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()

            // xcdevice honours -timeout itself; the extra grace is only so a wedged process is
            // reported as such rather than silently hanging device discovery.
            if (!process.waitFor(timeoutSeconds + PROCESS_GRACE_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException(
                    "`xcrun xcdevice list` did not finish within ${timeoutSeconds + PROCESS_GRACE_SECONDS}s"
                )
            }

            return tempOutput.readText()
        } finally {
            tempOutput.delete()
        }
    }

    private companion object {
        const val PROCESS_GRACE_SECONDS = 10L
    }
}

class XcDeviceLister(private val xcDeviceProcess: XcDeviceProcess = XcDeviceProcess()) {

    private val mapper by lazy { jacksonObjectMapper() }

    fun listPhysicalIOSDevices(timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): List<XcDeviceEntry> {
        val response = xcDeviceProcess.xcdeviceListOutput(timeoutSeconds)
        if (response.isBlank()) return emptyList()

        val entries = mapper.readValue<List<XcDeviceEntry>>(response)
        return entries.filter { it.isUsablePhysicalIOSDevice }
    }

    companion object {
        /**
         * `xcdevice` blocks for roughly this long when a device is attached, so it is deliberately
         * kept short: it runs on the device-discovery path of every `maestro test` invocation that
         * reaches it.
         */
        const val DEFAULT_TIMEOUT_SECONDS = 5L
    }
}
