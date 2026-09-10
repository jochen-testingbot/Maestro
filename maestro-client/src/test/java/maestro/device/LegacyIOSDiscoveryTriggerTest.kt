package maestro.device

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import util.DeviceCtlResponse

/**
 * `xcdevice` waits on every attached device before answering, so the fallback it drives has to
 * stay off the startup path of runs that cannot benefit from it. These pin the condition that
 * decides whether it runs at all.
 */
internal class LegacyIOSDiscoveryTriggerTest {

    @Test
    fun `no devices attached does not trigger the fallback`() {
        assertThat(DeviceService.shouldQueryXcDevice(emptyList())).isFalse()
    }

    @Test
    fun `a fleet that devicectl fully resolved does not trigger the fallback`() {
        val entries = listOf(
            device(udid = "00008120-0014485601E3C01E"),
            device(udid = "00008030-000000000000001E"),
        )

        assertThat(DeviceService.shouldQueryXcDevice(entries)).isFalse()
    }

    @Test
    fun `a device CoreDevice refused to pair with triggers the fallback`() {
        // What devicectl reports for a pre-iOS-17 device: a stub identified by a CoreDevice UUID,
        // with pairingState 'unsupported' and no UDID at all.
        val entries = listOf(
            device(udid = null, identifier = "C7351183-17AA-52F7-B60C-B80AE575A637"),
        )

        assertThat(DeviceService.shouldQueryXcDevice(entries)).isTrue()
    }

    @Test
    fun `an unresolved device alongside a resolved one still triggers the fallback`() {
        val entries = listOf(
            device(udid = "00008120-0014485601E3C01E"),
            device(udid = null, identifier = "C7351183-17AA-52F7-B60C-B80AE575A637"),
        )

        assertThat(DeviceService.shouldQueryXcDevice(entries)).isTrue()
    }

    @Test
    fun `a blank udid counts as unresolved`() {
        assertThat(DeviceService.shouldQueryXcDevice(listOf(device(udid = "")))).isTrue()
    }

    @Test
    fun `a missing hardwareProperties block counts as unresolved`() {
        val entry = DeviceCtlResponse.Device(
            identifier = "C7351183-17AA-52F7-B60C-B80AE575A637",
            deviceProperties = null,
            hardwareProperties = null,
        )

        assertThat(DeviceService.shouldQueryXcDevice(listOf(entry))).isTrue()
    }

    private fun device(
        udid: String?,
        identifier: String = "6986451F-A2FF-48DE-A70E-45E06E1F1446",
    ) = DeviceCtlResponse.Device(
        identifier = identifier,
        deviceProperties = DeviceCtlResponse.DeviceProperties(
            name = "iPhone",
            osVersionNumber = "18.4.1",
            developerModeStatus = "enabled",
        ),
        hardwareProperties = DeviceCtlResponse.HardwareProperties(udid = udid),
    )
}
