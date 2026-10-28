package ios.devicectl

import com.github.michaelbull.result.getError
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DeviceResetHookTest {

    @TempDir
    lateinit var tempDir: File

    private fun hook(body: String): File {
        val script = File(tempDir, "hook.sh")
        script.writeText("#!/bin/sh\n$body\n")
        script.setExecutable(true)
        return script
    }

    @Test
    fun `clear-state passes the action and bundle id`() {
        val calls = File(tempDir, "calls.txt")
        val script = hook("echo \"$@\" >> '${calls.path}'")

        DeviceResetHook(script.path).clearState("com.example.app")

        assertThat(calls.readText().trim()).isEqualTo("clear-state com.example.app")
    }

    @Test
    fun `a failing hook throws with its output`() {
        val script = hook("echo 'no ipa for com.example.other'; exit 3")

        val error = assertThrows<IllegalStateException> {
            DeviceResetHook(script.path).clearState("com.example.other")
        }

        assertThat(error.message).contains("exit code 3")
        assertThat(error.message).contains("no ipa for com.example.other")
    }

    @Test
    fun `a hung hook times out`() {
        val script = hook("sleep 30")

        val error = assertThrows<IllegalStateException> {
            DeviceResetHook(script.path, timeoutMs = 300).clearKeychain()
        }

        assertThat(error.message).contains("timed out")
    }

    @Test
    fun `fromEnvironment ignores an unset or non-executable path`() {
        val notExecutable = File(tempDir, "plain.txt").apply { writeText("x") }

        assertThat(DeviceResetHook.fromEnvironment(emptyMap())).isNull()
        assertThat(DeviceResetHook.fromEnvironment(mapOf(DeviceResetHook.ENV_VAR to notExecutable.path))).isNull()
        assertThat(DeviceResetHook.fromEnvironment(mapOf(DeviceResetHook.ENV_VAR to hook("true").path))).isNotNull()
    }

    @Test
    fun `real device delegates clearState and clearKeychain to the hook`() {
        val calls = File(tempDir, "calls.txt")
        val device = DeviceControlIOSDevice("udid", DeviceResetHook(hook("echo \"$@\" >> '${calls.path}'").path))

        device.clearAppState("com.example.app")
        device.clearKeychain()

        assertThat(calls.readLines()).containsExactly("clear-state com.example.app", "clear-keychain").inOrder()
    }

    @Test
    fun `real device surfaces a failed keychain reset as an error result`() {
        val device = DeviceControlIOSDevice("udid", DeviceResetHook(hook("exit 1").path))

        assertThat(device.clearKeychain().getError()).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `real device without a hook keeps the old no-op behaviour`() {
        val device = DeviceControlIOSDevice("udid", resetHook = null)

        device.clearAppState("com.example.app")
        assertThat(device.clearKeychain().getError()).isNull()
    }
}
