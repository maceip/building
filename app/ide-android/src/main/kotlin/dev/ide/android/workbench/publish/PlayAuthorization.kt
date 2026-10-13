package dev.ide.android.workbench.publish

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.AccountPicker
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import dev.ide.workbench.publish.SessionAccessTokenProvider
import java.security.MessageDigest
import java.util.WeakHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal const val ANDROID_PUBLISHER_SCOPE = "https://www.googleapis.com/auth/androidpublisher"

/** Never save this object, its provider, or its input with rememberSaveable / SavedStateHandle. */
internal class PlayAuthorizationState(private val clearNativeCache: (String) -> Unit = {}) {
    val tokens = SessionAccessTokenProvider()
    var connected by mutableStateOf(false)
        private set
    var accountLabel by mutableStateOf("")
        private set
    var nativeAccount by mutableStateOf(false)
        private set
    var identityKey by mutableStateOf("")
        private set
    private var manualSessionNumber = 0L
    var authorizing by mutableStateOf(false)
        internal set
    var expiresAtElapsed by mutableStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        internal set
    var manualTokenInput by mutableStateOf("")
    var manualAccountLabel by mutableStateOf("")
    internal var pendingAccount: Account? = null
    internal var generation = 0L
        private set
    internal var closed = false
        private set
    private var nativeToken: String? = null

    fun startAttempt(): Long {
        check(!closed)
        authorizing = true
        error = null
        return ++generation
    }

    fun acceptNative(token: String, account: Account, attempt: Long) {
        if (closed || attempt != generation) {
            clearNativeCache(token)
            return
        }
        replaceToken(token, account.name, native = true)
        authorizing = false
        pendingAccount = null
    }

    fun acceptManual() {
        require(manualAccountLabel.trim().isNotBlank()) { "Enter an account label for the confirmation screen." }
        require(!closed)
        val token = manualTokenInput
        manualTokenInput = ""
        replaceToken(token, "Session token · ${manualAccountLabel.trim()} (identity not verified)", native = false)
    }

    private fun replaceToken(token: String, account: String, native: Boolean) {
        // Validate before replacing a working session. The provider's validation message is static/safe.
        tokens.setToken(token)
        val previous = nativeToken
        nativeToken = if (native) token else null
        if (previous != null && previous != token) clearNativeCache(previous)
        accountLabel = account
        nativeAccount = native
        identityKey = if (native) "google:$account" else "manual:${++manualSessionNumber}"
        connected = true
        // Access tokens usually last one hour. Use a conservative local ceiling; HTTP 401 can expire sooner.
        expiresAtElapsed = SystemClock.elapsedRealtime() + 55 * 60_000L
        error = null
    }

    fun disconnect(message: String? = null) {
        ++generation // Late Play services callbacks may not recreate a cleared session.
        val old = nativeToken
        nativeToken = null
        tokens.clear()
        manualTokenInput = ""
        pendingAccount = null
        connected = false
        nativeAccount = false
        authorizing = false
        expiresAtElapsed = 0L
        error = message
        if (old != null) clearNativeCache(old)
    }

    fun close() {
        closed = true
        disconnect()
    }

    override fun toString(): String = "PlayAuthorizationState(redacted)"
}

internal data class PlayOAuthRegistration(val packageName: String, val signingSha1: List<String>)
internal data class PlayNativeAuthorization(
    val state: PlayAuthorizationState,
    val available: Boolean,
    val authorize: () -> Unit,
    val registration: PlayOAuthRegistration?,
)

