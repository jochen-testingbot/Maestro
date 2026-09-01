/*
 *
 *  Copyright (c) 2022 mobile.dev inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package maestro.cli.session

import dadb.Dadb
import dadb.adbserver.AdbServer
import ios.LocalIOSDevice
import ios.devicectl.DeviceControlIOSDevice
import device.SimctlIOSDevice
import ios.xctest.XCTestIOSDevice
import maestro.Maestro
import maestro.device.Device
import maestro.cli.CliError
import maestro.cli.device.PickDeviceInteractor
import maestro.cli.driver.DriverBuilder
import maestro.cli.driver.RealIOSDeviceDriver
import maestro.cli.util.PrintUtils
import maestro.device.Platform
import maestro.utils.CliInsights
import maestro.cli.report.TestDebugReporter
import maestro.cli.util.ScreenReporter
import maestro.drivers.AndroidDriver
import maestro.drivers.IOSDriver
import maestro.orchestra.WorkspaceConfig.PlatformConfiguration
import maestro.orchestra.workspace.WorkspaceExecutionPlanner
import maestro.utils.HttpClient
import maestro.utils.TempFileHandler
import org.slf4j.LoggerFactory
import util.DeviceCtlResponse
import util.IOSDeviceType
import util.XCRunnerCLIUtils
import xcuitest.XCTestClient
import xcuitest.XCTestDriverClient
import xcuitest.installer.Context
import xcuitest.installer.LocalXCTestInstaller
import xcuitest.installer.LocalXCTestInstaller.*
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.pathString
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

object MaestroSessionManager {
    private const val defaultHost = "localhost"
    private const val defaultXctestHost = "127.0.0.1"
    private const val defaultXcTestPort = 22087
    private const val MAESTRO_XCTEST_HOST = "MAESTRO_XCTEST_HOST"
    private const val MAESTRO_SKIP_IOS_TUNNEL_CHECK = "MAESTRO_SKIP_IOS_TUNNEL_CHECK"

    private val executor = Executors.newScheduledThreadPool(1)
    private val logger = LoggerFactory.getLogger(MaestroSessionManager::class.java)


    fun <T> newSession(
        host: String?,
        port: Int?,
        driverHostPort: Int?,
        deviceId: String?,
        teamId: String? = null,
        platform: String? = null,
        isStudio: Boolean = false,
        isHeadless: Boolean = false,
        screenSize: String? = null,
        reinstallDriver: Boolean = true,
        deviceIndex: Int? = null,
        executionPlan: WorkspaceExecutionPlanner.ExecutionPlan? = null,
        block: (MaestroSession) -> T,
    ): T {
        val selectedDevice = selectDevice(
            host = host,
            port = port,
            driverHostPort = driverHostPort,
            deviceId = deviceId,
            teamId = teamId,
            platform = if(!platform.isNullOrEmpty()) Platform.fromString(platform) else null,
            deviceIndex = deviceIndex,
        )
        val sessionId = UUID.randomUUID().toString()
        val effectiveDeviceId = selectedDevice.device?.instanceId
            ?: selectedDevice.deviceId
            ?: sessionId // fallback: use session UUID as unique device key when no device ID is available

        val heartbeatFuture = executor.scheduleAtFixedRate(
            {
                try {
                    SessionStore.default.heartbeat(sessionId, selectedDevice.platform, effectiveDeviceId)
                } catch (e: Exception) {
                    logger.error("Failed to record heartbeat", e)
                }
            },
            0L,
            5L,
            TimeUnit.SECONDS
        )

        val session = createMaestro(
            selectedDevice = selectedDevice,
            connectToExistingSession = if (isStudio) {
                false
            } else if (driverHostPort != null) {
                // Custom port specified → skip session store check for all devices
                false
            } else {
                SessionStore.default.hasActiveSessionForDevice(
                    sessionId,
                    selectedDevice.platform,
                    effectiveDeviceId
                )
            },
            isStudio = isStudio,
            isHeadless = isHeadless,
            screenSize = screenSize,
            driverHostPort = driverHostPort,
            reinstallDriver = reinstallDriver,
            platformConfiguration = executionPlan?.workspaceConfig?.platform
        )
        Runtime.getRuntime().addShutdownHook(thread(start = false) {
            heartbeatFuture.cancel(true)
            SessionStore.default.delete(sessionId, selectedDevice.platform, effectiveDeviceId)
            runCatching { ScreenReporter.reportMaxDepth() }
            if (SessionStore.default.shouldCloseSession(selectedDevice.platform, effectiveDeviceId)) {
                session.close()
            }
        })

        return block(session)
    }

    private fun selectDevice(
        host: String?,
        port: Int?,
        driverHostPort: Int?,
        deviceId: String?,
        platform: Platform? = null,
        teamId: String? = null,
        deviceIndex: Int? = null,
    ): SelectedDevice {

        if (deviceId == "chromium" || platform == Platform.WEB) {
            return SelectedDevice(
                platform = Platform.WEB,
                deviceType = Device.DeviceType.BROWSER
            )
        }

        if (host == null) {
            val device = PickDeviceInteractor.pickDevice(deviceId, driverHostPort, platform, deviceIndex)

            // Only validate/build driver when NOT using custom driver port
            if (device.deviceType == Device.DeviceType.REAL && device.platform == Platform.IOS && driverHostPort == null) {
                PrintUtils.message("Detected connected iPhone with ${device.instanceId}!")
                val driverBuilder = DriverBuilder()
                RealIOSDeviceDriver(
                    destination = "platform=iOS,id=${device.instanceId}",
                    teamId = teamId,
                    driverBuilder = driverBuilder
                ).validateAndUpdateDriver()
            }
            return SelectedDevice(
                platform = device.platform,
                device = device,
                deviceType = device.deviceType
            )
        }

        if (isAndroid(host, port)) {
            val deviceType = when {
                deviceId?.startsWith("emulator") == true -> Device.DeviceType.EMULATOR
                else -> Device.DeviceType.REAL
            }
            return SelectedDevice(
                platform = Platform.ANDROID,
                host = host,
                port = port,
                deviceId = deviceId,
                deviceType = deviceType
            )
        }

        return SelectedDevice(
            platform = Platform.IOS,
            host = null,
            port = null,
            deviceId = deviceId,
            deviceType = Device.DeviceType.SIMULATOR
        )
    }

    private fun createMaestro(
        selectedDevice: SelectedDevice,
        connectToExistingSession: Boolean,
        isStudio: Boolean,
        isHeadless: Boolean,
        screenSize: String?,
        reinstallDriver: Boolean,
        driverHostPort: Int?,
        platformConfiguration: PlatformConfiguration? = null,
    ): MaestroSession {
        return when {
            selectedDevice.device != null -> MaestroSession(
                maestro = when (selectedDevice.device.platform) {
                    Platform.ANDROID -> createAndroid(
                        selectedDevice.device.instanceId,
                        !connectToExistingSession,
                        driverHostPort,
                        reinstallDriver,
                    )

                    Platform.IOS -> createIOS(
                        selectedDevice.device.instanceId,
                        !connectToExistingSession,
                        driverHostPort,
                        reinstallDriver,
                        deviceType = selectedDevice.device.deviceType,
                        platformConfiguration = platformConfiguration
                    )

                    Platform.WEB -> pickWebDevice(isStudio, isHeadless, screenSize)
                },
                device = selectedDevice.device,
            )

            selectedDevice.platform == Platform.ANDROID -> MaestroSession(
                maestro = pickAndroidDevice(
                    selectedDevice.host,
                    selectedDevice.port,
                    driverHostPort,
                    !connectToExistingSession,
                    reinstallDriver,
                    selectedDevice.deviceId,
                ),
                device = null,
            )

            selectedDevice.platform == Platform.IOS -> MaestroSession(
                maestro = pickIOSDevice(
                    deviceId = selectedDevice.deviceId,
                    openDriver = !connectToExistingSession,
                    driverHostPort = driverHostPort ?: defaultXcTestPort,
                    reinstallDriver = reinstallDriver,
                    platformConfiguration = platformConfiguration,
                ),
                device = null,
            )

            selectedDevice.platform == Platform.WEB -> MaestroSession(
                maestro = pickWebDevice(isStudio, isHeadless, screenSize),
                device = null
            )

            else -> error("Unable to create Maestro session")
        }
    }

    private fun isAndroid(host: String?, port: Int?): Boolean {
        return try {
            val dadb = if (port != null) {
                Dadb.create(host ?: defaultHost, port)
            } else {
                Dadb.discover(host ?: defaultHost)
                    ?: createAdbServerDadb()
                    ?: error("No android devices found.")
            }

            dadb.close()

            true
        } catch (_: Exception) {
            false
        }
    }

    private fun pickAndroidDevice(
        host: String?,
        port: Int?,
        driverHostPort: Int?,
        openDriver: Boolean,
        reinstallDriver: Boolean,
        deviceId: String? = null,
    ): Maestro {
        val dadb = if (port != null) {
            Dadb.create(host ?: defaultHost, port)
        } else if (deviceId != null) {
            Dadb.list(host = host ?: defaultHost).find { it.toString() == deviceId }
                ?: error("No Android device found with id '$deviceId' on host '${host ?: defaultHost}'")
        } else {
            Dadb.discover(host ?: defaultHost)
                ?: createAdbServerDadb()
                ?: error("No android devices found.")
        }

        return Maestro.android(
            driver = AndroidDriver(dadb, driverHostPort, "", reinstallDriver),
            openDriver = openDriver,
        )
    }

    private fun createAdbServerDadb(): Dadb? {
        return try {
            AdbServer.createDadb(adbServerPort = 5038)
        } catch (ignored: Exception) {
            null
        }
    }

    private fun pickIOSDevice(
        deviceId: String?,
        openDriver: Boolean,
        driverHostPort: Int,
        reinstallDriver: Boolean,
        platformConfiguration: PlatformConfiguration?,
    ): Maestro {
        val device = PickDeviceInteractor.pickDevice(deviceId, driverHostPort)
        return createIOS(
            device.instanceId,
            openDriver,
            driverHostPort,
            reinstallDriver,
            deviceType = device.deviceType,
            platformConfiguration = platformConfiguration
        )
    }

    private fun createAndroid(
        instanceId: String,
        openDriver: Boolean,
        driverHostPort: Int?,
        reinstallDriver: Boolean,
    ): Maestro {
        val driver = AndroidDriver(
            dadb = Dadb
                .list()
                .find { it.toString() == instanceId }
                ?: Dadb.discover()
                ?: error("Unable to find device with id $instanceId"),
            hostPort = driverHostPort,
            emulatorName = instanceId,
            reinstallDriver = reinstallDriver,
        )

        return Maestro.android(
            driver = driver,
            openDriver = openDriver,
        )
    }

    private fun createIOS(
        deviceId: String,
        openDriver: Boolean,
        driverHostPort: Int?,
        reinstallDriver: Boolean,
        platformConfiguration: PlatformConfiguration?,
        deviceType: Device.DeviceType,
    ): Maestro {

        val iOSDeviceType = when (deviceType) {
            Device.DeviceType.REAL -> IOSDeviceType.REAL
            Device.DeviceType.SIMULATOR -> IOSDeviceType.SIMULATOR
            else -> {
                throw UnsupportedOperationException("Unsupported device type $deviceType for iOS platform")
            }
        }
        val iOSDriverConfig = when (deviceType) {
            Device.DeviceType.REAL -> {
                val maestroDirectory = Paths.get(System.getProperty("user.home"), ".maestro")
                val driverPath = maestroDirectory.resolve("maestro-iphoneos-driver-build").resolve("driver-iphoneos")
                    .resolve("Build").resolve("Products")
                IOSDriverConfig(
                    prebuiltRunner = false,
                    sourceDirectory = driverPath.pathString,
                    context = Context.CLI,
                    snapshotKeyHonorModalViews = platformConfiguration?.ios?.snapshotKeyHonorModalViews
                )
            }
            Device.DeviceType.SIMULATOR -> {
                IOSDriverConfig(
                    prebuiltRunner = false,
                    sourceDirectory =  "driver-iPhoneSimulator",
                    context = Context.CLI,
                    snapshotKeyHonorModalViews = platformConfiguration?.ios?.snapshotKeyHonorModalViews
                )
            }
             else -> throw UnsupportedOperationException("Unsupported device type $deviceType for iOS platform")
        }

        val tempFileHandler = TempFileHandler()
        var xctestHosts = listOf(defaultXctestHost)
        val deviceController = when (deviceType) {
            Device.DeviceType.REAL -> {
                val device = util.LocalIOSDevice().listDeviceViaDeviceCtl(deviceId)
                requireReachableRealDevice(deviceId, device.connectionProperties)
                xctestHosts = resolveRealDeviceXctestHosts(deviceId, device.connectionProperties)
                val deviceCtlDevice = DeviceControlIOSDevice(deviceId = device.identifier)
                deviceCtlDevice
            }
            Device.DeviceType.SIMULATOR -> {
                val simctlIOSDevice = SimctlIOSDevice(
                    deviceId = deviceId,
                    tempFileHandler = tempFileHandler
                )
                simctlIOSDevice
            }
            else -> throw UnsupportedOperationException("Unsupported device type $deviceType for iOS platform")
        }

        val xcTestInstaller = LocalXCTestInstaller(
            deviceId = deviceId,
            host = xctestHosts.first(),
            fallbackHosts = xctestHosts.drop(1),
            defaultPort = driverHostPort ?: defaultXcTestPort,
            reinstallDriver = reinstallDriver,
            deviceType = iOSDeviceType,
            iOSDriverConfig = iOSDriverConfig,
            deviceController = deviceController,
            tempFileHandler = tempFileHandler,
            logsDir = TestDebugReporter.getDebugOutputPath().toFile(),
        )

        val xcTestDriverClient = XCTestDriverClient(
            installer = xcTestInstaller,
            client = XCTestClient(xctestHosts.first(), driverHostPort ?: defaultXcTestPort),
            okHttpClient = HttpClient.build(
                name = "XCTestDriverClient",
                // A network-attached device answers over a CoreDevice tunnel rather than a
                // usbmux forward; 1s to establish a connection is not enough there.
                connectTimeout = xctestConnectTimeout(deviceType),
                readTimeout = 200.seconds,
                callTimeout = 200.seconds,
            ),
            reinstallDriver = reinstallDriver,
        )

        val xcRunnerCLIUtils = XCRunnerCLIUtils(tempFileHandler = tempFileHandler)
        val xcTestDevice = XCTestIOSDevice(
            deviceId = deviceId,
            client = xcTestDriverClient,
            getInstalledApps = { xcRunnerCLIUtils.listApps(deviceId) },
        )

        val iosDriver = IOSDriver(
            LocalIOSDevice(
                deviceId = deviceId,
                xcTestDevice = xcTestDevice,
                deviceController = deviceController,
                insights = CliInsights
            ),
            insights = CliInsights
        )

        return Maestro.ios(
            driver = iosDriver,
            // Only check isShutdown() if not using custom driver port (avoids accessing driver files)
            openDriver = if (driverHostPort != null) openDriver else (openDriver || xcTestDevice.isShutdown()),
        )
    }

    private fun xctestConnectTimeout(deviceType: Device.DeviceType): Duration = when (deviceType) {
        Device.DeviceType.REAL -> 10.seconds
        else -> 1.seconds
    }

    /**
     * A device that dropped off USB and is only paired over Wi-Fi is still reported by
     * `devicectl list devices`, but every devicectl/xcodebuild call against it fails until
     * CoreDevice has a tunnel to it. Fail here, with an actionable message, instead of 2-4
     * minutes later behind a generic driver-startup timeout.
     *
     * Only enforced when devicectl actually reports a tunnel state. Older Xcode/devicectl
     * releases (and pre-CoreDevice devices, i.e. iOS 16 and earlier) omit the field, and those
     * setups drive the device through xcodebuild's lockdown path with no tunnel at all — so an
     * absent value must not block the run.
     */
    private fun requireReachableRealDevice(
        deviceId: String,
        connectionProperties: DeviceCtlResponse.ConnectionProperties,
    ) {
        if (connectionProperties.isTunnelConnected) return

        val message = "iOS device $deviceId is paired but not currently reachable: devicectl reports " +
            "tunnelState='${connectionProperties.tunnelState ?: "unknown"}' " +
            "(transport: ${connectionProperties.transportType ?: "unknown"}). " +
            "Connect the device over USB, or — for a network-attached device — make sure it is " +
            "unlocked, on the same network as this host, and trusted, then confirm it shows as " +
            "'connected' in `xcrun devicectl list devices`."

        val skipCheck = System.getenv(MAESTRO_SKIP_IOS_TUNNEL_CHECK)?.toBoolean() == true
        if (connectionProperties.tunnelState == null || skipCheck) {
            logger.warn("$message (continuing anyway)")
            return
        }

        throw CliError("$message\nSet $MAESTRO_SKIP_IOS_TUNNEL_CHECK=true to run anyway.")
    }

    /**
     * Addresses to try, in order, when talking to the on-device XCTest runner.
     *
     * The runner binds 0.0.0.0, so it is reachable either through a host port forward
     * (iproxy/usbmux, on loopback) or directly on the device's CoreDevice tunnel address.
     * Which one is live depends on how the device is attached, so order by what devicectl
     * reports and keep the other as a fallback: a cabled device whose forward is missing, or
     * a network device whose tunnel address is stale, still recovers instead of timing out.
     * [MAESTRO_XCTEST_HOST] pins a single address and disables the fallback.
     */
    private fun resolveRealDeviceXctestHosts(
        deviceId: String,
        connectionProperties: DeviceCtlResponse.ConnectionProperties,
    ): List<String> {
        System.getenv(MAESTRO_XCTEST_HOST)?.takeIf { it.isNotBlank() }?.let { override ->
            logger.info("Using XCTest runner host $override for $deviceId ($MAESTRO_XCTEST_HOST)")
            return listOf(override)
        }

        val tunnelAddress = connectionProperties.tunnelIPAddress?.takeIf { it.isNotBlank() }
            ?: return listOf(defaultXctestHost)

        return if (connectionProperties.isNetworkAttached) {
            PrintUtils.message(
                "Device $deviceId is attached over the network; will reach the XCTest runner on its " +
                    "tunnel address $tunnelAddress, falling back to $defaultXctestHost (a port forward). " +
                    "Set $MAESTRO_XCTEST_HOST to pin one address."
            )
            listOf(tunnelAddress, defaultXctestHost)
        } else {
            listOf(defaultXctestHost, tunnelAddress)
        }
    }

    private fun pickWebDevice(isStudio: Boolean, isHeadless: Boolean, screenSize: String?): Maestro {
        return Maestro.web(isStudio, isHeadless, screenSize)
    }

    private data class SelectedDevice(
        val platform: Platform,
        val device: Device.Connected? = null,
        val host: String? = null,
        val port: Int? = null,
        val deviceId: String? = null,
        val deviceType: Device.DeviceType,
    )

    data class MaestroSession(
        val maestro: Maestro,
        val device: Device? = null,
    ) {

        fun close() {
            maestro.close()
        }
    }
}
