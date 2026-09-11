import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import util.DeviceLanAddressResolver

class DeviceLanAddressResolverTest {

    // Real `arp -an` output from a lab host, including the leading-zero stripping macOS applies.
    private val arpOutput = """
        ? (192.168.3.1) at 9c:5:d6:b0:cf:f0 on en0 ifscope [ethernet]
        ? (192.168.3.9) at f0:18:98:e9:73:ea on en0 ifscope [ethernet]
        ? (192.168.3.155) at ae:d9:4:46:20:28 on en0 ifscope [ethernet]
        ? (192.168.3.174) at b8:1:1f:dd:89:3b on en0 ifscope [ethernet]
    """.trimIndent()

    @Test
    fun `matches a MAC that lockdown reports with leading zeros`() {
        // lockdown prints b8:01:1f:..., arp prints b8:1:1f:...
        assertThat(DeviceLanAddressResolver.addressForMac(arpOutput, "b8:01:1f:dd:89:3b"))
            .isEqualTo("192.168.3.174")
    }

    @Test
    fun `matches a MAC with several stripped octets`() {
        assertThat(DeviceLanAddressResolver.addressForMac(arpOutput, "ae:d9:04:46:20:28"))
            .isEqualTo("192.168.3.155")
    }

    @Test
    fun `is case insensitive`() {
        assertThat(DeviceLanAddressResolver.addressForMac(arpOutput, "B8:01:1F:DD:89:3B"))
            .isEqualTo("192.168.3.174")
    }

    @Test
    fun `returns null when the device is not in the table`() {
        assertThat(DeviceLanAddressResolver.addressForMac(arpOutput, "aa:bb:cc:dd:ee:ff")).isNull()
    }

    @Test
    fun `does not confuse a partially matching MAC`() {
        // Same prefix, different tail -- must not resolve to the wrong device.
        assertThat(DeviceLanAddressResolver.addressForMac(arpOutput, "b8:01:1f:dd:89:3c")).isNull()
    }

    @Test
    fun `normalizes an all-zero octet`() {
        assertThat(DeviceLanAddressResolver.normalizeMac("00:0a:0b:00:10:20")).isEqualTo("0:a:b:0:10:20")
    }
}

class DeviceBonjourResolverTest {

    @Test
    fun `extracts the service instance from the name lockdown reports`() {
        val full = "b8:01:1f:dd:89:3b@fe80::ba01:1fff:fedd:893b-supportsRP-24._apple-mobdev2._tcp.local."
        assertThat(DeviceLanAddressResolver.serviceInstanceName(full))
            .isEqualTo("b8:01:1f:dd:89:3b@fe80::ba01:1fff:fedd:893b-supportsRP-24")
    }

    @Test
    fun `tolerates a name without the trailing dot`() {
        val full = "aa:bb:cc:dd:ee:ff@fe80::1._apple-mobdev2._tcp.local"
        assertThat(DeviceLanAddressResolver.serviceInstanceName(full))
            .isEqualTo("aa:bb:cc:dd:ee:ff@fe80::1")
    }

    @Test
    fun `reads the target hostname out of dns-sd resolve output`() {
        val output = """
            DATE: ---Thu 11 Sep 2026---
            18:44:40.203  ...STARTING...
            18:44:40.203  b8:01:1f:dd:89:3b@fe80::ba01:1fff:fedd:893b-supportsRP-24._apple-mobdev2._tcp.local. can be reached at iPhone-4.local.:32498 (interface 14) Flags: 1
        """.trimIndent()
        assertThat(DeviceLanAddressResolver.hostnameFromResolve(output)).isEqualTo("iPhone-4.local")
    }

    @Test
    fun `returns null when dns-sd found nothing`() {
        assertThat(DeviceLanAddressResolver.hostnameFromResolve("DATE: ---Thu 11 Sep 2026---\n...STARTING...")).isNull()
        assertThat(DeviceLanAddressResolver.hostnameFromResolve(null)).isNull()
    }
}
