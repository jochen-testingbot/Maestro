import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import util.XcDeviceLister
import util.XcDeviceProcess

class XcDeviceListerTest {

    @Test
    fun `keeps the physical iOS device and drops the host, simulators and offline entries`() {
        val lister = listerReturning(xcDeviceOutput())

        val devices = lister.listPhysicalIOSDevices()

        assertThat(devices.map { it.identifier })
            .containsExactly("da4fbbdf3ba419a78e312d8ae3e4b338f50f0584")
    }

    @Test
    fun `exposes the fields device discovery needs`() {
        val lister = listerReturning(xcDeviceOutput())

        val device = lister.listPhysicalIOSDevices().single()

        assertThat(device.name).isEqualTo("iPhone")
        // devicectl reports the bare version, so the build number has to be stripped to match
        assertThat(device.osVersionNumber).isEqualTo("16.7.16")
        assertThat(device.modelName).isEqualTo("iPhone 8 (Model A1863, A1905, A1906, A1907)")
        assertThat(device.connectionInterface).isEqualTo("usb")
    }

    @Test
    fun `drops a device that xcdevice reports an error for`() {
        val lister = listerReturning(
            """
            [
              {
                "simulator" : false,
                "available" : false,
                "ignored" : false,
                "identifier" : "da4fbbdf3ba419a78e312d8ae3e4b338f50f0584",
                "platform" : "com.apple.platform.iphoneos",
                "operatingSystemVersion" : "16.7.16 (20H392)",
                "name" : "iPhone",
                "error" : {
                  "code" : -13,
                  "description" : "iPhone is locked."
                }
              }
            ]
            """.trimIndent()
        )

        assertThat(lister.listPhysicalIOSDevices()).isEmpty()
    }

    @Test
    fun `tolerates a host with nothing attached`() {
        assertThat(listerReturning("[]").listPhysicalIOSDevices()).isEmpty()
    }

    @Test
    fun `tolerates xcdevice printing nothing at all`() {
        assertThat(listerReturning("").listPhysicalIOSDevices()).isEmpty()
    }

    @Test
    fun `ignores unknown fields so a future Xcode cannot break discovery`() {
        val lister = listerReturning(
            """
            [
              {
                "simulator" : false,
                "available" : true,
                "ignored" : false,
                "identifier" : "da4fbbdf3ba419a78e312d8ae3e4b338f50f0584",
                "platform" : "com.apple.platform.iphoneos",
                "operatingSystemVersion" : "16.7.16 (20H392)",
                "somethingXcode27Added" : { "nested" : [1, 2, 3] }
              }
            ]
            """.trimIndent()
        )

        assertThat(lister.listPhysicalIOSDevices()).hasSize(1)
    }

    private fun listerReturning(response: String): XcDeviceLister {
        val process = mockk<XcDeviceProcess>()
        every { process.xcdeviceListOutput(any()) } returns response
        return XcDeviceLister(process)
    }

    /**
     * Captured from `xcrun xcdevice list` on a host running Xcode 26.2 with an iPhone 8 on
     * iOS 16.7.16 attached over USB — the case CoreDevice will not pair with. Trimmed to one
     * entry per category.
     */
    private fun xcDeviceOutput(): String {
        return """
            [
              {
                "architecture" : "x86_64h",
                "available" : true,
                "identifier" : "98D65965-9590-5A44-937A-3BBBE94ADA05",
                "ignored" : false,
                "interface" : "usb",
                "modelCode" : "Parallels26,1",
                "modelName" : "Mac",
                "modelUTI" : "com.apple.device",
                "name" : "My Mac",
                "operatingSystemVersion" : "15.7.7 (24G720)",
                "platform" : "com.apple.platform.macosx",
                "simulator" : false
              },
              {
                "architecture" : "arm64",
                "available" : true,
                "identifier" : "da4fbbdf3ba419a78e312d8ae3e4b338f50f0584",
                "ignored" : false,
                "interface" : "usb",
                "modelCode" : "iPhone10,4",
                "modelName" : "iPhone 8 (Model A1863, A1905, A1906, A1907)",
                "modelUTI" : "com.apple.iphone-8-2",
                "name" : "iPhone",
                "operatingSystemVersion" : "16.7.16 (20H392)",
                "platform" : "com.apple.platform.iphoneos",
                "simulator" : false
              },
              {
                "architecture" : "arm64",
                "available" : true,
                "identifier" : "00008120-0014485601E3C01E",
                "ignored" : true,
                "interface" : "usb",
                "modelName" : "iPhone 15",
                "name" : "Ignored iPhone",
                "operatingSystemVersion" : "17.5.1 (21F90)",
                "platform" : "com.apple.platform.iphoneos",
                "simulator" : false
              },
              {
                "architecture" : "arm64",
                "available" : false,
                "identifier" : "00008030-000000000000001E",
                "ignored" : false,
                "modelName" : "iPhone SE",
                "name" : "Unplugged iPhone",
                "operatingSystemVersion" : "16.4 (20E247)",
                "platform" : "com.apple.platform.iphoneos",
                "simulator" : false
              },
              {
                "architecture" : "x86_64",
                "available" : true,
                "identifier" : "562DE509-A3F9-46F5-BA64-7591CD3B262B",
                "ignored" : false,
                "modelCode" : "iPad16,6",
                "modelName" : "iPad Pro 13-inch (M4)",
                "name" : "iPad Pro 13-inch (M4)",
                "operatingSystemVersion" : "18.6 (22G86)",
                "platform" : "com.apple.platform.iphonesimulator",
                "simulator" : true
              }
            ]
        """.trimIndent()
    }
}
