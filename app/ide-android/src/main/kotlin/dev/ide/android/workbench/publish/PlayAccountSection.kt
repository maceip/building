package dev.ide.android.workbench.publish

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.FileActions

internal const val PLAY_OAUTH_PLAYGROUND_URL = "https://developers.google.com/oauthplayground/"

/** Opening documentation/tooling is not an OAuth callback and never creates an authorized session. */
internal fun openPlayTokenBrowser(fileActions: FileActions): String {
    if (!fileActions.canOpenUrl) return "Open $PLAY_OAUTH_PLAYGROUND_URL in a browser on this phone or a computer. No token has been imported."
    return try {
        fileActions.openUrl(PLAY_OAUTH_PLAYGROUND_URL)
        "Browser opening requested. Return with an access token; browser sign-in alone does not authorize this app."
    } catch (_: Exception) {
        "The browser could not open. You can use Google's OAuth Playground on a computer and import only its short-lived access token."
    }
}

/** Token import is always available; the device-account chooser is an explicit, optional method. */
@Composable
internal fun PlayAccountSection(
    native: PlayNativeAuthorization,
    state: PlayPublishState,
    fileActions: FileActions,
    enabled: Boolean,
) {
    val auth = native.state
    val controlsEnabled = enabled && state.busy == null && !auth.authorizing && !auth.closed
    var showTokenHelp by remember { mutableStateOf(false) }
    var showDeviceAccount by remember { mutableStateOf(false) }
    var showNativeSetup by remember { mutableStateOf(false) }
    var browserNotice by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxWidth().testTag("play-account-section"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Connect without adding a phone account", style = MaterialTheme.typography.titleSmall)
        Text("Use a short-lived token from your browser or computer.", style = MaterialTheme.typography.bodySmall)
        if (auth.connected) {
            Text(
                if (auth.nativeAccount) "Device-account token ready · ${auth.accountLabel}"
                else "Token imported · ${auth.accountLabel}",
                modifier = Modifier.testTag("play-account-session-status"),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text("No token imported", Modifier.testTag("play-account-session-status"), style = MaterialTheme.typography.bodyMedium)
        }
        auth.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        OutlinedTextField(
            value = auth.manualTokenInput,
            onValueChange = { auth.manualTokenInput = it.take(16384) },
            label = { Text("Access token") },
            supportingText = { Text("Session only. No passwords, keys or refresh tokens.") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            enabled = controlsEnabled,
            modifier = Modifier.fillMaxWidth().testTag("play-session-token"),
        )
        OutlinedTextField(
            value = auth.manualAccountLabel,
            onValueChange = { auth.manualAccountLabel = it.take(200) },
            label = { Text("Account label · not verified") },
            singleLine = true,
            enabled = controlsEnabled,
            modifier = Modifier.fillMaxWidth().testTag("play-session-account-label"),
        )
        Button(
            onClick = {
                try {
                    auth.acceptManual()
                    state.synchronizeIdentity()
                } catch (_: Exception) {
                    auth.manualTokenInput = ""
                    auth.error = "Enter an account label and a short-lived OAuth access token only, without Bearer, whitespace, JSON, a refresh token or an ID token. No API request was made."
                }
            },
            enabled = controlsEnabled && auth.manualTokenInput.isNotBlank() && auth.manualAccountLabel.isNotBlank(),
            modifier = Modifier.fillMaxWidth().testTag("play-use-session-token"),
        ) { Text(if (auth.connected) "Replace token" else "Use token") }
        OutlinedButton(
            onClick = state::checkAccess,
            enabled = controlsEnabled && auth.connected && state.form.packageName.isNotBlank(),
            modifier = Modifier.fillMaxWidth().testTag("play-check-access"),
        ) { Text(if (state.accessChecked) "Check Play access again" else "Check Play access") }
        Text(
            when {
                state.accessChecked -> "Read access checked. Publishing permission is checked when you submit."
                state.form.packageName.isBlank() -> "Select a bundle or enter the app package first."
                else -> "Read-only check. Nothing is uploaded."
            },
            modifier = Modifier.testTag("play-account-access-status"),
            style = MaterialTheme.typography.bodySmall,
        )
        if (auth.connected || auth.authorizing) {
            TextButton(
                onClick = { auth.disconnect("Session credentials cleared. No edit was deleted or release undone; Google permission grants remain.") },
                enabled = enabled && state.busy == null,
                modifier = Modifier.testTag("play-disconnect-account"),
            ) { Text(if (auth.authorizing) "Cancel authorization" else "Disconnect") }
        }

        TextButton(
            onClick = { browserNotice = openPlayTokenBrowser(fileActions) },
            enabled = controlsEnabled,
            modifier = Modifier.testTag("play-open-token-browser"),
        ) { Text("Get a token in your browser") }
        browserNotice?.let { Text(it, Modifier.testTag("play-token-browser-notice"), style = MaterialTheme.typography.bodySmall) }
        TextButton(
            onClick = { showTokenHelp = !showTokenHelp },
            modifier = Modifier.testTag("play-token-help"),
        ) { Text(if (showTokenHelp) "Hide token setup help" else "How to get a browser token") }
        if (showTokenHelp) {
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("For manual testing, use Google's OAuth Playground in your external browser or on a computer. This app does not collect browser cookies or add a phone account.", style = MaterialTheme.typography.bodySmall)
                Text("1. Open Playground settings and choose Access type: Online. Enter this custom scope in Step 1:", style = MaterialTheme.typography.bodySmall)
                Text(ANDROID_PUBLISHER_SCOPE, style = MaterialTheme.typography.bodySmall)
                Text("2. Choose Authorize APIs, sign in there and review Google's permission request. Then exchange the authorization code for tokens in Step 2.", style = MaterialTheme.typography.bodySmall)
                Text("3. Copy only the Access token into the masked field here. Add your own account label, use the token, then check Play access. Treat the token like a password and clear any clipboard copy afterward.", style = MaterialTheme.typography.bodySmall)
                Text("Leave ‘Use your own OAuth credentials’ off for this manual test. No client secret or service-account key belongs in this app. Google's test client may reject the scope or account; if so, use your own trusted OAuth tooling. Never substitute a refresh token or authorization code.", style = MaterialTheme.typography.bodySmall)
                Text("Tokens expire; import a fresh token when Google rejects it. The app also clears its session after a local time limit or when this publishing screen is left/destroyed. No automatic renewal, upload or commit occurs.", style = MaterialTheme.typography.bodySmall)
                Text("Direct browser-to-app OAuth is not configured. It would need a developer-owned, registered web flow and secure handoff; opening Play Console or returning from the browser is not API authorization.", style = MaterialTheme.typography.bodySmall)
            }
        }

        HorizontalDivider()
        TextButton(
            onClick = { showDeviceAccount = !showDeviceAccount },
            enabled = controlsEnabled,
            modifier = Modifier.testTag("play-device-account-options"),
        ) { Text(if (showDeviceAccount) "Hide device-account method" else "Use an account on this device · optional") }
        if (showDeviceAccount) {
            Text("This separate method opens Android's Google account chooser. Skip it if you do not want the publishing account on this phone; browser-token import above does not use it.", style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = native.authorize,
                enabled = controlsEnabled && native.available,
                modifier = Modifier.fillMaxWidth().testTag("play-google-authorize"),
            ) { Text(if (auth.authorizing) "Waiting for device authorization…" else "Choose device account") }
            if (!native.available) Text("Device-account authorization is unavailable. Browser-token import remains available.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showNativeSetup = !showNativeSetup }) { Text(if (showNativeSetup) "Hide device OAuth setup" else "Device OAuth setup") }
            if (showNativeSetup) {
                Text("Register the installed IDE package and signing SHA-1 as an Android OAuth client, enable the Play Developer API, and configure consent/test users. The publishing user still needs access to the existing Play app. This is not the package you are uploading.", style = MaterialTheme.typography.bodySmall)
                Text("IDE package: ${native.registration?.packageName ?: "Unavailable"}", style = MaterialTheme.typography.bodySmall)
                Text("Signing SHA-1: ${native.registration?.signingSha1?.joinToString("\n").orEmpty().ifBlank { "Unavailable; inspect the installed APK certificate." }}", style = MaterialTheme.typography.bodySmall)
                Text("Debug, release and Play-distributed IDE signing certificates need their matching registrations. No client is created by this screen.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
