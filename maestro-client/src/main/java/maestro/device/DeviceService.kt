package maestro.device

import dadb.Dadb
import dadb.adbserver.AdbServer
import maestro.device.util.AndroidEnvUtils
import maestro.device.util.AvdDevice
import maestro.device.util.PrintUtils
import maestro.drivers.AndroidDriver
import maestro.drivers.CdpWebDriver
import maestro.utils.MaestroTimer
import maestro.utils.TempFileHandler
import okio.buffer
import okio.source
import org.slf4j.LoggerFactory
import util.DeviceCtlResponse
import util.LocalIOSDevice
import util.LocalSimulatorUtils
import util.SimctlList
import util.XcDeviceLister
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class AvdInfo(val name: String, val model: String, val os: String)

object DeviceService {
    private val logger = LoggerFactory.getLogger(DeviceService::class.java)

    private val tempFileHandler = TempFileHandler()
    private val localSimulatorUtils = LocalSimulatorUtils(tempFileHandler)

    fun startDevice(
        device: Device.AvailableForLaunch,
        driverHostPort: Int?,
        connectedDevices: Set<String> = setOf()
    ): Device.Connected {
        when (device.deviceSpec.platform) {
            Platform.IOS -> {
                PrintUtils.message("Launching Simulator...")
                try {
                    val iosSpec = device.deviceSpec as DeviceSpec.Ios
                    localSimulatorUtils.bootSimulator(device.modelId)
                    PrintUtils.message("Setting the device locale to ${iosSpec.locale.code}...")
                    localSimulatorUtils.setDeviceLanguage(device.modelId, iosSpec.locale.languageCode)
                    localSimulatorUtils.setDeviceLocale(device.modelId, iosSpec.locale.code)
                    localSimulatorUtils.reboot(device.modelId)
                    localSimulatorUtils.launchSimulator(device.modelId)
                    localSimulatorUtils.awaitLaunch(device.modelId)
                } catch (e: LocalSimulatorUtils.SimctlError) {
                    logger.error("Failed to launch simulator", e)
                    throw DeviceError(e.message)
                }

                return Device.Connected(
                    instanceId = device.modelId,
                    description = device.description,
                    platform = device.platform,
                    deviceType = device.deviceType,
                    deviceSpec = device.deviceSpec,
                )
            }

            Platform.ANDROID -> {
                PrintUtils.message("Launching Emulator...")
                val androidSpec = device.deviceSpec as DeviceSpec.Android
                val emulatorBinary = requireEmulatorBinary()

                ProcessBuilder(
                    emulatorBinary.absolutePath,
                    "-avd",
                    device.modelId,
                    "-netdelay",
                    "none",
                    "-netspeed",
                    "full"
                ).start().waitFor(10, TimeUnit.SECONDS)

                var lastException: Exception? = null

                val dadb = MaestroTimer.withTimeout(60000) {
                    try {
                        Dadb.list().lastOrNull { dadb ->
                            !connectedDevices.contains(dadb.toString())
                        }
                    } catch (ignored: Exception) {
                        Thread.sleep(100)
                        lastException = ignored
                        null
                    }
                } ?: throw DeviceError("Unable to start device: ${device.modelId}", lastException)

                PrintUtils.message("Waiting for emulator ( ${device.modelId} ) to boot...")
                while (!bootComplete(dadb)) {
                    Thread.sleep(1000)
                }

                PrintUtils.message("Setting the device locale to ${androidSpec.locale.code}...")
                val driver = AndroidDriver(dadb, driverHostPort)
                driver.installMaestroDriverApp()
                val result = driver.setDeviceLocale(
                    country = androidSpec.locale.countryCode,
                    language = androidSpec.locale.languageCode,
                )

                when (result) {
                    SET_LOCALE_RESULT_SUCCESS -> PrintUtils.message("[Done] Setting the device locale to ${androidSpec.locale.code}...")
                    SET_LOCALE_RESULT_LOCALE_NOT_VALID -> throw IllegalStateException("Failed to set locale ${androidSpec.locale.code}, the locale is not valid for a chosen device")
                    SET_LOCALE_RESULT_UPDATE_CONFIGURATION_FAILED -> throw IllegalStateException("Failed to set locale ${androidSpec.locale.code}, exception during updating configuration occurred")
                    else -> throw IllegalStateException("Failed to set locale ${androidSpec.locale.code}, unknown exception happened")
                }
                driver.uninstallMaestroDriverApp()

                return Device.Connected(
                    instanceId = dadb.toString(),
                    description = device.description,
                    platform = device.platform,
                    deviceType = device.deviceType,
                    deviceSpec = device.deviceSpec,
                )
            }

            Platform.WEB -> {
                PrintUtils.message("Launching Web...")
                CdpWebDriver(isStudio = false, isHeadless = false, screenSize = null).open()

                return Device.Connected(
                    instanceId = "chromium",
                    description = "Chromium Web Browser",
                    platform = device.platform,
                    deviceType = device.deviceType,
                    deviceSpec = device.deviceSpec,
                )
            }
        }
    }

