package util

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import java.io.File

class DeviceCtlProcess {

    fun devicectlAppsOutput(deviceId: String): File {
        val tempOutput = File.createTempFile("devicectl_apps_response", ".json")
        ProcessBuilder(
            listOf(
                "xcrun", "devicectl", "--json-output", tempOutput.path,
                "device", "info", "apps", "--device", deviceId
            )
        )
            .redirectError(ProcessBuilder.Redirect.PIPE).start().apply {
                waitFor()
            }

        return tempOutput
    }

    fun devicectlDevicesOutput(): File {
        val tempOutput = File.createTempFile("devicectl_response", ".json")
        ProcessBuilder(listOf("xcrun", "devicectl", "--json-output", tempOutput.path, "list", "devices"))
            .redirectError(ProcessBuilder.Redirect.PIPE).start().apply {
                waitFor()
            }

        return tempOutput
    }
}

class LocalIOSDevice(private val deviceCtlProcess: DeviceCtlProcess = DeviceCtlProcess()) {

    private val logger = LoggerFactory.getLogger(LocalIOSDevice::class.java)

    fun uninstall(deviceId: String, bundleIdentifier: String) {
        CommandLineUtils.runCommand(
            listOf(
                "xcrun",
                "devicectl",
                "device",
                "uninstall",
                "app",
                "--device",
                deviceId,
                bundleIdentifier
            )
        )
    }

    /**
     * Bundle identifiers of the apps installed on a physical device.
     *
     * The simulator equivalent, `XCRunnerCLIUtils.listApps`, goes through `simctl`, which has no
     * concept of a physical device and quietly returns nothing when handed a UDID.
     *
     * Returns an empty set rather than failing: the callers use this to help the XCTest runner
     * work out which app is in the foreground, and losing that is a degraded flow, not a fatal
     * one. Note that devicectl only sees devices CoreDevice paired with, so iOS 16 and older
     * yield nothing here.
     */
    fun listApps(deviceId: String): Set<String> {
        val tempOutput = deviceCtlProcess.devicectlAppsOutput(deviceId)
        return try {
            val response = tempOutput.readText()
            if (response.isBlank()) return emptySet()

            jacksonObjectMapper().readValue<DeviceCtlAppsResponse>(response)
                .result
                .apps
                .mapNotNull { it.bundleIdentifier?.takeIf { id -> id.isNotBlank() } }
                .toSet()
        } catch (e: Exception) {
            logger.warn("Failed to list apps installed on iOS device $deviceId via devicectl", e)
            emptySet()
        } finally {
            tempOutput.delete()
        }
    }

    fun listDeviceViaDeviceCtl(deviceId: String): DeviceCtlResponse.Device {
        val tempOutput = File.createTempFile("devicectl_response", ".json")
        try {
            ProcessBuilder(listOf("xcrun" , "devicectl", "--json-output", tempOutput.path, "list", "devices"))
                .redirectError(ProcessBuilder.Redirect.PIPE).start().apply {
                    waitFor()
                }
            val bytes = tempOutput.readBytes()
            val response = String(bytes)

            val jacksonObjectMapper = jacksonObjectMapper()
            jacksonObjectMapper.configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, false)
            val deviceCtlResponse = jacksonObjectMapper.readValue<DeviceCtlResponse>(response)
            return deviceCtlResponse.result.devices.find {
                it.hardwareProperties?.udid == deviceId
            } ?: throw IllegalArgumentException("iOS device with identifier $deviceId not connected or available")
        } finally {
            tempOutput.delete()
        }
    }

    fun listDeviceViaDeviceCtl(): List<DeviceCtlResponse.Device> {
        val tempOutput = deviceCtlProcess.devicectlDevicesOutput()
        try {
            val bytes = tempOutput.readBytes()
            val response = String(bytes)

            val deviceCtlResponse = jacksonObjectMapper().readValue<DeviceCtlResponse>(response)
            return deviceCtlResponse.result.devices
        } finally {
            tempOutput.delete()
        }
    }
}