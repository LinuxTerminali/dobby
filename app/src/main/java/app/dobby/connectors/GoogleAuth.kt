package app.dobby.connectors

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One Google login shared by Gmail, Docs and Sheets. OAuth PKCE via AppAuth with an
 * Android OAuth client (no secret). Setup: Google Cloud Console → OAuth client → Android,
 * package app.dobby + your signing SHA-1; paste the client id in Settings.
 */
class GoogleAuth(private val context: Context) {
    private val prefs = context.getSharedPreferences("google_auth", Context.MODE_PRIVATE)
    private val service by lazy { AuthorizationService(context) }
    private val config = AuthorizationServiceConfiguration(
        Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),
        Uri.parse("https://oauth2.googleapis.com/token"))

    var clientId: String
        get() = prefs.getString("client_id", "") ?: ""
        set(v) = prefs.edit().putString("client_id", v).apply()

    private var state: AuthState
        get() = prefs.getString("state", null)?.let { AuthState.jsonDeserialize(it) } ?: AuthState()
        set(v) = prefs.edit().putString("state", v.jsonSerializeString()).apply()

    fun grantedScopes(): Set<String> = state.scopeSet ?: emptySet()
    fun isSignedIn() = state.isAuthorized
    fun signOut() { state = AuthState() }

    /** Intent that opens Google's consent screen. Launch it with StartActivityForResult. */
    fun signInIntent(scopes: List<String>): Intent {
        require(clientId.isNotBlank()) { "Set the Google OAuth client id in Settings first" }
        val req = AuthorizationRequest.Builder(config, clientId, ResponseTypeValues.CODE,
            Uri.parse("app.dobby:/oauth2redirect"))
            .setScopes(scopes)
            .setAdditionalParameters(mapOf("access_type" to "offline", "prompt" to "consent"))
            .build()
        return service.getAuthorizationRequestIntent(req)
    }

    /** Call from the activity result. Exchanges the code for tokens. */
    suspend fun handleRedirect(data: Intent?) {
        val resp = data?.let { AuthorizationResponse.fromIntent(it) }
        val ex = data?.let { AuthorizationException.fromIntent(it) }
        val s = state; s.update(resp, ex); state = s
        if (resp == null) throw ex ?: IllegalStateException("no auth response")
        suspendCancellableCoroutine<Unit> { cont ->
            service.performTokenRequest(resp.createTokenExchangeRequest()) { tr, tex ->
                val s2 = state; s2.update(tr, tex); state = s2
                if (tr != null) cont.resume(Unit) else cont.resumeWithException(tex ?: IllegalStateException("token exchange failed"))
            }
        }
    }

    private suspend fun freshToken(): String = suspendCancellableCoroutine { cont ->
        val s = state
        s.performActionWithFreshTokens(service) { access, _, ex ->
            state = s
            if (access != null) cont.resume(access) else cont.resumeWithException(ex ?: IllegalStateException("no token"))
        }
    }

    /** Authenticated GET returning the response body. */
    suspend fun get(url: String): String {
        val token = freshToken()
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer $token")
        c.connectTimeout = 15000; c.readTimeout = 30000
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) throw IllegalStateException("HTTP $code: ${body.take(300)}")
        return body
    }

    companion object {
        const val SCOPE_GMAIL = "https://www.googleapis.com/auth/gmail.readonly"
        const val SCOPE_DOCS = "https://www.googleapis.com/auth/documents.readonly"
        const val SCOPE_SHEETS = "https://www.googleapis.com/auth/spreadsheets.readonly"
    }
}
