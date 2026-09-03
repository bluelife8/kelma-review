package tech.kelma.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

@Composable
internal fun AccountRegistrationScreen(
    working: Boolean,
    error: String?,
    message: String?,
    onRegister: (String, String) -> Unit,
    onOpenUri: (String) -> Unit,
    onBack: () -> Unit,
    onContinueWithoutAccount: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var acceptsTerms by remember { mutableStateOf(false) }
    LaunchedEffect(message) {
        if (message != null) {
            password = ""
            confirmation = ""
        }
    }
    val validEmail = email.normalizedAccountEmail().let { it.contains('@') && it.length <= 320 }
    val canSubmit = validEmail && password.length >= 5 && password == confirmation && acceptsTerms && !working
    AccountOnboardingFrame(
        title = "Create your Kelma account",
        description = "Register here for optional KelmaSync. We’ll email a verification link before sign-in.",
        working = working,
        onBack = onBack,
    ) {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            modifier = Modifier.fillMaxWidth().testTag("registration-email"),
            enabled = !working,
            label = { Text("Email") },
            singleLine = true,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth().testTag("registration-password"),
            enabled = !working,
            label = { Text("Password (5+ characters)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = confirmation,
            onValueChange = { confirmation = it },
            modifier = Modifier.fillMaxWidth().testTag("registration-confirm-password"),
            enabled = !working,
            label = { Text("Confirm password") },
            supportingText = if (confirmation.isNotEmpty() && confirmation != password) {
                { Text("Passwords do not match") }
            } else {
                null
            },
            isError = confirmation.isNotEmpty() && confirmation != password,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !working) { acceptsTerms = !acceptsTerms }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = acceptsTerms,
                onCheckedChange = { acceptsTerms = it },
                modifier = Modifier.testTag("registration-legal-consent"),
                enabled = !working,
            )
            Text(
                "I agree to the Kelma Terms of Use and acknowledge the Privacy Policy.",
                modifier = Modifier.padding(start = 8.dp),
                color = onboardingSecondaryColor(),
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onOpenUri(KelmaReviewTermsUrl) }, enabled = !working) {
                Text("Terms")
            }
            TextButton(onClick = { onOpenUri(KelmaReviewPrivacyUrl) }, enabled = !working) {
                Text("Privacy")
            }
        }
        AccountFeedback(error, message)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onRegister(email.normalizedAccountEmail(), password) },
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("registration-submit"),
            enabled = canSubmit,
        ) {
            if (working) OnboardingProgress() else Text("Create account", fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth(), enabled = !working) {
            Text("Back to sign in", fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = onContinueWithoutAccount,
            modifier = Modifier.fillMaxWidth().testTag("registration-continue-without-account"),
            enabled = !working,
        ) {
            Text("Continue without an account")
        }
    }
}

@Composable
internal fun AccountPasswordResetScreen(
    working: Boolean,
    error: String?,
    message: String?,
    initialEmail: String,
    onRequestReset: (String) -> Unit,
    onBack: () -> Unit,
) {
    var email by remember(initialEmail) { mutableStateOf(initialEmail) }
    val normalized = email.normalizedAccountEmail()
    val canSubmit = normalized.contains('@') && normalized.length <= 320 && !working
    AccountOnboardingFrame(
        title = "Reset your password",
        description = "Enter your Kelma account email. We’ll send a secure, time-limited reset link.",
        working = working,
        onBack = onBack,
    ) {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            modifier = Modifier.fillMaxWidth().testTag("password-reset-email"),
            enabled = !working,
            label = { Text("Email") },
            singleLine = true,
        )
        AccountFeedback(error, message)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { onRequestReset(normalized) },
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("password-reset-submit"),
            enabled = canSubmit,
        ) {
            if (working) OnboardingProgress() else Text("Send reset email", fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth(), enabled = !working) {
            Text("Back to sign in", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AccountOnboardingFrame(
    title: String,
    description: String,
    working: Boolean,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val background = if (isDesktopApp) KelmaDesktopColors.Background else KelmaColors.Background
    val surface = if (isDesktopApp) KelmaDesktopColors.Surface else KelmaColors.Surface
    val border = if (isDesktopApp) KelmaDesktopColors.Border else KelmaColors.SurfaceBorder
    Surface(modifier = Modifier.fillMaxSize(), color = background) {
        Column(modifier = Modifier.safeContentPadding()) {
            if (isDesktopApp) DesktopTopToolbar(onDecks = onBack, onSync = {})
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 480.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = if (isDesktopApp) 36.dp else 16.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (!isDesktopApp) {
                        TextButton(onClick = onBack, enabled = !working) {
                            Text("‹ Back", color = KelmaColors.GoldSoft, fontWeight = FontWeight.Bold)
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = surface,
                        shape = MaterialTheme.shapes.large,
                        border = BorderStroke(1.dp, border),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 26.dp)) {
                            Text(
                                title,
                                color = onboardingPrimaryColor(),
                                fontSize = if (isDesktopApp) 24.sp else 28.sp,
                                lineHeight = if (isDesktopApp) 30.sp else 34.sp,
                                fontWeight = FontWeight.ExtraBold,
                            )
                            Text(
                                description,
                                modifier = Modifier.padding(top = 8.dp, bottom = 22.dp),
                                color = onboardingSecondaryColor(),
                                fontSize = 14.sp,
                                lineHeight = 21.sp,
                            )
                            content()
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun AccountFeedback(error: String?, message: String?) {
    val feedback = error ?: message ?: return
    Text(
        feedback,
        modifier = Modifier.padding(top = 12.dp).testTag("account-access-feedback"),
        color = if (error != null) KelmaColors.Bad else KelmaColors.Good,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    )
}

@Composable
private fun OnboardingProgress() {
    CircularProgressIndicator(
        modifier = Modifier.width(20.dp).height(20.dp),
        color = MaterialTheme.colorScheme.onPrimary,
        strokeWidth = 2.dp,
    )
}

@Composable
private fun onboardingPrimaryColor() =
    if (isDesktopApp) KelmaDesktopColors.TextPrimary else KelmaColors.TextPrimary

@Composable
private fun onboardingSecondaryColor() =
    if (isDesktopApp) KelmaDesktopColors.TextSecondary else KelmaColors.TextSecondary
