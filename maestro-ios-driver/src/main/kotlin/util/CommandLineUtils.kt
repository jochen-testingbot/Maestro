package util

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import okio.buffer
import okio.source
import org.slf4j.LoggerFactory

object CommandLineUtils {

    private val isWindows = System.getProperty("os.name").startsWith("Windows")
    private val nullFile = File(if (isWindows) "NUL" else "/dev/null")
    private val logger = LoggerFactory.getLogger(CommandLineUtils::class.java)

    private const val DEFAULT_TIMEOUT_SECONDS = 5L * 60

    @Suppress("SpreadOperator")
    fun runCommand(
            parts: List<String>,
            waitForCompletion: Boolean = true,
            outputFile: File? = null,
            params: Map<String, String> = emptyMap(),
            timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    ): Process {
        logger.info("Running command line operation: $parts with $params")

        val processBuilder =
                if (outputFile != null) {
                    ProcessBuilder(*parts.toTypedArray())
                            .redirectOutput(outputFile)
                            .redirectError(outputFile)
                } else {
                    ProcessBuilder(*parts.toTypedArray())
                            .redirectOutput(nullFile)
                            .redirectError(ProcessBuilder.Redirect.PIPE)
                }

        processBuilder.environment().putAll(params)
        val process = processBuilder.start()

        if (waitForCompletion) {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroy()
                throw TimeoutException(
                    "Command timed out after $timeoutSeconds seconds: ${parts.joinToString(" ")}"
                )
            }

            if (process.exitValue() != 0) {
                val processOutput = process.errorStream.source().buffer().readUtf8()

                logger.error("Process failed with exit code ${process.exitValue()}")
                logger.error("Error output $processOutput")

                throw IllegalStateException(processOutput)
            }
        }

        return process
    }
}
