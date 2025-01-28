// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.nikyokki

import android.util.Log
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.Actor
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
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class FilmIzlesene : MainAPI() {
    override var mainUrl = "https://www.filmizlesene.pro"
    override var name = "Filmİzlesene"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/kategori/turler/aile-filmleri-izle-1"     to "Aile",
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
        val home = document.select("div.movie-box").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse {
        val title     = this.selectFirst("div.film-ismi a")?.text() ?: ""
        Log.d("FIS", "Title: $title")
        val href      = fixUrlNull(this.selectFirst("div.film-ismi a")?.attr("href")) ?: ""
        Log.d("FIS", "Href: $href")
        val posterUrl = fixUrlNull(this.selectFirst("div.poster img")?.attr("data-src"))
        Log.d("FIS", "Poster: $posterUrl")

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document

        return document.select("div.movie-box").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        Log.d("FIS", "LoadURL: $url")
        val document = app.get(url).document

        val orgTitle = document.selectFirst("div.title-border h1")?.text()?.trim() ?: ""
        Log.d("FIS", "OrgTitle: $orgTitle")
        val altTitle = document.selectFirst("div.bolum-ismi")?.text()?.trim() ?: ""
        Log.d("FIS", "altTitle: $altTitle")
        val title = if (altTitle.isNotEmpty()) "$orgTitle - $altTitle" else orgTitle
        Log.d("FIS", "title: $title")
        val poster = fixUrlNull(document.selectFirst("div.film-afis img")?.attr("src"))
        Log.d("FIS", "poster: $poster")
        val description = document.selectFirst("div#film-aciklama")?.text()?.trim()
        Log.d("FIS", "description: $description")

        var year =
            document.selectFirst("div.release a")?.text()?.trim()?.toIntOrNull()
        Log.d("FIS", "year: $year")
        val tags = document.select("div.categories a").map { it.text() }
        Log.d("FIS", "tags: $tags")
        var rating =
            document.selectFirst("div.imdb")?.text()?.replace("IMDb Puanı:","")
                ?.split("/")?.first()?.trim()?.toRatingInt()
        Log.d("FIS", "rating: " + document.selectFirst("div.imdb").toString())
        var actors = document.select("div.actor a").map { it.text() }
        Log.d("FIS", "actors: $actors")
        val trailer = document.selectFirst("div.container iframe")?.attr("src")
        Log.d("FIS", "trailer: $trailer")

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("FIS", "data » $data")
        val document = app.get(data).document

        document.select("div.sources script").forEach {
            if (it.toString().contains("#source")) {
                val url = it.toString().substringAfter("<iframe src=\"").substringBefore("\"")
                val doci = app.get(url,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to " en-US,en;q=0.5",
                    ), referer = "https://www.filmizlesene.pro/")
                var iframe = doci.document.select("iframe").attr("src")
                if (iframe.contains("/vidmo/")) {
                    val doci2 = app.get(iframe,
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0",
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                            "Accept-Language" to " en-US,en;q=0.5",
                        ), referer = "https://www.filmizlesene.pro/")
                    var iframe2 = doci2.document.select("iframe").attr("src")
                    loadExtractor(iframe2, iframe, subtitleCallback, callback)
                } else if (iframe.contains("/hdplayer/drive/")) {

                } else {
                    loadExtractor(iframe, url, subtitleCallback, callback)
                }
            }
        }

        return true
    }

}