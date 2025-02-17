package com.nikyokki

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class FilmKovasi : MainAPI() {
    override var mainUrl              = "https://filmkovasi.tv"
    override var name                 = "FilmKovası"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/filmizle/aile/"           to "Aile",
        "${mainUrl}/filmizle/aksiyon-hd/"     to "Aksiyon",
        "${mainUrl}/filmizle/animasyon/"      to "Animasyon",
        "${mainUrl}/filmizle/belgesel-hd/"    to "Belgesel",
        "${mainUrl}/filmizle/bilim-kurgu/"    to "Bilim Kurgu",
        "${mainUrl}/filmizle/dram-hd/"        to "Dram",
        "${mainUrl}/filmizle/fantastik-hd/"   to "Fantastik",
        "${mainUrl}/filmizle/gerilim/"        to "Gerilim",
        "${mainUrl}/filmizle/gizem/"          to "Gizem",
        "${mainUrl}/filmizle/komedi-hd/"      to "Komedi",
        "${mainUrl}/filmizle/korku/"          to "Korku",
        "${mainUrl}/filmizle/macera-hd/"      to "Macera",
        "${mainUrl}/filmizle/romantik-hd/"    to "Romantik",
        "${mainUrl}/filmizle/savas-hd/"       to "Savaş",
        "${mainUrl}/filmizle/suc-hd/"         to "Suç",
        "${mainUrl}/filmizle/tarih/"          to "Tarih",
        "${mainUrl}/filmizle/vahsi-bati-hd/"  to "Vahşi Batı",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}page/$page").document
        val home = document.select("div.movie-box").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title = this.selectFirst("div.film-ismi a")?.text()?.replace(" izle", "") ?: ""
        val href = fixUrlNull(this.selectFirst("div.film-ismi a")?.attr("href")) ?: ""
        val posterUrl = fixUrlNull(this.selectFirst("div.poster img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document

        return document.select("div.movie-box").mapNotNull { it.toMainPageResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("div.title a")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("div.title a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.title-border")?.text()?.replace(" izle", "")?.trim() ?: ""
        val poster = fixUrlNull(document.selectFirst("div.film-afis img")?.attr("src"))
        val description = document.selectFirst("div#film-aciklama")?.text()?.trim()
        var year = document.selectFirst("div.release a")?.text()?.trim()?.toIntOrNull()
        val tags = document.select("div#listelements a").map { it.text() }
        var rating = document.selectFirst("div.imdb")?.text()?.replace("IMDb Puanı:", "")
            ?.split("/")?.first()?.trim()?.toRatingInt()
        var actors = document.select("div.actor a").map { it.text() }
        val trailer = document.selectFirst("div.film-afis iframe")?.attr("src")
        val listItems = document.select("div.list-item")
        for (item in listItems) {
            if (item.selectFirst("a")?.attr("href")?.contains("/yil/") == true) {
                year = item.selectFirst("a")?.text()?.toIntOrNull()
            }
            if (item.selectFirst("a")?.attr("href")?.contains("/oyuncu/") == true) {
                actors = item.select("a").map { it.text() }
            }
        }
        document.select("div#listelements div").forEach {
            if (it.text().contains("IMDb:")) {
                rating = it.text().trim().split(" ").last().toRatingInt()
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            this.rating = rating
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val title     = this.selectFirst("a img")?.attr("alt") ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("a img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("FKV", "data » ${data}")
        val document = app.get(data).document
        val iframe = document.selectFirst("iframe")?.attr("src") ?: ""
        Log.d("FKV", iframe)
        loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)

        if (document.select("div.sources a").size > 0) {
            document.select("div.soruces a").forEach {
                val doc = app.get(it.attr("href")).document
                val iffi = doc.selectFirst("iframe")?.attr("src") ?: ""
                Log.d("FKV", iffi)
                loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
            }
        }
        return true
    }
}