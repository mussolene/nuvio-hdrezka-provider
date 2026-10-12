package local.hdrezka

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.*

internal data class HDRezkaSearchCard(
    val title: String,
    val href: String,
    val posterUrl: String,
    val type: HDRezkaSearchType,
    val year: Int?,
    val episode: Int?
)

internal enum class HDRezkaSearchType { MOVIE, SERIES }

internal object HDRezkaSearchParser {
    private val yearPattern = Regex("\\b(?:19|20)\\d{2}\\b")

    fun card(element: Element): HDRezkaSearchCard? {
        val title = element.selectFirst("div.b-content__inline_item-link > a")
            ?.text()
            ?.trim()
            .orEmpty()
        val href = element.selectFirst("a[href]")?.attr("href")?.trim().orEmpty()
        if (title.isEmpty() || href.isEmpty()) return null

        val metadata = element.selectFirst("div.b-content__inline_item-link > div")
            ?.text()
            .orEmpty()
        val type = if (element.select("span.info").isNotEmpty()) {
            HDRezkaSearchType.SERIES
        } else {
            HDRezkaSearchType.MOVIE
        }
        val episode = element.select("span.info")
            .text()
            .substringAfter(",")
            .replace(Regex("[^0-9]"), "")
            .toIntOrNull()

        return HDRezkaSearchCard(
            title = title,
            href = href,
            posterUrl = element.selectFirst("img[src]")?.attr("src").orEmpty(),
            type = type,
            year = yearPattern.find(metadata)?.value?.toIntOrNull(),
            episode = episode
        )
    }

    fun originalTitle(element: Element): String? = element
        .selectFirst("div.b-post__origtitle")
        ?.text()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    fun needsOriginalTitle(query: String): Boolean = query.any { it in 'A'..'Z' || it in 'a'..'z' }

    fun matchingTitle(query: String, resultCount: Int, originalTitle: String?): String? {
        if (!needsOriginalTitle(query)) return null
        return if (resultCount == 1) query.trim() else originalTitle
    }
}

