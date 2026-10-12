package local.hdrezka

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object HDRezkaSession {
    private const val PREFS = "hdrezka_nuvio_session"
    private const val VALUE = "encrypted_cookie"
    private const val KEY_ALIAS = "hdrezka_nuvio_cookie_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private lateinit var appContext: Context

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun hasSession(): Boolean {
        val stored = cookies()
        return stored.containsKey("dle_user_id") && stored.containsKey("dle_password")
    }

    fun cookies(): Map<String, String> = CookieCodec.decode(cookieHeader())

    suspend fun login(login: String, password: String): Boolean {
        require(login.isNotBlank()) { "Введите логин" }
        require(password.isNotBlank()) { "Введите пароль" }
        return HDRezkaHttp.login(HDRezkaHttp.BASE_URL, login, password)
    }

    fun claimLoginPrompt(): Boolean {
        val prefs = preferences()
        val process = android.os.Process.myPid()
        synchronized(appContext) {
            if (hasSession() || prefs.getBoolean("guest_mode", false) ||
                prefs.getInt("prompt_process", -1) == process) return false
            return prefs.edit().putInt("prompt_process", process).commit()
        }
    }

    fun useGuestMode() {
        preferences().edit().putBoolean("guest_mode", true).apply()
    }

    fun mergeCookies(newCookies: Map<String, String>) {
        if (newCookies.isEmpty()) return
        val allowed = newCookies.filterKeys {
            it == "dle_user_id" ||
                it == "dle_password" ||
                it == "PHPSESSID" ||
                it.startsWith("techaro.lol-anubis-")
        }
        if (allowed.isEmpty()) return
        saveCookieHeader(CookieCodec.encode(cookies() + allowed))
    }

    fun clear() {
        preferences().edit().remove(VALUE).apply()
    }

    private fun cookieHeader(): String {
        check(::appContext.isInitialized) { "HDRezkaSession is not attached" }
        val encoded = preferences().getString(VALUE, null) ?: return ""
        return runCatching { decrypt(encoded) }
            .getOrElse {
                clear()
                ""
            }
    }

    private fun saveCookieHeader(value: String) {
        preferences().edit().putString(VALUE, encrypt(value)).apply()
    }

    private fun preferences() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val body = Base64.encodeToString(
            cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)),
            Base64.NO_WRAP
        )
        return "$iv.$body"
    }

    private fun decrypt(value: String): String {
        val parts = value.split('.', limit = 2)
        require(parts.size == 2) { "Некорректный формат сеанса" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
        )
        return String(
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)),
            StandardCharsets.UTF_8
        )
    }
}

internal object CookieCodec {
    fun encode(cookies: Map<String, String>): String = cookies.entries
        .sortedBy { it.key }
        .joinToString("; ") { "${it.key}=${it.value}" }

    fun decode(header: String): Map<String, String> = header
        .split(';')
        .mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val key = entry.substring(0, separator).trim()
            val value = entry.substring(separator + 1).trim()
            if (key.isEmpty() || value.isEmpty()) null else key to value
        }
        .toMap()
}