    fun listConnectedDevices(
        includeWeb: Boolean = false,
        host: String? = null,
        port: Int? = null,
    ): List<Device.Connected> {
        return listDevices(includeWeb = includeWeb, host, port)
            .filterIsInstance<Device.Connected>()
    }

    fun <T : Device> List<T>.withPlatform(platform: Platform?) =
        filter { platform == null || it.platform == platform }

    fun listAvailableForLaunchDevices(includeWeb: Boolean = false): List<Device.AvailableForLaunch> {
        return listDevices(includeWeb = includeWeb)
            .filterIsInstance<Device.AvailableForLaunch>()
    }

     fun listDevices(includeWeb: Boolean, host: String? = null, port: Int? = null): List<Device> {
        return listAndroidDevices(host, port) +
                listIOSDevices() +
                if (includeWeb) {
                    listWebDevices()
                } else {
                    listOf()
                }
    }

    fun listWebDevices(): List<Device> {
        return listOf(
            Device.Connected(
                platform = Platform.WEB,
                description = "Chromium Web Browser",
                instanceId = "chromium",
                deviceType = Device.DeviceType.BROWSER,
                deviceSpec = DeviceSpec.Web.DEFAULT
            ),
            Device.AvailableForLaunch(
                modelId = "chromium",
                description = "Chromium Web Browser",
                platform = Platform.WEB,
                deviceType = Device.DeviceType.BROWSER,
                deviceSpec = DeviceSpec.Web.DEFAULT
            )
        )
    }

