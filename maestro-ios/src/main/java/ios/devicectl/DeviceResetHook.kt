package ios.devicectl

import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Delegates app-state and keychain resets on real iOS devices to an external command.
 *
 * iOS gives no way to wipe an app's data container or keychain items in place on a physical
 * device, so the only reliable reset is reinstalling the app (and, for the keychain, running a
 * helper signed with the app's bundle id). That needs the signed ipa and the signing setup,
 * which live with whoever provisioned the device, not with Maestro. The host points
 * [ENV_VAR] at an executable that is invoked as:
 *
 *   <hook> clear-state <bundleId>
 *   <hook> clear-keychain
 *
 * A zero exit means the reset happened; anything else fails the command with the hook's output.
 */
class DeviceResetHook(
    private val hookPath: String,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    fun clearState(bundleId: String) = run("clear-state", bundleId)

    fun clearKeychain() = run("clear-keychain")

    private fun run(vararg args: String) {
        val command = listOf(hookPath) + args
        logger.info("Running device reset hook: ${command.joinToString(" ")}")
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()

        // Read on a separate thread so a hook that never exits cannot block us past the timeout.
        val tail = ArrayDeque<String>()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    logger.info("[reset hook] $line")
                    synchronized(tail) {
                        tail.addLast(line)
                        if (tail.size > TAIL_LINES) tail.removeFirst()
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("Device reset hook '${args.joinToString(" ")}' timed out after ${timeoutMs / 1000}s")
        }
        reader.join(READER_JOIN_MS)

        val exitCode = process.exitValue()
        if (exitCode != 0) {
            val output = synchronized(tail) { tail.joinToString("\n") }
            throw IllegalStateException(
                "Device reset hook '${args.joinToString(" ")}' failed with exit code $exitCode" +
                    if (output.isNotBlank()) ":\n$output" else ""
            )
        }
    }

    companion object {
        const val ENV_VAR = "MAESTRO_IOS_DEVICE_RESET_HOOK"

        // Reinstalling a large ipa over USB can take a few minutes.
        private const val DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L
        private const val TAIL_LINES = 20
        private const val READER_JOIN_MS = 2000L

        private val logger = LoggerFactory.getLogger(DeviceResetHook::class.java)

        fun fromEnvironment(env: Map<String, String> = System.getenv()): DeviceResetHook? {
            val path = env[ENV_VAR]?.takeIf { it.isNotBlank() } ?: return null
            if (!File(path).canExecute()) {
                logger.warn("$ENV_VAR is set to '$path', which is not an executable file; ignoring it")
                return null
            }
            return DeviceResetHook(path)
        }
    }
}
