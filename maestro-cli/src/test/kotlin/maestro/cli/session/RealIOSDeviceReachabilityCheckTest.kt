package maestro.cli.session

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import maestro.cli.CliError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import util.DeviceCtlResponse
import util.DeviceCtlResponse.ConnectionProperties
import util.LocalIOSDevice
import util.RealIOSDeviceResolver
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RealIOSDeviceReachabilityCheckTest {

    private val udid = "00008120-000202A12632201E"

    private val resolver = mockk<RealIOSDeviceResolver>()
    private val localIOSDevice = mockk<LocalIOSDevice>(relaxUnitFun = true)
    private val sleeps = mutableListOf<Duration>()
    private var skipRequested = false

    private val idleWired = properties(tunnelState = "disconnected", transportType = "wired")
    private val idleNetwork = properties(tunnelState = "disconnected", transportType = "localNetwork")

    @Test
    fun `a connected device passes without being woken or re-read`() {
        val connected = properties(tunnelState = "connected", transportType = "wired", tunnelIPAddress = "fd12::1")

        val result = check().requireReachable(udid, connected)

        assertThat(result).isEqualTo(connected)
        verify(exactly = 0) { localIOSDevice.wakeTunnel(any(), any()) }
        verify(exactly = 0) { resolver.findByUdid(any()) }
    }

    @Test
    fun `an idle device is woken and passes once its tunnel comes up`() {
        val up = properties(tunnelState = "connected", transportType = "wired", tunnelIPAddress = "fd12::1")
        every { resolver.findByUdid(udid) } returnsMany listOf(device(idleWired), device(up))

        val result = check().requireReachable(udid, idleWired)

        // The fresh reading is what callers must go on with: the new tunnel has a new address.
        assertThat(result).isEqualTo(up)
        verify(exactly = 1) { localIOSDevice.wakeTunnel(udid, any()) }
        verify(exactly = 2) { resolver.findByUdid(udid) }
        assertThat(sleeps).containsExactly(1.seconds)
    }

    @Test
    fun `the wake is bounded by the configured timeout`() {
        every { resolver.findByUdid(udid) } returns device(properties(tunnelState = "connected", transportType = "wired"))

        check(wakeTimeout = 7.seconds).requireReachable(udid, idleWired)

        verify(exactly = 1) { localIOSDevice.wakeTunnel(udid, 7L) }
    }

    @Test
    fun `a wired device whose tunnel stays down passes with a warning`() {
        every { resolver.findByUdid(udid) } returns device(idleWired)

        val result = check(settleTimeout = 3.seconds).requireReachable(udid, idleWired)

        assertThat(result).isEqualTo(idleWired)
        verify(exactly = 1) { localIOSDevice.wakeTunnel(udid, any()) }
        // First read is immediate, the remaining two wait an interval each.
        verify(exactly = 3) { resolver.findByUdid(udid) }
        assertThat(sleeps).containsExactly(1.seconds, 1.seconds)
    }

    @Test
    fun `a network-only device whose tunnel stays down still fails the run`() {
        every { resolver.findByUdid(udid) } returns device(idleNetwork)

        val error = assertThrows<CliError> { check(settleTimeout = 3.seconds).requireReachable(udid, idleNetwork) }

        assertThat(error).hasMessageThat().contains(udid)
        assertThat(error).hasMessageThat().contains("tunnelState='disconnected'")
        assertThat(error).hasMessageThat().contains("localNetwork")
        assertThat(error).hasMessageThat().contains(RealIOSDeviceReachabilityCheck.MAESTRO_SKIP_IOS_TUNNEL_CHECK)
        verify(exactly = 1) { localIOSDevice.wakeTunnel(udid, any()) }
        verify(exactly = 3) { resolver.findByUdid(udid) }
    }

    @Test
    fun `a device with no tunnel state is left to the legacy path without being woken`() {
        val legacy = ConnectionProperties()

        val result = check().requireReachable(udid, legacy)

        assertThat(result).isEqualTo(legacy)
        verify(exactly = 0) { localIOSDevice.wakeTunnel(any(), any()) }
        verify(exactly = 0) { resolver.findByUdid(any()) }
    }

    @Test
    fun `the escape hatch skips the check without waking the device`() {
        skipRequested = true

        val result = check().requireReachable(udid, idleNetwork)

        assertThat(result).isEqualTo(idleNetwork)
        verify(exactly = 0) { localIOSDevice.wakeTunnel(any(), any()) }
        verify(exactly = 0) { resolver.findByUdid(any()) }
    }

    @Test
    fun `a failing wake call does not abort the check`() {
        every { localIOSDevice.wakeTunnel(udid, any()) } throws IllegalStateException("xcrun: devicectl not found")
        val up = properties(tunnelState = "connected", transportType = "wired")
        every { resolver.findByUdid(udid) } returns device(up)

        val result = check().requireReachable(udid, idleWired)

        assertThat(result).isEqualTo(up)
    }

    @Test
    fun `a device that vanishes while polling is reported with its last known state`() {
        every { resolver.findByUdid(udid) } returns null

        val error = assertThrows<CliError> { check(settleTimeout = 2.seconds).requireReachable(udid, idleNetwork) }

        // Without the transport type the wired/network decision could not be made; the initial
        // reading is kept so the message and the outcome stay meaningful.
        assertThat(error).hasMessageThat().contains("localNetwork")
        verify(exactly = 2) { resolver.findByUdid(udid) }
    }

    private fun check(
        wakeTimeout: Duration = 15.seconds,
        settleTimeout: Duration = 15.seconds,
    ) = RealIOSDeviceReachabilityCheck(
        resolver = resolver,
        localIOSDevice = localIOSDevice,
        skipRequested = { skipRequested },
        sleep = { sleeps += it },
        wakeTimeout = wakeTimeout,
        settleTimeout = settleTimeout,
        pollInterval = 1.seconds,
    )

    private fun properties(
        tunnelState: String?,
        transportType: String?,
        tunnelIPAddress: String? = null,
    ) = ConnectionProperties(
        tunnelState = tunnelState,
        transportType = transportType,
        tunnelIPAddress = tunnelIPAddress,
    )

    private fun device(connectionProperties: ConnectionProperties) = DeviceCtlResponse.Device(
        identifier = "6986451F-A2FF-48DE-A70E-45E06E1F1446",
        deviceProperties = DeviceCtlResponse.DeviceProperties(
            name = "iPhone",
            osVersionNumber = "18.4.1",
            developerModeStatus = "enabled",
        ),
        hardwareProperties = DeviceCtlResponse.HardwareProperties(udid = udid),
        connectionProperties = connectionProperties,
    )
}