    fun listAndroidDevices(host: String? = null, port: Int? = null): List<Device> {
        val host = host ?: "localhost"
        if (port != null) {
            val dadb = Dadb.create(host, port)
            return listOf(
                Device.Connected(
                    instanceId = dadb.toString(),
                    description = dadb.toString(),
                    platform = Platform.ANDROID,
                    deviceType = Device.DeviceType.EMULATOR,
                    deviceSpec = DeviceSpec.Android.DEFAULT
                )
            )
        }

        // Fetch AVD info once (model + os) to avoid repeated avdmanager calls
        val avdInfoList = fetchAndroidAvdInfo()

        val connected = runCatching {
            Dadb.list(host = host).map { dadb ->
                val avdName = runCatching {
                    dadb.shell("getprop ro.kernel.qemu").output.trim().let { qemuProp ->
                        if (qemuProp == "1") {
                            val avdNameResult = ProcessBuilder("adb", "-s", dadb.toString(), "emu", "avd", "name")
                                .redirectErrorStream(true)
                                .start()
                                .apply { waitFor(5, TimeUnit.SECONDS) }
                                .inputStream.bufferedReader().readLine()?.trim() ?: ""

                            if (avdNameResult.isNotBlank() && !avdNameResult.contains("unknown AVD")) {
                                avdNameResult
                            } else null
                        } else null
                    }
                }.getOrNull()

                val instanceId = dadb.toString()
                val deviceType = when {
                    instanceId.startsWith("emulator") -> Device.DeviceType.EMULATOR
                    else -> Device.DeviceType.REAL
                }
                Device.Connected(
                    instanceId = instanceId,
                    description = avdName ?: dadb.toString(),
                    platform = Platform.ANDROID,
                    deviceType = deviceType,
                    deviceSpec = DeviceSpec.Android.DEFAULT,
                )
            }
        }.getOrNull() ?: emptyList()

        // Note that there is a possibility that AVD is actually already connected and is present in
        // connectedDevices.
        val avds = try {
            val emulatorBinary = requireEmulatorBinary()
            ProcessBuilder(emulatorBinary.absolutePath, "-list-avds")
                .start()
                .inputStream
                .bufferedReader()
                .useLines { lines ->
                    lines
                        .map { avdName ->
                            val avdInfo = avdInfoList.find { it.name == avdName } ?: AvdInfo(name = avdName, model = "", os = "")
                            Device.AvailableForLaunch(
                                modelId = avdName,
                                description = avdName,
                                platform = Platform.ANDROID,
                                deviceType = Device.DeviceType.EMULATOR,
                                deviceSpec = if (avdInfo.model.isBlank() || avdInfo.os.isBlank()) {
                                    DeviceSpec.Android.DEFAULT
                                } else {
                                    DeviceSpec.Android(model = avdInfo.model, os = avdInfo.os)
                                }
                            )
                        }
                        .toList()
                }
        } catch (ignored: Exception) {
            emptyList()
        }

        return connected + avds
    }

    /**
     * Runs `avdmanager list avd` and returns a list of AvdInfo
     * - name
     * - model: the canonical device ID from avdmanager, e.g. "pixel_6"
     * - os: "android-XX" derived from the API level, e.g. "android-34"
     *
     * Falls back to config.ini for the OS if avdmanager output lacks it.
     * Returns empty string on any failure
     */
    private fun fetchAndroidAvdInfo(): List<AvdInfo> {
        return try {
            val avd = requireAvdManagerBinary()
            val output = ProcessBuilder(avd.absolutePath, "list", "avd")
                .redirectErrorStream(true)
                .start()
                .apply { waitFor(30, TimeUnit.SECONDS) }
                .inputStream.bufferedReader().readText()
            parseAvdInfo(output, AndroidEnvUtils.androidAvdHome)
        } catch (e: Exception) {
            emptyList()
        }
    }

