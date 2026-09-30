package maestro.cli.session

import maestro.cli.CliError
import org.slf4j.LoggerFactory
import util.DeviceCtlResponse.ConnectionProperties
import util.LocalIOSDevice
import util.RealIOSDeviceResolver
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Pre-flight check that a physical iOS device can actually be reached, run before the session
 * spends minutes installing and launching the runner against it.
 *
 * A device that dropped off USB and is only paired over Wi-Fi is still reported by
 * `devicectl list devices`, but every devicectl/xcodebuild call against it fails until
 * CoreDevice has a tunnel to it. Fail here, with an actionable message, instead of 2-4
 * minutes later behind a generic driver-startup timeout.
 *
 * `tunnelState` read once does not settle that, though. CoreDevice builds tunnels on demand
 * and tears them down again about ten seconds after the last client lets go of the device, so
 * `disconnected` is also the normal idle state of a healthy cabled device -- and reading it
 * right after some other tool released the device failed real runs. So the device is asked for
 * first, which brings the tunnel up if it can come up at all, and the state is re-read until
 * it says so.
 *
 * Only enforced when devicectl actually reports a tunnel state. Older Xcode/devicectl releases
 * (and pre-CoreDevice devices, i.e. iOS 16 and earlier) omit the field, and those setups drive
 * the device through xcodebuild's lockdown path with no tunnel at all -- so an absent value
 * must not block the run.
 */
class RealIOSDeviceReachabilityCheck(
    private val resolver: RealIOSDeviceResolver = RealIOSDeviceResolver(),
    private val localIOSDevice: LocalIOSDevice = LocalIOSDevice(),
    private val skipRequested: () -> Boolean = { System.getenv(MAESTRO_SKIP_IOS_TUNNEL_CHECK)?.toBoolean() == true },
    private val sleep: (Duration) -> Unit = { Thread.sleep(it.inWholeMilliseconds) },
    private val wakeTimeout: Duration = WAKE_TIMEOUT,
    private val settleTimeout: Duration = SETTLE_TIMEOUT,
    private val pollInterval: Duration = POLL_INTERVAL,
) {

    /**
     * Returns the connection properties the session should go on with: freshly read ones when
     * the tunnel had to be brought up (every tunnel gets a new address), otherwise the ones
     * passed in.
     */
    fun requireReachable(deviceId: String, connectionProperties: ConnectionProperties): ConnectionProperties {
        if (connectionProperties.isTunnelConnected) return connectionProperties

        // No tunnel state at all means CoreDevice never had an opinion about this device — either
        // an older devicectl, or a device it declined to pair with. Both are driven over lockdown,
        // where there is no tunnel to wait for, so there is nothing here to warn about.
        if (connectionProperties.tunnelState == null) {
            logger.info(
                "iOS device {} has no CoreDevice tunnel; it will be driven over the legacy path " +
                    "and the XCTest runner reached through a port forward.",
                deviceId,
            )
            return connectionProperties
        }

        if (skipRequested()) {
            logger.warn(
                "iOS device $deviceId ${describe(connectionProperties)}; not checking whether it is " +
                    "reachable ($MAESTRO_SKIP_IOS_TUNNEL_CHECK is set)."
            )
            return connectionProperties
        }

        logger.info("iOS device {} {}; asking CoreDevice to connect to it.", deviceId, describe(connectionProperties))
        val settled = wakeAndAwaitTunnel(deviceId, connectionProperties)
        if (settled.isTunnelConnected) {
            logger.info("iOS device {} is reachable: {}.", deviceId, describe(settled))
            return settled
        }

        val message = "iOS device $deviceId is paired but not currently reachable: ${describe(settled)} " +
            "even after asking CoreDevice to connect to it."

        // The cable is in, so this is not the network-only case the check exists for, and
        // xcodebuild opens a tunnel of its own when it starts the runner. Let it try rather
        // than lose the run to a state we cannot explain.
        if (settled.transportType == ConnectionProperties.TRANSPORT_WIRED) {
            logger.warn("$message Continuing, since the device is attached over USB.")
            return settled
        }

        throw CliError(
            "$message Connect the device over USB, or — for a network-attached device — make sure it is " +
                "unlocked, on the same network as this host, and trusted, then confirm it shows as " +
                "'connected' in `xcrun devicectl list devices`.\n" +
                "Set $MAESTRO_SKIP_IOS_TUNNEL_CHECK=true to run anyway."
        )
    }

    /**
     * The wake call returns once devicectl has connected (or given up), so the first read is
     * immediate and only the retries wait. The last state seen is what is reported: a device
     * that vanishes mid-poll keeps its previous reading rather than losing the transport type.
     */
    private fun wakeAndAwaitTunnel(deviceId: String, initial: ConnectionProperties): ConnectionProperties {
        runCatching { localIOSDevice.wakeTunnel(deviceId, wakeTimeout.inWholeSeconds) }
            .onFailure { logger.warn("Asking CoreDevice to connect to iOS device $deviceId failed", it) }

        var latest = initial
        repeat(polls()) { attempt ->
            if (attempt > 0) sleep(pollInterval)
            latest = resolver.findByUdid(deviceId)?.connectionProperties ?: latest
            if (latest.isTunnelConnected) return latest
        }
        return latest
    }

    private fun polls(): Int = ceil(settleTimeout / pollInterval).toInt().coerceAtLeast(1)

    private fun describe(connectionProperties: ConnectionProperties): String =
        "devicectl reports tunnelState='${connectionProperties.tunnelState}' " +
            "(transport: ${connectionProperties.transportType ?: "unknown"})"

    companion object {
        const val MAESTRO_SKIP_IOS_TUNNEL_CHECK = "MAESTRO_SKIP_IOS_TUNNEL_CHECK"

        /** A cabled device answers `device info details` in a second or two; over Wi-Fi, several. */
        private val WAKE_TIMEOUT = 15.seconds

        /** How long to keep re-reading `list devices` for the tunnel the wake asked for. */
        private val SETTLE_TIMEOUT = 15.seconds
        private val POLL_INTERVAL = 1.seconds

        private val logger = LoggerFactory.getLogger(RealIOSDeviceReachabilityCheck::class.java)
    }
}
