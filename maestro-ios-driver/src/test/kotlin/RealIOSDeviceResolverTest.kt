import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import util.DeviceCtlResponse
import util.LocalIOSDevice
import util.RealIOSDeviceResolver
import util.XcDeviceEntry
import util.XcDeviceLister

class RealIOSDeviceResolverTest {

    private val udid = "da4fbbdf3ba419a78e312d8ae3e4b338f50f0584"

    @Test
    fun `prefers devicectl and does not consult xcdevice when it answers`() {
        val localIOSDevice = mockk<LocalIOSDevice>()
        val xcDeviceLister = mockk<XcDeviceLister>()
        val fromDeviceCtl = deviceCtlDevice(tunnelState = "connected")
        every { localIOSDevice.listDeviceViaDeviceCtl(udid) } returns fromDeviceCtl

        val resolved = RealIOSDeviceResolver(localIOSDevice, xcDeviceLister).findByUdid(udid)

        assertThat(resolved).isEqualTo(fromDeviceCtl)
        // xcdevice blocks on every attached device, so it must not be touched on the common path
        verify(exactly = 0) { xcDeviceLister.listPhysicalIOSDevices(any()) }
    }

    @Test
    fun `falls back to xcdevice when devicectl cannot resolve the device`() {
        val resolver = RealIOSDeviceResolver(
            localIOSDevice = deviceCtlThatCannotResolve(),
            xcDeviceLister = xcDeviceListerReturning(xcDeviceEntry()),
        )

        val resolved = resolver.findByUdid(udid)

        assertThat(resolved).isNotNull()
        assertThat(resolved!!.identifier).isEqualTo(udid)
        assertThat(resolved.hardwareProperties?.udid).isEqualTo(udid)
        assertThat(resolved.deviceProperties?.name).isEqualTo("iPhone")
        assertThat(resolved.deviceProperties?.osVersionNumber).isEqualTo("16.7.16")
    }

    @Test
    fun `a device resolved via xcdevice reports no tunnel`() {
        val resolver = RealIOSDeviceResolver(
            localIOSDevice = deviceCtlThatCannotResolve(),
            xcDeviceLister = xcDeviceListerReturning(xcDeviceEntry()),
        )

        val connectionProperties = resolver.findByUdid(udid)!!.connectionProperties

        // Callers read an absent tunnelState as "driven over lockdown"; a synthesised 'unavailable'
        // would instead look like a device whose tunnel is broken and abort the run.
        assertThat(connectionProperties.tunnelState).isNull()
        assertThat(connectionProperties.isTunnelConnected).isFalse()
        assertThat(connectionProperties.isNetworkAttached).isFalse()
        assertThat(connectionProperties.tunnelIPAddress).isNull()
        assertThat(connectionProperties.localHostnames).isEmpty()
    }

    @Test
    fun `returns null when neither stack knows the device`() {
        val resolver = RealIOSDeviceResolver(
            localIOSDevice = deviceCtlThatCannotResolve(),
            xcDeviceLister = xcDeviceListerReturning(),
        )

        assertThat(resolver.findByUdid(udid)).isNull()
    }

    @Test
    fun `ignores xcdevice entries for other devices`() {
        val resolver = RealIOSDeviceResolver(
            localIOSDevice = deviceCtlThatCannotResolve(),
            xcDeviceLister = xcDeviceListerReturning(xcDeviceEntry(identifier = "00008030-000000000000001E")),
        )

        assertThat(resolver.findByUdid(udid)).isNull()
    }

    @Test
    fun `survives xcdevice blowing up`() {
        val xcDeviceLister = mockk<XcDeviceLister>()
        every { xcDeviceLister.listPhysicalIOSDevices(any()) } throws IllegalStateException("xcdevice hung")

        val resolver = RealIOSDeviceResolver(deviceCtlThatCannotResolve(), xcDeviceLister)

        assertThat(resolver.findByUdid(udid)).isNull()
    }

    @Test
    fun `requireByUdid keeps the original error when nothing can resolve the device`() {
        val resolver = RealIOSDeviceResolver(
            localIOSDevice = deviceCtlThatCannotResolve(),
            xcDeviceLister = xcDeviceListerReturning(),
        )

        val error = assertThrows<IllegalArgumentException> { resolver.requireByUdid(udid) }

        assertThat(error).hasMessageThat().contains(udid)
        assertThat(error).hasMessageThat().contains("not connected or available")
    }

    private fun deviceCtlThatCannotResolve() = mockk<LocalIOSDevice>().also {
        every { it.listDeviceViaDeviceCtl(any<String>()) } throws
            IllegalArgumentException("iOS device with identifier $udid not connected or available")
    }

    private fun xcDeviceListerReturning(vararg entries: XcDeviceEntry) = mockk<XcDeviceLister>().also {
        every { it.listPhysicalIOSDevices(any()) } returns entries.toList()
    }

    private fun xcDeviceEntry(identifier: String = udid) = XcDeviceEntry(
        identifier = identifier,
        name = "iPhone",
        platform = XcDeviceEntry.PLATFORM_IPHONEOS,
        operatingSystemVersion = "16.7.16 (20H392)",
        modelName = "iPhone 8 (Model A1863, A1905, A1906, A1907)",
        architecture = "arm64",
        connectionInterface = "usb",
        available = true,
    )

    private fun deviceCtlDevice(tunnelState: String) = DeviceCtlResponse.Device(
        identifier = "6986451F-A2FF-48DE-A70E-45E06E1F1446",
        deviceProperties = DeviceCtlResponse.DeviceProperties(
            name = "iPhone",
            osVersionNumber = "18.4.1",
            developerModeStatus = "enabled",
        ),
        hardwareProperties = DeviceCtlResponse.HardwareProperties(udid = udid),
        connectionProperties = DeviceCtlResponse.ConnectionProperties(tunnelState = tunnelState),
    )
}