    internal fun parseAvdInfo(output: String, avdHome: File): List<AvdInfo> {
        val result = mutableListOf<AvdInfo>()
        var currentName: String? = null
        var currentModel: String? = null
        var currentOs: String? = null

        for (line in output.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Name:") -> {
                    // Save previous block
                    if (currentName != null) {
                        result += AvdInfo(name = currentName, model = currentModel ?: "", os = currentOs ?: "")
                    }
                    currentName = trimmed.removePrefix("Name:").trim()
                    currentModel = null
                    currentOs = null
                }
                trimmed.startsWith("Device:") -> {
                    // "pixel_6 (Google Pixel 6)" → "pixel_6"
                    currentModel = trimmed.removePrefix("Device:").trim().substringBefore(" ")
                }
                currentOs == null && currentName != null -> {
                    // Read OS from config.ini once we have the AVD name
                    val configFile = File(avdHome, "$currentName.avd/config.ini")
                    if (configFile.exists()) {
                        val sysdir = configFile.readLines()
                            .firstOrNull { it.startsWith("image.sysdir.1=") }
                            ?.substringAfter("=")
                        currentOs = sysdir?.split("/")?.firstOrNull { it.startsWith("android-") }
                    }
                }
            }
        }
        // Save last block
        if (currentName != null) {
            result += AvdInfo(name = currentName, model = currentModel ?: "", os = currentOs ?: "")
        }
        return result
    }

    fun listIOSDevices(): List<Device> {
        val simctlList = try {
            localSimulatorUtils.list()
        } catch (ignored: Exception) {
            return emptyList()
        }

        val runtimeNameByIdentifier = simctlList
            .runtimes
            .associate { it.identifier to it.name }

        return simctlList
            .devices
            .flatMap { runtime ->
                runtime.value
                    .filter { it.isAvailable }
                    .map { device(runtimeNameByIdentifier, runtime, it) }
            } + runCatching { listIOSConnectedDevices() }.getOrElse { e ->
                // A single unparseable/odd entry from devicectl must not take down simulator
                // listing along with it.
                logger.warn("Failed to list connected iOS devices via devicectl", e)
                emptyList()
            }
    }

    fun listIOSConnectedDevices(): List<Device.Connected> {
        val connectedIphoneList = runCatching { LocalIOSDevice().listDeviceViaDeviceCtl() }
            .getOrElse { e ->
                // devicectl being unusable must not stop us from looking for devices it would
                // not have been able to report anyway.
                logger.warn("Failed to list connected iOS devices via devicectl", e)
                emptyList()
            }

        val devicesFromDeviceCtl = connectedIphoneList.mapNotNull { device ->
            val udid = device.hardwareProperties?.udid
            // Accept devices that are either connected via tunnel OR have Developer Mode enabled
            val isDeveloperModeEnabled = device.deviceProperties?.developerModeStatus == "enabled"
            val isTunnelConnected = device.connectionProperties.isTunnelConnected

            if (udid == null || (!isTunnelConnected && !isDeveloperModeEnabled)) {
                return@mapNotNull null
            }

            if (!isTunnelConnected) {
                logger.warn(
                    "iOS device {} is listed but its devicectl tunnel is '{}' (transport: {}). " +
                        "Driver installation may fail until the tunnel is up.",
                    udid,
                    device.connectionProperties.tunnelState ?: "unknown",
                    device.connectionProperties.transportType ?: "unknown",
                )
            }

            val description = listOfNotNull(
                device.deviceProperties?.name,
                device.deviceProperties?.osVersionNumber,
                device.identifier
            ).joinToString(" - ")

            Device.Connected(
                instanceId = udid,
                description = description,
                platform = Platform.IOS,
                deviceType = Device.DeviceType.REAL,
                deviceSpec = DeviceSpec.Ios.DEFAULT
            )
        }

        return devicesFromDeviceCtl + legacyIOSConnectedDevices(connectedIphoneList, devicesFromDeviceCtl)
    }

    /**
     * Devices that CoreDevice will not talk to, found through `xcrun xcdevice list` instead.
     *
     * CoreDevice refuses to pair with anything older than iOS 17. Such a device still shows up in
     * `devicectl list devices`, but as a stub: `pairingState: unsupported` and no
     * `hardwareProperties.udid`, so the mapping above drops it and `maestro test` concludes that
     * no device is connected. The device itself is perfectly driveable — the CLI launches the
     * XCTest runner with `xcodebuild test-without-building`, which reaches it over the legacy
     * lockdown path — so the gap is purely one of discovery.
     *
     * Only consulted when devicectl came up short, because `xcdevice` waits on every attached
     * device before answering and this sits on the startup path of every run. A UDID-less devicectl
     * entry is precisely the signature of a device CoreDevice turned down; a host with no devices
     * attached, or one whose devices all resolved, pays nothing.
     */
    private fun legacyIOSConnectedDevices(
        deviceCtlEntries: List<DeviceCtlResponse.Device>,
        alreadyFound: List<Device.Connected>,
    ): List<Device.Connected> {
        if (!shouldQueryXcDevice(deviceCtlEntries)) return emptyList()

        val entries = runCatching { XcDeviceLister().listPhysicalIOSDevices() }
            .getOrElse { e ->
                logger.warn("Failed to list connected iOS devices via xcdevice", e)
                return emptyList()
            }

        val knownIds = alreadyFound.map { it.instanceId }.toSet()
        return entries
            .filter { it.identifier !in knownIds }
            .map { entry ->
                val udid = requireNotNull(entry.identifier)
                logger.info(
                    "iOS device {} ({}, iOS {}) is not managed by CoreDevice; discovered via xcdevice over {}.",
                    udid,
                    entry.modelName ?: "unknown model",
                    entry.osVersionNumber ?: "unknown version",
                    entry.connectionInterface ?: "unknown interface",
                )

                val description = listOfNotNull(
                    entry.name,
                    entry.osVersionNumber,
                    udid,
                ).joinToString(" - ")

                Device.Connected(
                    instanceId = udid,
                    description = description,
                    platform = Platform.IOS,
                    deviceType = Device.DeviceType.REAL,
                    deviceSpec = DeviceSpec.Ios.DEFAULT
                )
            }
    }

    internal fun shouldQueryXcDevice(deviceCtlEntries: List<DeviceCtlResponse.Device>): Boolean {
        if (System.getenv(MAESTRO_DISABLE_LEGACY_IOS_DISCOVERY)?.toBoolean() == true) return false
        // Escape hatch for hosts where devicectl omits the unsupported device altogether rather
        // than reporting a stub for it.
        if (System.getenv(MAESTRO_FORCE_LEGACY_IOS_DISCOVERY)?.toBoolean() == true) return true

        return deviceCtlEntries.any { it.hardwareProperties?.udid.isNullOrBlank() }
    }

    private const val MAESTRO_DISABLE_LEGACY_IOS_DISCOVERY = "MAESTRO_DISABLE_LEGACY_IOS_DISCOVERY"
    private const val MAESTRO_FORCE_LEGACY_IOS_DISCOVERY = "MAESTRO_FORCE_LEGACY_IOS_DISCOVERY"

    private fun device(
      runtimeNameByIdentifier: Map<String, String>,
      runtime: Map.Entry<String, List<SimctlList.Device>>,
      device: SimctlList.Device,
    ): Device {
        val runtimeName = runtimeNameByIdentifier[runtime.key] ?: "Unknown runtime"
        val description = "${device.name} - $runtimeName - ${device.udid}"

        // "com.apple.CoreSimulator.SimDeviceType.iPhone-XS" → "iPhone-XS"
        val model = device.deviceTypeIdentifier?.substringAfterLast(".") ?: ""
        // "com.apple.CoreSimulator.SimRuntime.iOS-17-5" → "iOS-17-5"
        val os = runtime.key.substringAfterLast(".")

        return if (device.state == "Booted") {
            Device.Connected(
                instanceId = device.udid,
                description = description,
                platform = Platform.IOS,
                deviceType = Device.DeviceType.SIMULATOR,
                deviceSpec = if (model.isBlank() || os.isBlank()) {
                    DeviceSpec.Ios.DEFAULT
                } else {
                    DeviceSpec.Ios(model = model, os = os)
                }
            )
        } else {
            Device.AvailableForLaunch(
                modelId = device.udid,
                description = description,
                platform = Platform.IOS,
                deviceType =  Device.DeviceType.SIMULATOR,
                deviceSpec = if (model.isBlank() || os.isBlank()) {
                    DeviceSpec.Ios.DEFAULT
                } else {
                    DeviceSpec.Ios(model = model, os = os)
                }
            )
        }
    }

    /**
     * @return true if ios simulator or android emulator is currently connected
     */
    fun isDeviceConnected(deviceName: String, platform: Platform): Device.Connected? {
        return when (platform) {
            Platform.IOS -> listIOSDevices()
                .filterIsInstance<Device.Connected>()
                .find { it.description.contains(deviceName, ignoreCase = true) }

            else -> runCatching {
                (Dadb.list() + AdbServer.listDadbs(adbServerPort = 5038))
                    .mapNotNull { dadb -> runCatching { dadb.shell("getprop ro.kernel.qemu.avd_name").output }.getOrNull() }
                    .map { output ->
                        Device.Connected(
                            instanceId = output,
                            description = output,
                            platform = Platform.ANDROID,
                            deviceType = Device.DeviceType.EMULATOR,
                            deviceSpec = DeviceSpec.Android.DEFAULT
                        )
                    }
                    .find { connectedDevice -> connectedDevice.description.contains(deviceName, ignoreCase = true) }
            }.getOrNull()
        }
    }

    /**
     * @return true if ios simulator or android emulator is available to launch
     */
    fun isDeviceAvailableToLaunch(deviceName: String, platform: Platform): Device.AvailableForLaunch? {
        return if (platform == Platform.IOS) {
            listIOSDevices()
                .filterIsInstance<Device.AvailableForLaunch>()
                .find { it.description.contains(deviceName, ignoreCase = true) }
        } else {
            listAndroidDevices()
                .filterIsInstance<Device.AvailableForLaunch>()
                .find { it.description.contains(deviceName, ignoreCase = true) }
        }
    }

    /**
     * Creates an iOS simulator
     *
     * @param deviceName Any name
     * @param device Simulator type as specified by Apple i.e. iPhone-11
     * @param os OS runtime name as specified by Apple i.e. iOS-16-2
     */
    fun createIosDevice(deviceName: String, device: String, os: String): UUID {
        val command = listOf(
            "xcrun",
            "simctl",
            "create",
            deviceName,
            "com.apple.CoreSimulator.SimDeviceType.$device",
            "com.apple.CoreSimulator.SimRuntime.$os"
        )

        val process = ProcessBuilder(*command.toTypedArray()).start()
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            throw TimeoutException()
        }

        if (process.exitValue() != 0) {
            val processOutput = process.errorStream
                .source()
                .buffer()
                .readUtf8()

            throw IllegalStateException(processOutput)
        } else {
            val output = String(process.inputStream.readBytes()).trim()
            return try {
                UUID.fromString(output)
            } catch (ignore: IllegalArgumentException) {
                throw IllegalStateException("Unable to create device. No UUID was generated")
            }
        }
    }

    /**
     * Creates an Android emulator
     *
     * @param deviceName Any device name
     * @param device Device type as specified by the Android SDK i.e. "pixel_6"
     * @param systemImage Full system package i.e "system-images;android-28;google_apis;x86_64"
     * @param tag google apis or playstore tag i.e. google_apis or google_apis_playstore
     * @param abi x86_64, x86, arm64 etc..
     */
    fun createAndroidDevice(
        deviceName: String,
        device: String,
        systemImage: String,
        tag: String,
        abi: String,
        force: Boolean = false,
    ): String {
        val avd = requireAvdManagerBinary()
        val name = deviceName
        val command = mutableListOf(
            avd.absolutePath,
            "create", "avd",
            "--name", name,
            "--package", systemImage,
            "--tag", tag,
            "--abi", abi,
            "--device", device,
        )

        if (force) command.add("--force")

        val process = ProcessBuilder(*command.toTypedArray()).start()

        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            throw TimeoutException()
        }

        if (process.exitValue() != 0) {
            val processOutput = process.errorStream
                .source()
                .buffer()
                .readUtf8()

            throw IllegalStateException("Failed to start android emulator: $processOutput")
        }

        return name
    }

    fun getAvailablePixelDevices(): List<AvdDevice> {
        val avd = requireAvdManagerBinary()
        val command = mutableListOf(
            avd.absolutePath,
            "list", "device"
        )

        val process = ProcessBuilder(*command.toTypedArray()).start()

        if (!process.waitFor(1, TimeUnit.MINUTES)) {
            throw TimeoutException()
        }

        if (process.exitValue() != 0) {
            val processOutput = process.inputStream.source().buffer().readUtf8() + "\n" + process.errorStream.source().buffer().readUtf8()

            throw IllegalStateException("Failed to list avd devices emulator: $processOutput")
        }

        return runCatching {
            AndroidEnvUtils.parsePixelDevices(String(process.inputStream.readBytes()).trim())
        }.getOrNull() ?: emptyList()
    }

    /**
     * @return true is Android system image is already installed
     */
    fun isAndroidSystemImageInstalled(image: String): Boolean {
        val command = listOf(
            requireSdkManagerBinary().absolutePath,
            "--list_installed"
        )
        try {
            val process = ProcessBuilder(*command.toTypedArray()).start()
            if (!process.waitFor(1, TimeUnit.MINUTES)) {
                throw TimeoutException()
            }

            if (process.exitValue() == 0) {
                val output = String(process.inputStream.readBytes()).trim()

                return output.contains(image)
            }
        } catch (e: Exception) {
            logger.error("Unable to detect if SDK package is installed", e)
        }

        return false
    }

    /**
     * Uses the Android SDK manager to install android image
     */
    fun installAndroidSystemImage(image: String): Boolean {
        val command = listOf(
            requireSdkManagerBinary().absolutePath,
            image
        )
        try {
            val process = ProcessBuilder(*command.toTypedArray())
                .inheritIO()
                .start()
            if (!process.waitFor(120, TimeUnit.MINUTES)) {
                throw TimeoutException()
            }

            return process.exitValue() == 0 && isAndroidSystemImageInstalled(image)
        } catch (e: Exception) {
            logger.error("Unable to install if SDK package is installed", e)
        }

        return false
    }

    fun getAndroidSystemImageInstallCommand(pkg: String): String {
        return listOf(
            requireSdkManagerBinary().absolutePath,
            "\"$pkg\""
        ).joinToString(separator = " ")
    }

    fun deleteIosDevice(uuid: String): Boolean {
        val command = listOf(
            "xcrun",
            "simctl",
            "delete",
            uuid
        )

        val process = ProcessBuilder(*command.toTypedArray()).start()

        if (!process.waitFor(1, TimeUnit.MINUTES)) {
            throw TimeoutException()
        }

        return process.exitValue() == 0
    }

    fun killAndroidDevice(deviceId: String): Boolean {
        val command = listOf("adb", "-s", deviceId, "emu", "kill")

        try {
            val process = ProcessBuilder(*command.toTypedArray()).start()

            if (!process.waitFor(1, TimeUnit.MINUTES)) {
                throw TimeoutException("Android kill command timed out")
            }

            val success = process.exitValue() == 0
            if (success) {
                logger.info("Killed Android device: $deviceId")
            } else {
                logger.error("Failed to kill Android device: $deviceId")
            }

            return success
        } catch (e: Exception) {
            logger.error("Error killing Android device: $deviceId", e)
            return false
        }
    }

    fun killIOSDevice(deviceId: String): Boolean {
        val command = listOf("xcrun", "simctl", "shutdown", deviceId)

        try {
            val process = ProcessBuilder(*command.toTypedArray()).start()

            if (!process.waitFor(1, TimeUnit.MINUTES)) {
                throw TimeoutException("iOS kill command timed out")
            }

            val success = process.exitValue() == 0
            if (success) {
                logger.info("Killed iOS device: $deviceId")
            } else {
                logger.error("Failed to kill iOS device: $deviceId")
            }

            return success
        } catch (e: Exception) {
            logger.error("Error killing iOS device: $deviceId", e)
            return false
        }
    }

    private fun bootComplete(dadb: Dadb): Boolean {
        return try {
            val booted = dadb.shell("getprop sys.boot_completed").output.trim() == "1"
            val settingsAvailable = dadb.shell("settings list global").exitCode == 0
            val packageManagerAvailable = dadb.shell("pm get-max-users").exitCode == 0
            return settingsAvailable && packageManagerAvailable && booted
        } catch (e: IllegalStateException) {
            false
        }
    }

    private fun requireEmulatorBinary(): File = AndroidEnvUtils.requireEmulatorBinary()

    private fun requireAvdManagerBinary(): File = AndroidEnvUtils.requireCommandLineTools("avdmanager")

    private fun requireSdkManagerBinary(): File = AndroidEnvUtils.requireCommandLineTools("sdkmanager")

    private const val SET_LOCALE_RESULT_SUCCESS = 0
    private const val SET_LOCALE_RESULT_LOCALE_NOT_VALID = 1
    private const val SET_LOCALE_RESULT_UPDATE_CONFIGURATION_FAILED = 2
}
