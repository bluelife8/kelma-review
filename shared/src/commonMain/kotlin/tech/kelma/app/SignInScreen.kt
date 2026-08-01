package tech.kelma.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class LocalAccountChoice(
    val username: String,
    val endpoint: String,
)

@Composable
fun SignInScreen(
    signingIn: Boolean,
    error: String?,
    onSignIn: (username: String, password: String) -> Unit,
    onBack: () -> Unit,
    accounts: List<LocalAccountChoice> = emptyList(),
    onSelectAccount: (LocalAccountChoice) -> Unit = {},
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var selectedAccount by remember(accounts) { mutableStateOf<LocalAccountChoice?>(null) }
    val submit = { onSignIn(username, password) }
    val selectAccount: (LocalAccountChoice) -> Unit = {
        selectedAccount = it
        username = it.username
        password = ""
        onSelectAccount(it)
    }
    val updateUsername: (String) -> Unit = {
        username = it
        selectedAccount = accounts.firstOrNull { account -> account.username == it }
    }

    if (isDesktopApp) {
        DesktopSignInScreen(
            username = username,
            password = password,
            signingIn = signingIn,
            error = error,
            accounts = accounts,
            selectedAccount = selectedAccount,
            onSelectAccount = selectAccount,
            onUsernameChange = updateUsername,
            onPasswordChange = { password = it },
            onSubmit = submit,
            onBack = onBack,
        )
    } else {
        MobileSignInScreen(
            username = username,
            password = password,
            signingIn = signingIn,
            error = error,
            accounts = accounts,
            selectedAccount = selectedAccount,
            onSelectAccount = selectAccount,
            onUsernameChange = updateUsername,
            onPasswordChange = { password = it },
            onSubmit = submit,
            onBack = onBack,
        )
    }
}

@Composable
private fun DesktopSignInScreen(
    username: String,
    password: String,
    signingIn: Boolean,
    error: String?,
    accounts: List<LocalAccountChoice>,
    selectedAccount: LocalAccountChoice?,
    onSelectAccount: (LocalAccountChoice) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = KelmaDesktopColors.Background) {
        Column(modifier = Modifier.safeContentPadding()) {
            DesktopTopToolbar(onDecks = onBack, onSync = {})
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(20.dp),
                    color = KelmaDesktopColors.Surface,
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.dp, KelmaDesktopColors.Border),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 36.dp, vertical = 32.dp)) {
                        Text(
                            text = if (accounts.isEmpty()) "Sign in to KelmaSync" else "KelmaSync accounts",
                            color = KelmaDesktopColors.TextPrimary,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Open a saved account without entering its password, or add another account.",
                            modifier = Modifier.padding(top = 7.dp),
                            color = KelmaDesktopColors.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(20.dp))
                        SavedAccountChooser(
                            accounts = accounts,
                            selectedAccount = selectedAccount,
                            signingIn = signingIn,
                            onSelectAccount = onSelectAccount,
                            desktop = true,
                        )
                        SignInFields(
                            username,
                            password,
                            signingIn,
                            error,
                            onUsernameChange,
                            onPasswordChange,
                            onSubmit,
                            desktop = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileSignInScreen(
    username: String,
    password: String,
    signingIn: Boolean,
    error: String?,
    accounts: List<LocalAccountChoice>,
    selectedAccount: LocalAccountChoice?,
    onSelectAccount: (LocalAccountChoice) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = KelmaColors.Background) {
        Box(modifier = Modifier.safeContentPadding().fillMaxSize()) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 4.dp),
                enabled = !signingIn,
            ) {
                Text("‹ Back", color = KelmaColors.GoldSoft, fontWeight = FontWeight.Bold)
            }
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 72.dp, end = 20.dp, bottom = 32.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "Kelma Review",
                    color = KelmaColors.Gold,
                    fontSize = 20.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = if (accounts.isEmpty()) "Sign in to KelmaSync" else "KelmaSync accounts",
                    color = KelmaColors.TextPrimary,
                    fontSize = 32.sp,
                    lineHeight = 38.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.8).sp,
                )
                Text(
                    text = "Open a saved account without entering its password, or add another account.",
                    modifier = Modifier.padding(top = 12.dp),
                    color = KelmaColors.TextSecondary,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                )
                Spacer(Modifier.height(28.dp))
                SavedAccountChooser(
                    accounts = accounts,
                    selectedAccount = selectedAccount,
                    signingIn = signingIn,
                    onSelectAccount = onSelectAccount,
                    desktop = false,
                )
                SignInFields(
                    username,
                    password,
                    signingIn,
                    error,
                    onUsernameChange,
                    onPasswordChange,
                    onSubmit,
                    desktop = false,
                )
            }
        }
    }
}

