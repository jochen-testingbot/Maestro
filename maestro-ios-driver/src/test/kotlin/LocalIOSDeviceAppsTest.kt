import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import util.DeviceCtlProcess
import util.LocalIOSDevice
import java.nio.file.Files
import kotlin.io.path.writeText

class LocalIOSDeviceAppsTest {

    private val udid = "00008120-0014485601E3C01E"

    @Test
    fun `reads bundle identifiers off a devicectl apps response`() {
        val device = deviceReturning(
            """
            {
              "info" : { "outcome" : "success" },
              "result" : {
                "apps" : [
                  {
                    "bundleIdentifier" : "nl.optimizers.app4salesv3",
                    "name" : "App4Sales",
                    "removable" : true
                  },
                  {
                    "bundleIdentifier" : "dev.mobile.maestro-driver-iosUITests.xctrunner",
                    "name" : "maestro-driver-iosUITests-Runner",
                    "removable" : true
                  }
                ]
              }
            }
            """.trimIndent()
        )

        assertThat(device.listApps(udid)).containsExactly(
            "nl.optimizers.app4salesv3",
            "dev.mobile.maestro-driver-iosUITests.xctrunner",
        )
    }

    @Test
    fun `skips entries without a usable bundle identifier`() {
        val device = deviceReturning(
            """
            {
              "result" : {
                "apps" : [
                  { "name" : "No bundle id at all" },
                  { "bundleIdentifier" : "", "name" : "Blank bundle id" },
                  { "bundleIdentifier" : "nl.optimizers.app4salesv3" }
                ]
              }
            }
            """.trimIndent()
        )

        assertThat(device.listApps(udid)).containsExactly("nl.optimizers.app4salesv3")
    }

    @Test
    fun `a device with no apps yields an empty set`() {
        val device = deviceReturning("""{ "result" : { "apps" : [] } }""")

        assertThat(device.listApps(udid)).isEmpty()
    }

    @Test
    fun `degrades instead of throwing when devicectl refuses the device`() {
        // What devicectl writes when it cannot reach the device at all, e.g. a pre-iOS-17 device
        // that CoreDevice declined to pair with. Knowing the installed apps only sharpens the
        // runner's foreground-app guess, so a failure here must not take the session down.
        val device = deviceReturning(
            """
            {
              "error" : {
                "code" : 1000,
                "domain" : "com.apple.dt.CoreDeviceError",
                "userInfo" : { "NSLocalizedDescription" : { "string" : "The specified device was not found." } }
              },
              "info" : { "outcome" : "failed" }
            }
            """.trimIndent()
        )

        assertThat(device.listApps(udid)).isEmpty()
    }

    @Test
    fun `degrades when devicectl writes nothing`() {
        assertThat(deviceReturning("").listApps(udid)).isEmpty()
    }

    @Test
    fun `degrades on malformed output`() {
        assertThat(deviceReturning("not json at all").listApps(udid)).isEmpty()
    }

    private fun deviceReturning(response: String): LocalIOSDevice {
        val output = Files.createTempFile("apps", ".json").apply { writeText(response) }
        val process = mockk<DeviceCtlProcess>()
        every { process.devicectlAppsOutput(any()) } returns output.toFile()
        return LocalIOSDevice(process)
    }
}
