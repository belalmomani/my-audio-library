package com.mylibrary.app.cloud

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.mylibrary.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Request
import org.json.JSONObject

data class Session(val userId: String, val email: String, val accessToken: String, val refreshToken: String, val expiresAtMs: Long)

sealed interface AuthResult {
    data class Ok(val session: Session) : AuthResult
    object ConfirmEmail : AuthResult
    data class Error(val message: String) : AuthResult
}

/** تسجيل الدخول عبر Supabase Auth (GoTrue REST). التوكنات في تخزين مشفّر؛ ولا تُسجَّل في السجلات. */
class AuthRepository(ctx: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        ctx, "auth_secure",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val _session = MutableStateFlow(load())
    val session: StateFlow<Session?> = _session

    val isConfigured: Boolean get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    private fun load(): Session? {
        val u = prefs.getString("uid", null) ?: return null
        return Session(u, prefs.getString("email", "") ?: "", prefs.getString("at", "") ?: "",
            prefs.getString("rt", "") ?: "", prefs.getLong("exp", 0))
    }

    private fun save(s: Session?) {
        prefs.edit {
            if (s == null) clear() else {
                putString("uid", s.userId); putString("email", s.email); putString("at", s.accessToken)
                putString("rt", s.refreshToken); putLong("exp", s.expiresAtMs)
            }
        }
        _session.value = s
    }

    private fun req(path: String, body: JSONObject, token: String? = null) = Request.Builder()
        .url(BuildConfig.SUPABASE_URL.trimEnd('/') + path)
        .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
        .apply { if (token != null) header("Authorization", "Bearer $token") }
        .post(Http.jsonBody(body)).build()

    private fun parse(json: JSONObject, fallbackEmail: String): Session {
        val user = json.getJSONObject("user")
        return Session(user.getString("id"), user.optString("email", fallbackEmail), json.getString("access_token"),
            json.getString("refresh_token"), System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000)
    }

    suspend fun signIn(email: String, password: String): AuthResult {
        if (!isConfigured) return AuthResult.Error("لم تُضبط بيانات Supabase في هذا الإصدار")
        return Http.io {
            val (code, body) = Http.exec(req("/auth/v1/token?grant_type=password",
                JSONObject().put("email", email.trim()).put("password", password)))
            if (code in 200..299) AuthResult.Ok(parse(JSONObject(body), email).also { save(it) })
            else AuthResult.Error(errorMessage(body, "تعذّر تسجيل الدخول"))
        }
    }

    suspend fun signUp(email: String, password: String): AuthResult {
        if (!isConfigured) return AuthResult.Error("لم تُضبط بيانات Supabase في هذا الإصدار")
        return Http.io {
            val (code, body) = Http.exec(req("/auth/v1/signup",
                JSONObject().put("email", email.trim()).put("password", password)))
            if (code !in 200..299) return@io AuthResult.Error(errorMessage(body, "تعذّر إنشاء الحساب"))
            val j = JSONObject(body)
            if (j.has("access_token")) AuthResult.Ok(parse(j, email).also { save(it) }) else AuthResult.ConfirmEmail
        }
    }

    suspend fun signOut() {
        val s = _session.value
        save(null)
        if (s != null && isConfigured) runCatching { Http.io { Http.exec(req("/auth/v1/logout", JSONObject(), s.accessToken)) } }
    }

    /** يعيد توكنًا صالحًا (مع التجديد عند الحاجة) أو null إن لم يكن المستخدم مسجّلًا. */
    suspend fun validToken(): String? {
        val s = _session.value ?: return null
        if (s.expiresAtMs - System.currentTimeMillis() > 60_000) return s.accessToken
        return Http.io {
            val (code, body) = Http.exec(req("/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", s.refreshToken)))
            if (code in 200..299) parse(JSONObject(body), s.email).also { save(it) }.accessToken
            else { if (code == 400 || code == 401) save(null); null }
        }
    }

    fun clearLocal() = save(null)

    private fun errorMessage(body: String, fallback: String): String =
        runCatching { JSONObject(body).let { it.optString("msg", it.optString("error_description", it.optString("message", fallback))) } }
            .getOrDefault(fallback).ifBlank { fallback }
}