class HDRezkaProvider : MainAPI() {
    override var mainUrl = HDRezkaHttp.BASE_URL
    override var name = "HDRezka"
    override var lang = "ru"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    private fun HDRezkaSearchCard.toSearchResult(titleOverride: String? = null): SearchResponse {
        val responseTitle = titleOverride ?: title
        return if (type == HDRezkaSearchType.MOVIE) {
            newMovieSearchResponse(responseTitle, href, TvType.Movie) {
                this.posterUrl = this@toSearchResult.posterUrl
                this.year = this@toSearchResult.year
            }
        } else {
            newTvSeriesSearchResponse(responseTitle, href, TvType.TvSeries) {
                this.posterUrl = this@toSearchResult.posterUrl
                this.year = this@toSearchResult.year
                this.episodes = episode
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val link = "$mainUrl/search/?do=search&subaction=search&q=$encodedQuery"
        val document = HDRezkaHttp.getDocument(link)

        val cards = document
            .select("div.b-content__inline_items div.b-content__inline_item")
            .mapNotNull(HDRezkaSearchParser::card)
        if (cards.isEmpty()) return emptyList()

        val results = cards.map { it.toSearchResult() }.toMutableList()
        if (HDRezkaSearchParser.needsOriginalTitle(query)) {
            val firstCard = cards.first()
            val originalTitle = if (cards.size > 1) {
                HDRezkaSearchParser.originalTitle(
                    HDRezkaHttp.getDocument(firstCard.href)
                )
            } else {
                null
            }
            val matchingTitle = HDRezkaSearchParser.matchingTitle(
                query,
                cards.size,
                originalTitle
            )
            if (matchingTitle != null && !matchingTitle.equals(firstCard.title, ignoreCase = true)) {
                results.add(0, firstCard.toSearchResult(matchingTitle))
            }
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {

        val document = HDRezkaHttp.getDocument(url)

        val id = url.split("/").last().split("-").first()
        val title = (document.selectFirst("div.b-post__title h1")?.text()?.trim()
            ?: document.selectFirst("div.b-post__origtitle")?.text()?.trim())
            ?.takeIf { it.isNotBlank() }
            ?: error("HDRezka не вернул карточку: доступ к странице ограничен")
        val poster = fixUrlNull(document.selectFirst("div.b-sidecover img")?.attr("src"))
        val year = document.select("div.film-info > div:nth-child(2) a").text().toIntOrNull()
        val tvType = if (document.select("#simple-episodes-tabs")
                .isNullOrEmpty()
        ) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst("div.b-post__description_text")?.text()?.trim()
        val data = HashMap<String, Any>()
        val server = ArrayList<Map<String, String>>()

        data["id"] = id
        data["favs"] = document.selectFirst("input#ctrl_favs")?.attr("value").toString()
        data["ref"] = url

        return if (tvType == TvType.TvSeries) {
            val translators = document.select("ul#translators-list li")
            if (translators.isNotEmpty()) {
                translators.map { res ->
                    server.add(
                        mapOf(
                            "translator_name" to res.text(),
                            "translator_id" to res.attr("data-translator_id"),
                        )
                    )
                }
            } else {
                // Extracts the default translator_id from the init script if translation list is missing
                document.select("script").map { script ->
                    val match = Regex("initCDNSeriesEvents\\(\\d+, (\\d+)").find(script.data())
                    if (match != null) {
                        server.add(
                            mapOf(
                                "translator_name" to "HDrezka",
                                "translator_id" to match.groupValues[1]
                            )
                        )
                    }
                }
            }
            val episodes = document.select(
                    "#simple-episodes-tabs .b-simple_episode__item"
                ).map { ep ->

                    val season = ep.attr("data-season_id").toIntOrNull()
                    val episode = ep.attr("data-episode_id").toIntOrNull()

                    val name = ep.selectFirst(".b-simple_episode__title")
                        ?.text()
                        ?.ifBlank { "Episode $episode" }
                        ?: "Episode $episode"

                    data["season"] = "$season"
                    data["episode"] = "$episode"
                    data["server"] = server
                    data["action"] = "get_stream"

                    newEpisode(mapper.writeValueAsString(data)) {
                        this.name = name
                        this.season = season
                        this.episode = episode
                    }
                }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
            }
        } else {
            document.select("ul#translators-list li").map { res ->
                server.add(
                    mapOf(
                        "translator_name" to res.text(),
                        "translator_id" to res.attr("data-translator_id"),
                        "camrip" to res.attr("data-camrip"),
                        "ads" to res.attr("data-ads"),
                        "director" to res.attr("data-director")
                    )
                )
            }

            data["server"] = server
            data["action"] = "get_movie"

            newMovieLoadResponse(title, url, TvType.Movie, mapper.writeValueAsString(data)) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
            }
        }
    }

    private fun decryptStreamUrl(data: String): String {
        // If the URL is already in plain text (starts with quality marker like [360p]),
        // skip decryption — HDrezka no longer encrypts stream URLs
        if (data.startsWith("[")) return data

        fun getTrash(arr: List<String>, item: Int): List<String> {
            val trash = ArrayList<List<String>>()
            for (i in 1..item) {
                trash.add(arr)
            }
            return trash.reduce { acc, list ->
                val temp = ArrayList<String>()
                acc.forEach { ac ->
                    list.forEach { li ->
                        temp.add(ac.plus(li))
                    }
                }
                return@reduce temp
            }
        }

        val trashList = listOf("@", "#", "!", "^", "$")
        val trashSet = getTrash(trashList, 2) + getTrash(trashList, 3)
        var trashString = data.replace("#h", "").split("//_//").joinToString("")

        trashSet.forEach {
            val temp = base64Encode(it.toByteArray())
            trashString = trashString.replace(temp, "")
        }

        return base64Decode(trashString)

    }

    private suspend fun cleanCallback(
        source: String,
        url: String,
        quality: String,
        isM3u8: Boolean,
        sourceCallback: (ExtractorLink) -> Unit
    ) {
        sourceCallback.invoke(
            newExtractorLink(
                source,
                source,
                url,
                if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "$mainUrl/"
                this.quality = getQuality(quality)
                this.headers = buildMap {
                    putAll(HDRezkaHttp.headers())
                    put("Origin", mainUrl)
                    val cookie = CookieCodec.encode(HDRezkaSession.cookies())
                    if (cookie.isNotBlank()) put("Cookie", cookie)
                }
            }
        )
    }

    private fun getLanguage(str: String): String {
        return when (str) {
            "Русский" -> "Russian"
            "Українська" -> "Ukrainian"
            else -> str
        }
    }

    private fun getQuality(str: String): Int {
        return when (str) {
            "360p" -> Qualities.P360.value
            "480p" -> Qualities.P480.value
            "720p" -> Qualities.P720.value
            "1080p" -> Qualities.P1080.value
            "1080p Ultra" -> Qualities.P1080.value
            else -> getQualityFromName(str)
        }
    }

    private suspend fun invokeSources(
        source: String,
        url: String,
        subtitle: String,
        subCallback: (SubtitleFile) -> Unit,
        sourceCallback: (ExtractorLink) -> Unit
    ) {
        decryptStreamUrl(url).split(",").map { links ->
            val quality =
                Regex("\\[([0-9]{3,4}p\\s?\\w*?)]").find(links)?.groupValues?.getOrNull(1)
                    ?.trim() ?: return@map null
            links.replace("[$quality]", "").split(" or ")
                .map {
                    val link = it.trim()
                    val type = if(link.contains(".m3u8")) "(Main)" else "(Backup)"
                    cleanCallback(
                        "$source $type",
                        link,
                        quality,
                        link.contains(".m3u8"),
                        sourceCallback,
                    )
                }
        }

        subtitle.split(",").map { sub ->
            val language =
                Regex("\\[(.*)]").find(sub)?.groupValues?.getOrNull(1) ?: return@map null
            val link = sub.replace("[$language]", "").trim()
            subCallback.invoke(
                newSubtitleFile(
                    getLanguage(language),
                    link
                )
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        mapper.readValue(data, Data::class.java)?.let { res ->
            if (res.server?.isEmpty() == true) {
                val document = HDRezkaHttp.getDocument(res.ref ?: return@let)
                document.select("script").map { script ->
                    if (script.data().contains("sof.tv.initCDNMoviesEvents(")) {
                        val dataJson =
                            script.data().substringAfter("false, {").substringBefore("});")
                        mapper.readValue("{$dataJson}", LocalSources::class.java)?.let { source ->
                            invokeSources(
                                this.name,
                                source.streams,
                                source.subtitle.toString(),
                                subtitleCallback,
                                callback
                            )
                        }
                    }
                }
            } else {
                res.server?.map { server ->
                    val response = app.post(
                        url = "$mainUrl/ajax/get_cdn_series/?t=${Date().time}",
                        data = mapOf(
                            "id" to res.id,
                            "translator_id" to server.translator_id,
                            "favs" to res.favs,
                            "is_camrip" to server.camrip,
                            "is_ads" to server.ads,
                            "is_director" to server.director,
                            "season" to res.season,
                            "episode" to res.episode,
                            "action" to res.action,
                        ).filterValues { it != null }.mapValues { it.value as String },
                        headers = HDRezkaHttp.headers() + mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Origin" to mainUrl
                        ),
                        referer = res.ref,
                        cookies = HDRezkaSession.cookies()
                    )
                    mapper.readValue(response.text, Sources::class.java)?.let { source ->
                        invokeSources(
                            server.translator_name.toString(),
                            source.url,
                            source.subtitle.toString(),
                            subtitleCallback,
                            callback
                        )
                    }
                }
            }
        }

        return true
    }

    data class LocalSources(
        @JsonProperty("streams") val streams: String,
        @JsonProperty("subtitle") val subtitle: Any?,
    )

    data class Sources(
        @JsonProperty("url") val url: String,
        @JsonProperty("subtitle") val subtitle: Any?,
    )

    data class Server(
        @JsonProperty("translator_name") val translator_name: String?,
        @JsonProperty("translator_id") val translator_id: String?,
        @JsonProperty("camrip") val camrip: String?,
        @JsonProperty("ads") val ads: String?,
        @JsonProperty("director") val director: String?,
    )

    data class Data(
        @JsonProperty("id") val id: String?,
        @JsonProperty("favs") val favs: String?,
        @JsonProperty("server") val server: List<Server>?,
        @JsonProperty("season") val season: String?,
        @JsonProperty("episode") val episode: String?,
        @JsonProperty("action") val action: String?,
        @JsonProperty("ref") val ref: String?,
    )

}
