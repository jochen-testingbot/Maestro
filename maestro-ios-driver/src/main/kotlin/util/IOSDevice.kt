package util

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

enum class IOSDeviceType {
    REAL,
    SIMULATOR
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class DeviceCtlResponse(
    val result: Result
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Result(
        val devices: List<Device>
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Device(
        val identifier: String,
        val deviceProperties: DeviceProperties?,
        val hardwareProperties: HardwareProperties?,
        val connectionProperties: ConnectionProperties = ConnectionProperties(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ConnectionProperties(
        val tunnelState: String? = null,
        val transportType: String? = null,
        val tunnelIPAddress: String? = null,
        /** mDNS names the device answers to, e.g. `iPhone.coredevice.local`. */
        val localHostnames: List<String> = emptyList(),
    ) {
        val isTunnelConnected: Boolean get() = tunnelState == CONNECTED

        /** True when the device is only reachable over the network (no USB cable). */
        val isNetworkAttached: Boolean get() = transportType == TRANSPORT_LOCAL_NETWORK

        companion object {
            const val CONNECTED  = "connected"
            const val TRANSPORT_LOCAL_NETWORK = "localNetwork"
            const val TRANSPORT_WIRED = "wired"
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DeviceProperties(
        val name: String?,
        val osVersionNumber: String?,
        val developerModeStatus: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HardwareProperties(
        val udid: String?
    )
}