@Composable
private fun SavedAccountChooser(
    accounts: List<LocalAccountChoice>,
    selectedAccount: LocalAccountChoice?,
    signingIn: Boolean,
    onSelectAccount: (LocalAccountChoice) -> Unit,
    desktop: Boolean,
) {
    if (accounts.isEmpty()) return
    val primaryColor = if (desktop) KelmaDesktopColors.TextPrimary else KelmaColors.TextPrimary
    val secondaryColor = if (desktop) KelmaDesktopColors.TextSecondary else KelmaColors.TextSecondary
    val selectedBorder = if (desktop) KelmaDesktopColors.Gold else KelmaColors.Gold
    val normalBorder = if (desktop) KelmaDesktopColors.Border else KelmaColors.SurfaceBorder
    Text(
        text = "Saved accounts",
        color = primaryColor,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(Modifier.height(8.dp))
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 152.dp).verticalScroll(rememberScrollState()),
    ) {
        accounts.forEach { account ->
            val selected = account == selectedAccount
            OutlinedButton(
                onClick = { onSelectAccount(account) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .testTag("saved-account-${account.username}"),
                enabled = !signingIn,
                border = BorderStroke(
                    if (selected) 2.dp else 1.dp,
                    if (selected) selectedBorder else normalBorder,
                ),
            ) {
                if (desktop) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = if (selected) "✓ ${account.username}" else account.username,
                            color = primaryColor,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        )
                        Text(
                            text = account.endpoint,
                            color = secondaryColor,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = if (selected) "✓ ${account.username}" else account.username,
                            color = primaryColor,
                            lineHeight = 22.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        )
                        Text(
                            text = account.endpoint,
                            color = secondaryColor,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        )
                    }
                }
            }
        }
    }
    Text(
        text = "Add another account",
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        color = secondaryColor,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun SignInFields(
    username: String,
    password: String,
    signingIn: Boolean,
    error: String?,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    desktop: Boolean,
) {
    val canSubmit = username.isNotBlank() && password.isNotEmpty() && !signingIn
    OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        modifier = Modifier.fillMaxWidth().testTag("sign-in-username"),
        enabled = !signingIn,
        label = { Text("Email or username") },
        singleLine = true,
    )
    Spacer(Modifier.height(if (desktop) 12.dp else 16.dp))
    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        modifier = Modifier.fillMaxWidth().testTag("sign-in-password"),
        enabled = !signingIn,
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
    )
    error?.let {
        Text(
            text = it,
            modifier = Modifier.padding(top = 12.dp),
            color = KelmaColors.Bad,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Spacer(Modifier.height(if (desktop) 22.dp else 24.dp))
    Button(
        onClick = onSubmit,
        modifier = Modifier
            .fillMaxWidth()
            .height(if (desktop) 44.dp else 48.dp)
            .testTag("sign-in-submit"),
        enabled = canSubmit,
    ) {
        if (signingIn) {
            CircularProgressIndicator(
                modifier = Modifier.width(20.dp).height(20.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
            )
        } else {
            Text("Sign in", fontWeight = FontWeight.Bold)
        }
    }
}