/** Native account choice + AuthorizationClient authorization, not Google sign-in or an ID token. */
@Composable
internal fun rememberPlayAuthorization(): PlayNativeAuthorization {
    val context = LocalContext.current
    val activity = remember(context) { context.findPublishActivity() }
    val client = remember(context) { Identity.getAuthorizationClient(context) }
    val state = remember(client) {
        PlayAuthorizationState { token ->
            // Clear the Play services cache as well as our memory. A cache-clear failure must never log a token.
            runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()) }
        }
    }
    var registration by remember(context) { mutableStateOf<PlayOAuthRegistration?>(null) }
    var resolutionAttempt by remember { mutableStateOf(0L) }

    fun acceptResult(result: AuthorizationResult, attempt: Long) {
        val account = state.pendingAccount
        val token = result.accessToken
        if (state.closed || attempt != state.generation) {
            if (token != null) runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()) }
            return
        }
        if (account == null || token.isNullOrBlank() || ANDROID_PUBLISHER_SCOPE !in result.grantedScopes) {
            state.authorizing = false
            state.error = "Google did not grant the Google Play publishing scope. Authorize again and approve that scope."
            return
        }
        try {
            state.acceptNative(token, account, attempt)
        } catch (_: IllegalArgumentException) {
            state.authorizing = false
            state.error = "Google returned an unusable access token. Authorize again."
        }
    }

    val resolutionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (!state.closed && resolutionAttempt == state.generation) {
            if (result.resultCode != Activity.RESULT_OK) {
                state.authorizing = false
                state.pendingAccount = null
                state.error = "Authorization was cancelled. No publishing request was made."
            } else {
                try {
                    acceptResult(client.getAuthorizationResultFromIntent(result.data), resolutionAttempt)
                } catch (failure: Exception) {
                    state.authorizing = false
                    state.pendingAccount = null
                    state.error = safeGoogleAuthorizationError(failure)
                }
            }
        }
    }

    fun authorizeAccount(account: Account, attempt: Long) {
        if (state.closed || attempt != state.generation) return
        state.pendingAccount = account
        val request = publisherAuthorizationRequest(account)
        client.authorize(request)
            .addOnSuccessListener { result ->
                if (state.closed || attempt != state.generation) {
                    result.accessToken?.let { token ->
                        runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()) }
                    }
                } else if (result.hasResolution()) {
                    val pending = result.pendingIntent
                    if (pending == null) {
                        state.authorizing = false
                        state.error = "Google authorization did not supply a consent screen. Update Google Play services and try again."
                    } else {
                        try {
                            resolutionAttempt = attempt
                            resolutionLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                        } catch (_: Exception) {
                            state.authorizing = false
                            state.error = "The native Google consent screen could not open. Check Google Play services and OAuth setup."
                        }
                    }
                } else {
                    acceptResult(result, attempt)
                }
            }
            .addOnFailureListener { failure ->
                if (!state.closed && attempt == state.generation) {
                    state.authorizing = false
                    state.pendingAccount = null
                    state.error = safeGoogleAuthorizationError(failure)
                }
            }
    }

    val accountLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (!state.closed && state.authorizing) {
            val name = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            val type = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE)
            if (result.resultCode != Activity.RESULT_OK || name.isNullOrBlank() || type != "com.google") {
                state.authorizing = false
                state.error = "Google account selection was cancelled. No publishing request was made."
            } else {
                try {
                    authorizeAccount(Account(name, type), state.generation)
                } catch (failure: Exception) {
                    state.authorizing = false
                    state.error = safeGoogleAuthorizationError(failure)
                }
            }
        }
    }

    LaunchedEffect(context) {
        registration = withContext(Dispatchers.IO) { readPlayOAuthRegistration(context) }
    }
    LaunchedEffect(state.connected, state.expiresAtElapsed) {
        if (state.connected) {
            delay((state.expiresAtElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            state.disconnect("This session access token needs renewal. Authorize again; your nonsecret form and edit details remain here.")
        }
    }
    DisposableEffect(state, activity) {
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) state.close()
        }
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
            state.close()
        }
    }
    return PlayNativeAuthorization(state, activity != null, authorize = {
        if (!state.closed && !state.authorizing) {
            state.startAttempt()
            try {
                val options = AccountPicker.AccountChooserOptions.Builder()
                    .setAllowableAccountsTypes(listOf("com.google"))
                    .setAlwaysShowAccountPicker(true)
                    .setTitleOverrideText("Choose the Google account with Play Console access")
                    .build()
                accountLauncher.launch(AccountPicker.newChooseAccountIntent(options))
            } catch (_: Exception) {
                state.authorizing = false
                state.error = "Native Google account selection is unavailable. Install/update Google Play services, or use the session-only access-token alternative."
            }
        }
    }, registration = registration)
}

internal fun publisherAuthorizationRequest(account: Account): AuthorizationRequest = AuthorizationRequest.builder()
    .setAccount(account)
    .setRequestedScopes(listOf(Scope(ANDROID_PUBLISHER_SCOPE)))
    .build()

internal fun safeGoogleAuthorizationError(failure: Exception): String = when ((failure as? ApiException)?.statusCode) {
    10, 12500 -> "Google OAuth is not configured for this installed app. Register the exact IDE package and signing SHA-1 below as an Android OAuth client, enable the Play Developer API, and configure consent/test users. No Google write was made."
    7 -> "Google authorization could not reach the network. Check connectivity and authorize again."
    16, 12501 -> "Google authorization was cancelled. No publishing request was made."
    else -> "Google authorization could not finish. Check Google Play services, the Android OAuth package/SHA-1 registration, consent setup, and account permissions. No publishing request was made."
}

@Suppress("DEPRECATION")
internal fun readPlayOAuthRegistration(context: Context): PlayOAuthRegistration {
    val fingerprints = runCatching {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners.orEmpty()
        } else {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty()
        }
        signatures.map { signature ->
            MessageDigest.getInstance("SHA-1").digest(signature.toByteArray()).joinToString(":") { "%02X".format(it.toInt() and 0xff) }
        }
    }.getOrDefault(emptyList())
    return PlayOAuthRegistration(context.packageName, fingerprints)
}

internal fun Context.findPublishActivity(): Activity? {
    var current: Context = this
    val seen = HashSet<Context>()
    while (seen.add(current)) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext ?: return null
    }
    return null
}

/** Own only our FLAG_SECURE lease; do not clear a flag already set by the host or another publisher. */
internal object PublishSecureWindow {
    private data class Lease(var count: Int, val previouslySecure: Boolean)
    private val leases = WeakHashMap<Window, Lease>()

    @Synchronized fun acquire(window: Window) {
        val existing = leases[window]
        if (existing != null) {
            existing.count++
        } else {
            leases[window] = Lease(1, window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    @Synchronized fun release(window: Window) {
        val lease = leases[window] ?: return
        if (--lease.count == 0) {
            leases.remove(window)
            if (!lease.previouslySecure) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

@Composable
internal fun SecurePlayPublishSurface() {
    val context = LocalContext.current
    val window = remember(context) { context.findPublishActivity()?.window }
    DisposableEffect(window) {
        window?.let(PublishSecureWindow::acquire)
        onDispose { window?.let(PublishSecureWindow::release) }
    }
}
