package com.nikyokki

import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.extractors.JWPlayer
import org.jsoup.Jsoup

class TvDiziler : MainAPI() {
    override var mainUrl              = "https://tvdiziler.cc"
    override var name                 = "TvDiziler"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/dizi/tur/aile/"      to "Aile",

    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val mainReq = app.get("${request.data}/${page}")

        val document = mainReq.document.body()
        //val document = Jsoup.parse(mainReq.body.string())
        val home = document.select("div.poster-long").mapNotNull { it.diziler() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.diziler(): SearchResponse? {
        val title =
            this.selectFirst("div.poster-long-subject h2")?.text() ?: return null
        val href =
            fixUrlNull(this.selectFirst("div.poster-long-subject a")?.attr("href"))
                ?: return null
        val posterUrl =
            fixUrlNull(this.selectFirst("div.poster-long-image img")?.attr("data-src"))

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    private fun Element.toPostSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3.truncate")?.text()?.trim() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        if (href.contains("/dizi/")) {
            return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            return newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchReq = app.post(
            "${mainUrl}/search?qr=$query",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0",
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest",
            ),
            referer = "${mainUrl}/"
        )

        val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        val searchResult: SearchResult = objectMapper.readValue(searchReq.toString())

        if (searchResult.success != 1) {
            throw ErrorLoadingException("Invalid Json response")
        }

        val searchDoc = searchResult.data

        val document = Jsoup.parse(searchDoc.toString())
        val results = mutableListOf<SearchResponse>()

        document.select("ul li").forEach { listItem ->
            val href = listItem.selectFirst("a")?.attr("href")
            if (href != null && (href.contains("/dizi/") || href.contains("/film/"))) {
                val result = listItem.toPostSearchResult()
                result?.let { results.add(it) }
            }
        }
        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        Log.d("TVD", "Load -> url")
        val mainReq = app.get(url, referer = mainUrl)
        val document = mainReq.document
        val title = document.selectFirst("div.page-title h1")?.selectFirst("a")?.text() ?: return null
        Log.d("TVD", "title -> $title")
        val poster =
            fixUrlNull(document.selectFirst("div.series-profile-image img")?.attr("src"))
        Log.d("TVD", "poster -> $poster")
        val year =
            document.selectFirst("h1 span")?.text()?.substringAfter("(")?.substringBefore(")")
                ?.toIntOrNull()
        Log.d("TVD", "year -> $year")

        val rating = document.selectFirst("//span[text()='IMDb Puanı']//following-sibling::p")?.text()?.trim()?.toRatingInt()
        Log.d("TVD", "rating -> $rating")

        val duration =
            document.selectXpath("//span[text()='Süre']//following-sibling::p").text().trim()
                .split(" ").first().toIntOrNull()
        Log.d("TVD", "duration -> $duration")

        val description = document.selectFirst("div.series-profile-summary p")?.text()?.trim()
        Log.d("TVD", "description -> $description")

        val tags = document.selectFirst("div.series-profile-type")?.select("a")
            ?.mapNotNull { it.text().trim() }
        Log.d("TVD", "tags -> $tags")
        val trailer = document.selectFirst("div.series-profile-trailer")?.attr("data-yt")
        val actors = mutableListOf<Actor>()
        document.select("div.series-profile-cast li").forEach {
            val img = fixUrlNull(it.selectFirst("img")?.attr("data-src"))
            val name = it.selectFirst("h5.truncate")?.text()?.trim() ?: return null
            actors.add(Actor(name, img))
        }
        if (url.contains("/dizi/")) {
            val episodeses = mutableListOf<Episode>()
            var szn = 1
            for (sezon in document.select("div.series-profile-episode-list")) {
                var blm = 1
                for (bolum in sezon.select("li")) {
                    val epName = bolum.selectFirst("h6.truncate a")?.text() ?: continue
                    val epHref = fixUrlNull(bolum.select("h6.truncate a").attr("href")) ?: continue
                    val epEpisode = blm++
                    val epSeason = szn
                    episodeses.add(
                        newEpisode(epHref) {
                            this.name = epName
                            this.season = epSeason
                            this.episode = epEpisode
                        })
                }
                szn++
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeses) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                addActors(actors)
                addTrailer("https://www.youtube.com/embed/${trailer}")
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.duration = duration
                addActors(actors)
                addTrailer("https://www.youtube.com/embed/${trailer}")
            }
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val title     = this.selectFirst("a img")?.attr("alt") ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("a img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to "$mainUrl/"
        )
        val aa = app.get(mainUrl)
        val ciSession = aa.cookies["ci_session"].toString()
        val document = app.get(
            data, headers = headers, cookies = mapOf(
                "ci_session" to ciSession
            )
        ).document
        val iframe =
            fixUrlNull(document.selectFirst("div#tv-spoox2 iframe")?.attr("src")) ?: return false
        Log.d("TVD", "Iframe -> $iframe")
        loadExtractor(iframe, referer = "$mainUrl/", subtitleCallback, callback)


        return true
    }
}
class TvDizilerOynat : JWPlayer() {
    override val name = "TvDizilerOynat"
    override val mainUrl = "https://tvdiziler.cc/player/oynat/"
}