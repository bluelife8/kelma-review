package tech.kelma.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

private const val DesktopVaultService = "tech.kelma.app"

@Composable
actual fun rememberCredentialVault(): CredentialVault = remember { DesktopCredentialVault() }

internal class DesktopCredentialVault(
    private val osName: String = System.getProperty("os.name").lowercase(),
) : CredentialVault {
    override fun read(clientId: String): String? = when {
        osName.contains("mac") -> runVaultCommand(
            listOf("/usr/bin/security", "find-generic-password", "-a", clientId, "-s", DesktopVaultService, "-w"),
            missingExitCodes = setOf(44),
        )
        osName.contains("win") -> runPowerShell(WindowsReadScript, clientId, missingExitCodes = setOf(3))
        else -> runVaultCommand(
            listOf("secret-tool", "lookup", "service", DesktopVaultService, "account", clientId),
        )?.ifBlank { null }
    }

    override fun write(clientId: String, token: String) {
        require(token.isNotBlank() && '\n' !in token && '\r' !in token) { "Authentication token is invalid" }
        when {
            osName.contains("mac") -> runVaultCommand(
                listOf(
                    "/usr/bin/security", "add-generic-password", "-a", clientId,
                    "-s", DesktopVaultService, "-U", "-X", token.encodeToByteArray().toHex(),
                ),
            )
            osName.contains("win") -> runPowerShell(WindowsWriteScript, clientId, token)
            else -> runVaultCommand(
                listOf(
                    "secret-tool", "store", "--label=Kelma Review sync token",
                    "service", DesktopVaultService, "account", clientId,
                ),
                standardInput = token,
            )
        }
    }

    override fun delete(clientId: String) {
        when {
            osName.contains("mac") -> runVaultCommand(
                listOf("/usr/bin/security", "delete-generic-password", "-a", clientId, "-s", DesktopVaultService),
                missingExitCodes = setOf(44),
            )
            osName.contains("win") -> runPowerShell(WindowsDeleteScript, clientId, missingExitCodes = setOf(3))
            else -> runVaultCommand(
                listOf("secret-tool", "clear", "service", DesktopVaultService, "account", clientId),
            )
        }
    }
}

private fun runPowerShell(
    script: String,
    clientId: String,
    standardInput: String? = null,
    missingExitCodes: Set<Int> = emptySet(),
): String? = runVaultCommand(
    listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script, clientId),
    standardInput,
    missingExitCodes,
)

private fun runVaultCommand(
    command: List<String>,
    standardInput: String? = null,
    missingExitCodes: Set<Int> = emptySet(),
): String? {
    val process = try {
        ProcessBuilder(command).redirectErrorStream(true).start()
    } catch (exception: Exception) {
        throw IllegalStateException("The operating-system credential service is unavailable", exception)
    }
    process.outputStream.bufferedWriter().use { writer ->
        standardInput?.let(writer::write)
        if (standardInput != null) writer.newLine()
    }
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()
    if (exitCode in missingExitCodes) return null
    check(exitCode == 0) { "The operating-system credential service rejected the request" }
    return output.trimEnd('\r', '\n')
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    byte.toUByte().toString(16).padStart(2, '0')
}

private val WindowsReadScript = """
    ${'$'}vault = New-Object Windows.Security.Credentials.PasswordVault
    try { ${'$'}credential = ${'$'}vault.Retrieve('tech.kelma.app', ${'$'}args[0]) }
    catch { exit 3 }
    ${'$'}credential.RetrievePassword()
    [Console]::Out.Write(${'$'}credential.Password)
""".trimIndent()

private val WindowsWriteScript = """
    ${'$'}token = [Console]::In.ReadToEnd().TrimEnd("`r", "`n")
    ${'$'}vault = New-Object Windows.Security.Credentials.PasswordVault
    try { ${'$'}old = ${'$'}vault.Retrieve('tech.kelma.app', ${'$'}args[0]); ${'$'}vault.Remove(${'$'}old) } catch {}
    ${'$'}vault.Add((New-Object Windows.Security.Credentials.PasswordCredential('tech.kelma.app', ${'$'}args[0], ${'$'}token)))
""".trimIndent()

private val WindowsDeleteScript = """
    ${'$'}vault = New-Object Windows.Security.Credentials.PasswordVault
    try { ${'$'}credential = ${'$'}vault.Retrieve('tech.kelma.app', ${'$'}args[0]) }
    catch { exit 3 }
    ${'$'}vault.Remove(${'$'}credential)
""".trimIndent()
