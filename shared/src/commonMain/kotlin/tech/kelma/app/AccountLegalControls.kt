package tech.kelma.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal const val KelmaReviewPrivacyUrl = "https://kelma.tech/review/privacy"
internal const val KelmaReviewTermsUrl = "https://kelma.tech/review/terms"
internal const val KelmaReviewSupportUrl = "https://kelma.tech/review/support"
internal const val KelmaAccountDeletionUrl = "https://kelma.tech/review/account-deletion"
internal const val KelmaReviewSourceUrl = "https://github.com/bluelife8/kelma-review"
internal const val KelmaReviewLicenseUrl = "https://github.com/bluelife8/kelma-review/blob/main/LICENSE"
internal const val KelmaReviewNoticesUrl =
    "https://github.com/bluelife8/kelma-review/blob/main/" +
        "shared/src/commonMain/composeResources/files/legal/THIRD_PARTY_NOTICES.md"

private enum class AccountConfirmation {
    RemoveFromDevice,
    DeleteKelmaAccount,
}

@Composable
internal fun AccountLegalControlsDialog(
    signedIn: Boolean,
    username: String?,
    working: Boolean,
    onChooseAccount: () -> Unit,
    onSwitchAccount: () -> Unit,
    onSignOut: () -> Unit,
    onRemoveFromDevice: () -> Unit,
    onOpenUri: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmation by remember { mutableStateOf<AccountConfirmation?>(null) }
    when (confirmation) {
        AccountConfirmation.RemoveFromDevice -> RemoveFromDeviceConfirmation(
            username = username,
            onConfirm = {
                confirmation = null
                onRemoveFromDevice()
            },
            onBack = { confirmation = null },
        )
        AccountConfirmation.DeleteKelmaAccount -> DeleteKelmaAccountConfirmation(
            onConfirm = {
                confirmation = null
                onOpenUri(KelmaAccountDeletionUrl)
            },
            onBack = { confirmation = null },
        )
        null -> AccountLegalOverview(
            signedIn = signedIn,
            username = username,
            working = working,
            onChooseAccount = onChooseAccount,
            onSwitchAccount = onSwitchAccount,
            onSignOut = onSignOut,
            onRemoveFromDevice = { confirmation = AccountConfirmation.RemoveFromDevice },
            onDeleteKelmaAccount = { confirmation = AccountConfirmation.DeleteKelmaAccount },
            onOpenUri = onOpenUri,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun AccountLegalOverview(
    signedIn: Boolean,
    username: String?,
    working: Boolean,
    onChooseAccount: () -> Unit,
    onSwitchAccount: () -> Unit,
    onSignOut: () -> Unit,
    onRemoveFromDevice: () -> Unit,
    onDeleteKelmaAccount: () -> Unit,
    onOpenUri: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text("Account & privacy", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = when {
                        username != null -> "Signed in as $username"
                        signedIn -> "Signed in to KelmaSync"
                        else -> "Using a local collection without an account"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
                Spacer(Modifier.height(18.dp))
                AccountSectionTitle("On this device")
                if (signedIn) {
                    AccountAction(
                        title = "Switch account",
                        description = "Keep this account saved and open the account chooser.",
                        testTag = "account-switch",
                        enabled = !working,
                        onClick = onSwitchAccount,
                    )
                    AccountAction(
                        title = "Sign out",
                        description = "Remove this account's secure token but keep its local collection " +
                            "on this device.",
                        testTag = "account-sign-out",
                        enabled = !working,
                        onClick = onSignOut,
                    )
                    AccountAction(
                        title = "Remove from this device",
                        description = "Delete this account's local collection, media cache, plugins, settings, " +
                            "and token. Cloud data is unchanged.",
                        testTag = "account-remove-device",
                        enabled = !working,
                        destructive = true,
                        onClick = onRemoveFromDevice,
                    )
                } else {
                    AccountAction(
                        title = "Choose or add an account",
                        description = "Sign in for KelmaSync or reopen an account saved on this device.",
                        testTag = "account-choose",
                        enabled = !working,
                        onClick = onChooseAccount,
                    )
                }

                SectionDivider()
                AccountSectionTitle("Cloud data")
                Text(
                    text = "Review cloud data belongs to your shared Kelma account. There is no separate " +
                        "Review-only cloud deletion action. Deleting the Kelma account removes Review sync data " +
                        "and Immersion cloud data.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
                if (signedIn) {
                    Spacer(Modifier.height(10.dp))
                    AccountAction(
                        title = "Delete Kelma account",
                        description = "Review the permanent shared-account deletion consequences before continuing.",
                        testTag = "account-delete-kelma",
                        enabled = !working,
                        destructive = true,
                        onClick = onDeleteKelmaAccount,
                    )
                }

                SectionDivider()
                AccountSectionTitle("Legal & help")
                ResourceLink("Privacy Policy", "What Review stores locally and in KelmaSync", "legal-privacy") {
                    onOpenUri(KelmaReviewPrivacyUrl)
                }
                ResourceLink("Terms of Use", "Terms for Review and optional KelmaSync", "legal-terms") {
                    onOpenUri(KelmaReviewTermsUrl)
                }
                ResourceLink("Support", "Private account help and public issue reporting", "legal-support") {
                    onOpenUri(KelmaReviewSupportUrl)
                }

                SectionDivider()
                AccountSectionTitle("About & licenses")
                Text(
                    text = "Kelma Review is an Apache-2.0 clean room application from Kelma Tech LLC. " +
                        "License and third-party notices are included in every release package.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
                Spacer(Modifier.height(8.dp))
                ResourceLink("Source code", "Public Kelma Review repository", "about-source") {
                    onOpenUri(KelmaReviewSourceUrl)
                }
                ResourceLink("Apache License 2.0", "Kelma Review application license", "about-license") {
                    onOpenUri(KelmaReviewLicenseUrl)
                }
                ResourceLink("Third-party notices", "Dependency and vendored-runtime licenses", "about-notices") {
                    onOpenUri(KelmaReviewNoticesUrl)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun AccountSectionTitle(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun AccountAction(
    title: String,
    description: String,
    testTag: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag(testTag),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (enabled) color.copy(alpha = 0.55f) else Color.Transparent),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            Text(title, fontWeight = FontWeight.Bold, textAlign = TextAlign.Start)
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                textAlign = TextAlign.Start,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ResourceLink(title: String, description: String, testTag: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag(testTag),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            Text(title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(10.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun RemoveFromDeviceConfirmation(username: String?, onConfirm: () -> Unit, onBack: () -> Unit) {
    AlertDialog(
        onDismissRequest = onBack,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text("Remove local account data?", fontWeight = FontWeight.ExtraBold) },
        text = {
            Text(
                "This permanently deletes the local collection, pending unsynced changes, downloaded media, " +
                    "plugins, settings, and secure token for ${username ?: "this account"}. KelmaSync cloud data " +
                    "is not deleted. Export anything you need first.",
                lineHeight = 21.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirm-remove-device")) {
                Text("Remove local data", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onBack) { Text("Cancel") } },
    )
}

@Composable
private fun DeleteKelmaAccountConfirmation(onConfirm: () -> Unit, onBack: () -> Unit) {
    AlertDialog(
        onDismissRequest = onBack,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text("Continue to account deletion?", fontWeight = FontWeight.ExtraBold) },
        text = {
            Text(
                "Deleting your Kelma account permanently removes authentication, KelmaSync decks, cards, " +
                    "review history, settings, cloud media, and Immersion cloud data. Active Kelma subscriptions " +
                    "are canceled. Offline collections, exports, and cards copied to Anki remain until you remove " +
                    "them separately. The secure web flow may ask you to sign in again.",
                lineHeight = 21.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirm-delete-kelma")) {
                Text("Open deletion page", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onBack) { Text("Cancel") } },
    )
}
