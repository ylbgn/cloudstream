package com.nikyokki

import android.util.Log
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toRatingInt
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class HDFilmCehennemi2 : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi2.nl"
    override var name = "HDFilmCehennemi2"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/tur/aile-filmleri/" to "Aile",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}/page/${page}").document
        val home = document.select("div.poster").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse {
        val title = this.selectFirst("div.poster-title h2")?.text()?.replace(" izle", "") ?: ""
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: ""
        val posterUrl = fixUrlNull(this.selectFirst("div.poster-image img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    private fun toSearchRes(movie: Movie): SearchResponse {
        val title = movie.title ?: ""
        val posterUrl = fixUrlNull("$mainUrl/uploads/poster/${movie.poster}")
        if (movie.type == "1") {
            val href = fixUrlNull("$mainUrl/${movie.slug}") ?: ""
            return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        } else {
            val href = fixUrlNull("$mainUrl/${movie.slugPrefix}/${movie.slug}") ?: ""
            return newMovieSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.post(
            "${mainUrl}/search",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            referer = "${mainUrl}/",
            data = mapOf("query" to query)
        ).document.body().text()

        val result = mutableListOf<SearchResponse>()

        val json = ObjectMapper().readValue(response, HDSearchResponse::class.java)
        json.result.forEach {
            result.add(toSearchRes(it))
        }

        return result
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.title a")?.text() ?: return null
        val href = fixUrlNull(this.selectFirst("div.title a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val orgTitle =
            document.selectFirst("div.card-header h1")?.text()?.substringAfter(" izle", "")?.trim()
                ?: ""
        val altTitle =
            document.selectFirst("div.pb-2")?.text()?.replace("Orijinal Adı:", "")?.trim() ?: ""
        val title =
            if (altTitle.isNotEmpty() && orgTitle != altTitle) "$orgTitle - $altTitle" else orgTitle
        val poster = fixUrlNull(document.selectFirst("pictur.poster-auto img")?.attr("data-src"))
        val description = document.selectFirst("article.text-white p")?.text()?.trim()
        var year = document.selectFirst("div.release a")?.text()?.trim()?.toIntOrNull()
        val tags = document.select("div#listelements a").map { it.text() }
        val rating = document.selectFirst("div.rate")?.text().toRatingInt()
        val actors = mutableListOf<Actor>()
        val trailer =
            document.select("div.card-body li").last()?.selectFirst("div")?.attr("data-trailer")
        val listItems = document.select("tbody tr").select("div")
        var duration = 0
        for (item in listItems) {
            if (item.selectFirst("small")?.text()?.contains("Yıl") == true) {
                year = item.selectFirst("a")?.text()?.toIntOrNull()
            }
            if (item.selectFirst("small")?.text()?.contains("Süre") == true) {
                duration =
                    item.selectFirst("strong")?.text()?.replace(" dakika", "")?.toIntOrNull()!!
            }
        }
        document.select("a.story-item").forEach {
            val img = fixUrlNull(it.selectFirst("img")?.attr("data-src"))
            val name = it.select("div.story-item-title").text()
            actors.add(Actor(name = name, image = img))
        }
        val recommendations =
            document.select("div.glide__slide div").mapNotNull { it.toRecommendationResult() }

        if (url.contains("/dizi/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.rating = rating
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val episodes = mutableListOf<Episode>()
            document.select("div.seasonsTabs-tabContent div").forEach { szn ->
                val epSzn = szn.attr("id").substringAfter("seasons-").toIntOrNull()
                szn.select("div.card-list-item").forEach {
                    val epHref = it.selectFirst("a")?.attr("href") ?: ""
                    val epName = it.selectFirst("h3")?.text()
                    val epnum =
                        epName?.substringAfter("Sezon ")?.substringBefore(". Bölüm")?.toIntOrNull()
                    episodes.add(
                        Episode(
                            data = epHref,
                            name = epName,
                            season = epSzn,
                            episode = epnum
                        )
                    )
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.rating = rating
                this.duration = duration
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse {
        val title = this.selectFirst("h2")?.text() ?: ""
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: ""
        val posterUrl = fixUrlNull(this.selectFirst("picture img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("HDC", "data » $data")
        val document = app.get(data).document
        if(document.select("div.tab-content div").size > 1) {
            document.select("div.tab-content div").forEach { it ->
                it.select("a").forEach { el ->
                    val url = el.attr("href")
                    Log.d("HDC", "url » $url")
                    val doc = app.get(url).document
                    val iframe = doc.selectFirst("iframe")?.attr("src") ?: ""
                    Log.d("HDC", "iframe » $iframe")
                    loadExtractor(iframe, url, subtitleCallback, callback)
                }
            }
        } else {
            val iframe = document.selectFirst("iframe")?.attr("src") ?: ""
            Log.d("HDC", "iframe » $iframe")
            loadExtractor(iframe, data, subtitleCallback, callback)
        }
        return true
    }
}