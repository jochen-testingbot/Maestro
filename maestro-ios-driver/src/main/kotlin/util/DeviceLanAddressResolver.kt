package util

import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The device's address on the local network, for reaching the XCTest runner directly.
 *
 * The runner binds 0.0.0.0, so a device on the same network answers on its own IP with no port
 * forward and no CoreDevice tunnel involved. That makes it a genuinely independent last resort:
 * it survives a dead iproxy, a stale tunnel, and an xcodebuild that will not start.
 *
 * Resolution is deliberately conservative. Nothing here guesses: an address is either supplied by
 * the caller or derived from the device's own hardware MAC, so a run can never be pointed at some
 * other phone's runner -- which would be far worse than failing to find an address at all.
 */
object DeviceLanAddressResolver {

    private val logger = LoggerFactory.getLogger(DeviceLanAddressResolver::class.java)

    const val MAESTRO_XCTEST_LAN_HOST = "MAESTRO_XCTEST_LAN_HOST"

    private val IDEVICEINFO_PATHS = listOf(
        "/opt/homebrew/bin/ideviceinfo",
        "/usr/local/bin/ideviceinfo",
        "/opt/local/bin/ideviceinfo",
    )

    private const val COMMAND_TIMEOUT_SECONDS = 10L
    private const val BONJOUR_BROWSE_SECONDS = 6L
    private const val MOBDEV2_SERVICE = "_apple-mobdev2._tcp"

    /** `<instance>._apple-mobdev2._tcp.local. can be reached at iPhone-4.local.:32498 (interface 14)` */
    private val REACHED_AT = Regex("""can be reached at (\S+?)\.?:\d+""")

    /** `? (192.168.3.155) at ae:d9:4:46:20:28 on en0 ifscope [ethernet]` */
    private val ARP_LINE = Regex("""\((\d+\.\d+\.\d+\.\d+)\) at ([0-9a-fA-F:]+)""")

    fun resolve(deviceId: String): String? {
        System.getenv(MAESTRO_XCTEST_LAN_HOST)?.takeIf { it.isNotBlank() }?.let { supplied ->
            logger.info("Using LAN address $supplied for $deviceId ($MAESTRO_XCTEST_LAN_HOST)")
            return supplied
        }

        // Bonjour first. The device publishes an _apple-mobdev2._tcp record and tells us, over
        // USB, the exact instance name it publishes under -- so we resolve the name the device
        // itself named, never a guess. It also keys on the hardware MAC, which means it keeps
        // working with Private Wi-Fi Address left on, unlike the ARP route below.
        bonjourHostname(deviceId)?.let { hostname ->
            logger.info("Resolved Bonjour hostname $hostname for $deviceId")
            return hostname
        }

        val mac = wifiMacAddress(deviceId) ?: return null
        val address = arpLookup(mac)
        if (address == null) {
            // iOS randomises its Wi-Fi MAC per network by default ("Private Wi-Fi Address"), and a
            // randomised address never matches the hardware one lockdown reports, so the device is
            // simply absent from the ARP table under this MAC. Turning that setting off for the
            // network makes this work; there is no host-side way around it.
            logger.info(
                "No Bonjour record and no ARP entry for $deviceId (Wi-Fi MAC $mac), so its LAN address " +
                    "is unknown. To reach this device over Wi-Fi, enable Wi-Fi connections for it " +
                    "(`pymobiledevice3 lockdown wifi-connections on`) so it publishes a $MOBDEV2_SERVICE " +
                    "record, or supply the address with $MAESTRO_XCTEST_LAN_HOST."
            )
            return null
        }

        logger.info("Resolved LAN address $address for $deviceId (Wi-Fi MAC $mac)")
        return address
    }

    /**
     * The device's own mDNS hostname, e.g. `iPhone-4.local` -- unique per device, unlike the
     * generic `iPhone.local`, so it cannot resolve to some other phone on a shared network.
     */
    private fun bonjourHostname(deviceId: String): String? {
        val instance = wirelessLockdownKey(deviceId, "BonjourFullServiceName")
            ?.let { serviceInstanceName(it) }
            ?: return null

        val resolved = runBounded(
            listOf("/usr/bin/dns-sd", "-L", instance, MOBDEV2_SERVICE, "local"),
            BONJOUR_BROWSE_SECONDS,
        )
        return hostnameFromResolve(resolved)
    }

    /** `<instance>._apple-mobdev2._tcp.local.` -> `<instance>` */
    internal fun serviceInstanceName(fullServiceName: String): String? =
        fullServiceName.trim()
            .removeSuffix(".")
            .removeSuffix(".local")
            .removeSuffix(".$MOBDEV2_SERVICE")
            .takeIf { it.isNotBlank() && !it.contains(MOBDEV2_SERVICE) }

    internal fun hostnameFromResolve(dnsSdOutput: String?): String? =
        dnsSdOutput?.lineSequence()
            ?.mapNotNull { REACHED_AT.find(it) }
            ?.firstOrNull()
            ?.groupValues
            ?.get(1)
            ?.takeIf { it.isNotBlank() }

    private fun wirelessLockdownKey(deviceId: String, key: String): String? =
        ideviceinfo()?.let { binary ->
            runOutput(listOf(binary, "-u", deviceId, "-q", "com.apple.mobile.wireless_lockdown", "-k", key))
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }

    private fun ideviceinfo(): String? =
        IDEVICEINFO_PATHS.firstOrNull { File(it).canExecute() }
            ?: runCatching {
                val which = ProcessBuilder("/usr/bin/which", "ideviceinfo").start()
                val resolved = which.inputStream.bufferedReader().use { it.readText() }.trim()
                if (which.waitFor() == 0 && resolved.isNotEmpty()) resolved else null
            }.getOrNull()

    /** `dns-sd` never exits on its own, so collect for [seconds] and then stop it. */
    private fun runBounded(command: List<String>, seconds: Long): String? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val drain = Thread { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }
        drain.isDaemon = true
        drain.start()

        process.waitFor(seconds, TimeUnit.SECONDS)
        process.destroyForcibly()
        drain.join(TimeUnit.SECONDS.toMillis(1))
        output.toString().takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun wifiMacAddress(deviceId: String): String? {
        val binary = ideviceinfo() ?: return null

        return runOutput(listOf(binary, "-u", deviceId, "-k", "WiFiAddress"))
            ?.trim()
            ?.takeIf { it.matches(Regex("""([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}""")) }
    }

    private fun arpLookup(mac: String): String? {
        val output = runOutput(listOf("/usr/sbin/arp", "-an")) ?: return null
        return addressForMac(output, mac)
    }

    /** Split out from the `arp` call so the parsing can be tested without a device attached. */
    internal fun addressForMac(arpOutput: String, mac: String): String? {
        val wanted = normalizeMac(mac)

        return arpOutput.lineSequence()
            .mapNotNull { ARP_LINE.find(it) }
            .firstOrNull { normalizeMac(it.groupValues[2]) == wanted }
            ?.groupValues
            ?.get(1)
    }

    /** macOS `arp` prints each octet without a leading zero, lockdown prints it with one. */
    internal fun normalizeMac(mac: String): String =
        mac.split(":").joinToString(":") { it.trimStart('0').ifEmpty { "0" }.lowercase() }

    private fun runOutput(command: List<String>): String? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val drain = Thread { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }
        drain.isDaemon = true
        drain.start()

        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        drain.join(TimeUnit.SECONDS.toMillis(1))
        if (process.exitValue() == 0) output.toString() else null
    }.getOrNull()
}
