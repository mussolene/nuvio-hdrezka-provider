package local.hdrezka

import com.fasterxml.jackson.annotation.JsonProperty
import android.util.Log
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object HDRezkaHttp {
    const val BASE_URL = "https://rezka.fi"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 9; Xiaomi) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val PASS_PATH = "/.within.website/x/cmd/anubis/api/pass-challenge"

    fun headers(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "X-Hdrezka-Android-App" to "1",
        "X-Hdrezka-Android-App-Version" to "2.2.5"
    )

    suspend fun getDocument(url: String): Document {
        var response = app.get(
            url,
            headers = headers(),
            cookies = HDRezkaSession.cookies()
        )
        HDRezkaSession.mergeCookies(response.cookies)
        val challenge = response.document.selectFirst("#anubis_challenge")?.text()?.trim()
        if (challenge.isNullOrEmpty()) {
            check(response.isSuccessful && response.text.isNotBlank()) {
                "HDRezka: HTTP ${response.code}, сайт не вернул страницу"
            }
            return response.document
        }

        val payload = mapper.readValue(challenge, AnubisChallenge::class.java)
            ?: error("Не удалось прочитать проверку Anubis")
        val startedAt = System.currentTimeMillis()
        val solution = withContext(Dispatchers.Default) {
            AnubisProofOfWork.solve(payload.challenge.randomData, payload.rules.difficulty)
        }
        val prefixRaw = response.document.selectFirst("#anubis_base_prefix")?.text()?.trim()
        val prefix = prefixRaw?.let { mapper.readValue(it, String::class.java) }.orEmpty()
        val origin = URI(url).let { "${it.scheme}://${it.authority}" }
        val passUrl = buildString {
            append(origin)
            append(prefix)
            append(PASS_PATH)
            append("?id=")
            append(encoded(payload.challenge.id))
            append("&response=")
            append(solution.hash)
            append("&nonce=")
            append(solution.nonce)
            append("&redir=")
            append(encoded(url))
            append("&elapsedTime=")
            append(System.currentTimeMillis() - startedAt + 500)
        }
        val passResponse = app.get(
            passUrl,
            headers = headers(),
            referer = url,
            cookies = HDRezkaSession.cookies()
        )
        HDRezkaSession.mergeCookies(passResponse.cookies)
        response = app.get(
            url,
            headers = headers(),
            cookies = HDRezkaSession.cookies()
        )
        HDRezkaSession.mergeCookies(response.cookies)
        check(response.isSuccessful && response.text.isNotBlank()) {
            "HDRezka: HTTP ${response.code}, сайт не вернул страницу"
        }
        check(response.document.selectFirst("#anubis_challenge") == null) {
            "Сайт повторно запросил проверку Anubis"
        }
        return response.document
    }

    suspend fun login(baseUrl: String, login: String, password: String): Boolean {
        getDocument("$baseUrl/")
        val response = app.post(
            "$baseUrl/ajax/login/",
            data = mapOf(
                "login_name" to login,
                "login_password" to password,
                "login_not_save" to "0"
            ),
            headers = headers() + mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to baseUrl
            ),
            referer = "$baseUrl/",
            cookies = HDRezkaSession.cookies()
        )
        HDRezkaSession.mergeCookies(response.cookies)
        val result = mapper.readValue(response.text, LoginResponse::class.java)
        val hasSession = HDRezkaSession.hasSession()
        Log.d(
            "HDRezkaAuth",
            "success=${result?.success}, cookies=${response.cookies.keys.sorted()}, session=$hasSession"
        )
        return result?.success == true && hasSession
    }

    private fun encoded(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private data class AnubisChallenge(
        @JsonProperty("rules") val rules: Rules,
        @JsonProperty("challenge") val challenge: Challenge
    )

    private data class Rules(
        @JsonProperty("difficulty") val difficulty: Int
    )

    private data class Challenge(
        @JsonProperty("id") val id: String,
        @JsonProperty("randomData") val randomData: String
    )

    private data class LoginResponse(
        @JsonProperty("success") val success: Boolean? = null
    )
}

internal object AnubisProofOfWork {
    private const val MAX_ITERATIONS = 1 shl 28

    fun solve(randomData: String, difficulty: Int): Solution {
        require(randomData.isNotEmpty()) { "Проверка Anubis не содержит исходных данных" }
        require(difficulty in 1..8) { "Неподдерживаемая сложность Anubis: $difficulty" }
        val digest = MessageDigest.getInstance("SHA-256")
        val zeroBytes = difficulty / 2
        val oddNibble = difficulty % 2 != 0
        for (nonce in 0 until MAX_ITERATIONS) {
            val hash = digest.digest((randomData + nonce).toByteArray(StandardCharsets.UTF_8))
            var accepted = true
            for (index in 0 until zeroBytes) {
                if (hash[index].toInt() != 0) {
                    accepted = false
                    break
                }
            }
            if (accepted && oddNibble && hash[zeroBytes].toInt() and 0xF0 != 0) {
                accepted = false
            }
            if (accepted) return Solution(hash.toHex(), nonce)
        }
        error("Проверка Anubis превысила допустимое число попыток")
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    data class Solution(val hash: String, val nonce: Int)
}
