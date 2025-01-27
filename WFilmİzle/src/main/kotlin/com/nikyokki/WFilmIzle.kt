// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.nikyokki

import android.util.Log
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
import com.lagradost.cloudstream3.toRatingInt
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class WFilmIzle : MainAPI() {
    override var mainUrl = "https://www.wfilmizle.my/"
    override var name = "WFilmİzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/tur/aile-filmleri-izle-hd/"     to "Aile",
        /*"${mainUrl}/tur/aksiyon-filmleri/"          to "Aksiyon",
        "${mainUrl}/tur/animasyon-filmleri/"        to "Animasyon",
        "${mainUrl}/tur/belgesel/"                  to "Belgesel",
        "${mainUrl}/tur/bilim-kurgu-filmleri/"      to "Bilim Kurgu",
        "${mainUrl}/tur/biyografi/"                 to "Biyografi",
        "${mainUrl}/tur/dram/"                      to "Dram",
        "${mainUrl}/tur/editor/"                    to "Editör",
        "${mainUrl}/tur/fantastik-filmler/"         to "Fantastik",
        "${mainUrl}/tur/genclik/"                   to "Gençlik",
        "${mainUrl}/tur/genel/"                     to "Genel",
        "${mainUrl}/tur/gerilim/"                   to "Gerilim",
        "${mainUrl}/tur/gizem-filmleri/"            to "Gizem",
        "${mainUrl}/tur/komedi-filmleri/"           to "Komedi",
        "${mainUrl}/tur/korku/"                     to "Korku",
        "${mainUrl}/tur/macera-filmleri/"           to "Macera",
        "${mainUrl}/tur/muzik/"                     to "Müzik",
        "${mainUrl}/tur/muzikal/"                   to "Müzikal",
        "${mainUrl}/tur/polisiye-filmler/"          to "Polisiye",
        "${mainUrl}/tur/romantik/"                  to "Romantik",
        "${mainUrl}/tur/savas-filmleri/"            to "Savaş",
        "${mainUrl}/tur/spor/"                      to "Spor",
        "${mainUrl}/tur/suc/"                       to "Suç",
        "${mainUrl}/tur/tarih/"                     to "Tarih",
        "${mainUrl}/tur/western-kovboy-filmleri/"   to "Western & Kovboy",
        "${mainUrl}/tur/yerli-film-izle/"           to "Yerli"*/
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}/page/${page}").document
        val home = document.select("div.movie-poster").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title     = this.selectFirst("span.movie-title")?.text() ?: ""
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: ""
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document

        return document.select("div.movie-poster").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val orgTitle = document.selectFirst("div.title h1")?.text()?.replace(" izle","")?.trim() ?: ""
        val altTitle = document.selectFirst("div.diger_adi h2")?.text()?.trim() ?: ""
        val title = if (altTitle.isNotEmpty()) "${orgTitle} - ${altTitle}" else orgTitle
        val poster = fixUrlNull(document.selectFirst("div.poster img")?.attr("src"))
        val description = document.selectFirst("div.excerpt p")?.text()?.trim()
        val year =
            document.selectFirst("div.release a")?.text()?.trim()?.toIntOrNull()
        val tags = document.select("div.categories a").map { it.text() }
        val rating =
            document.selectFirst("div.imdb")?.text()?.split("/")?.first()?.trim()
                ?.toRatingInt()
        val actors = document.select("div.actor a").map { it.text() }
        val trailer = document.selectFirst("div.container iframe")?.attr("src")

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("4KI", "data » ${data}")
        val document = app.get(data).document
        var iframe   = fixUrlNull(document.selectFirst("div.vast iframe")?.attr("src")) ?: return false
        Log.d("4KI", "iframe » ${iframe}")

        loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
        return true
    }
}