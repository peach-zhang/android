package com.github.gotify

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.github.gotify.client.model.User

internal class Settings(context: Context) {
    private val sharedPreferences: SharedPreferences
    val filesDir: String
    var url: String
        get() {
            val stored = sharedPreferences.getString("url", DEFAULT_URL)!!
            return if (stored.isEmpty()) DEFAULT_URL else stored
        }
        set(value) = sharedPreferences.edit { putString("url", value) }

    companion object {
        private const val DEFAULT_URL = "https://gotify.dobugs.com"
    }
    var token: String?
        get() = sharedPreferences.getString("token", null)
        set(value) = sharedPreferences.edit { putString("token", value) }
    var user: User? = null
        get() {
            val username = sharedPreferences.getString("username", null)
            val admin = sharedPreferences.getBoolean("admin", false)
            return if (username != null) {
                User().name(username).admin(admin)
            } else {
                User().name("UNKNOWN").admin(false)
            }
        }
        private set
    var serverVersion: String
        get() = sharedPreferences.getString("version", "UNKNOWN")!!
        set(value) = sharedPreferences.edit { putString("version", value) }
    var legacyCert: String?
        get() = sharedPreferences.getString("cert", null)
        set(value) = sharedPreferences.edit(commit = true) { putString("cert", value) }.toUnit()
    var caCertPath: String?
        get() = sharedPreferences.getString("caCertPath", null)
        set(value) = sharedPreferences
            .edit(commit = true) { putString("caCertPath", value) }
            .toUnit()
    var validateSSL: Boolean
        get() = sharedPreferences.getBoolean("validateSSL", true)
        set(value) = sharedPreferences.edit { putBoolean("validateSSL", value) }
    var clientCertPath: String?
        get() = sharedPreferences.getString("clientCertPath", null)
        set(value) = sharedPreferences.edit { putString("clientCertPath", value) }
    var clientCertPassword: String?
        get() = sharedPreferences.getString("clientCertPass", null)
        set(value) = sharedPreferences.edit { putString("clientCertPass", value) }
    var oidcCodeVerifier: String?
        get() = sharedPreferences.getString("oidc_code_verifier", null)
        set(value) = sharedPreferences.edit { putString("oidc_code_verifier", value) }
    var oidcState: String?
        get() = sharedPreferences.getString("oidc_state", null)
        set(value) = sharedPreferences.edit { putString("oidc_state", value) }

    init {
        sharedPreferences = context.getSharedPreferences("gotify", Context.MODE_PRIVATE)
        filesDir = context.filesDir.absolutePath
    }

    fun tokenExists(): Boolean = !token.isNullOrEmpty()

    fun clear() {
        url = DEFAULT_URL
        token = null
        validateSSL = true
        legacyCert = null
        caCertPath = null
        clientCertPath = null
        clientCertPassword = null
        oidcCodeVerifier = null
        oidcState = null
    }

    fun setUser(name: String?, admin: Boolean) {
        sharedPreferences.edit { putString("username", name).putBoolean("admin", admin) }
    }

    fun sslSettings(): SSLSettings {
        return SSLSettings(
            validateSSL,
            caCertPath,
            clientCertPath,
            clientCertPassword
        )
    }

    @Suppress("UnusedReceiverParameter")
    private fun Any?.toUnit() = Unit
}
