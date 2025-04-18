// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer


class FilmMakinesi : MainAPI() {
    override var mainUrl              = "https://filmmakinesi.de"
    override var name                 = "FilmMakinesi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    // ! CloudFlare bypass
    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 50L
    override var sequentialMainPageScrollDelay = 50L

    override val mainPage = mainPageOf(
        "${mainUrl}/filmler/sayfa/"                                to "Son Filmler",
        "${mainUrl}/film-izle/olmeden-izlenmesi-gerekenler/sayfa/" to "Ölmeden İzle",
        "${mainUrl}/tur/aksiyon/film/sayfa/"                       to "Aksiyon",
        "${mainUrl}/tur/bilim-kurgu/film/sayfa/"                   to "Bilim Kurgu",
        "${mainUrl}/tur/macera/film/sayfa/"                        to "Macera",
        "${mainUrl}/tur/komedi/film/sayfa/"                        to "Komedi",
        "${mainUrl}/tur/romantik/film/sayfa/"                      to "Romantik",
        "${mainUrl}/tur/belgesel/film/sayfa/"                      to "Belgesel",
        "${mainUrl}/tur/fantastik/film/sayfa/"                     to "Fantastik",
        "${mainUrl}/tur/polisiye/film/sayfa/"                      to "Polisiye Suç",
        "${mainUrl}/tur/korku/film/sayfa/"                         to "Korku",
        "${mainUrl}/tur/animasyon/film/sayfa/"                     to "Animasyon",
        "${mainUrl}/tur/gizem/film/sayfa/"                         to "Gizem",
        "${mainUrl}/kanal/netflix/sayfa/"                          to "Netflix",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}").document

        // Doğru film kartı seçimi ve bilgi çekme
        val home = document.select("div.col-6.col-md-4.col-xl-2").mapNotNull { col ->
            val link = col.selectFirst("a.item") ?: return@mapNotNull null
            val title = link.attr("data-title").ifBlank { link.attr("title") }
            if (title.isBlank()) return@mapNotNull null
            val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
            val posterUrl = fixUrlNull(link.selectFirst("img.thumbnail")?.attr("src"))
            val year = col.selectFirst("div.item-footer div.info > span")?.text()?.toIntOrNull()
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }

        Log.d("FilmMakinesi", "URL: ${request.data}${page}, Found films: ${home.size}")

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document
        return document.select("div.col-6.col-md-4.col-xl-2").mapNotNull { col ->
            val link = col.selectFirst("a.item") ?: return@mapNotNull null
            val title = link.attr("data-title").ifBlank { link.attr("title") }
            if (title.isBlank()) return@mapNotNull null
            val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
            val posterUrl = fixUrlNull(link.selectFirst("img.thumbnail")?.attr("src"))
            val year = col.selectFirst("div.item-footer div.info > span")?.text()?.toIntOrNull()
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title = this.selectFirst("a")?.text() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        try {
            // Başlık ve poster seçicileri güncel
            val title = document.selectFirst("div.film-bilgileri h1")?.text()?.trim() ?: return null
            val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
            val description = document.selectFirst("div.film-bilgileri div.ozet")?.text()?.trim()

            // Türler, puan, yıl, süre seçicileri güncel
            val tags = document.select("div.film-bilgileri div.tur a").map { it.text().trim() }
            val ratingText = document.selectFirst("div.film-bilgileri div.imdb-puani")?.text() ?: ""
            val rating = ratingText.replace("IMDB:", "").trim().toRatingInt()
            val yearText = document.selectFirst("div.film-bilgileri div.yapim-yili")?.text() ?: ""
            val year = yearText.replace("Yapım Yılı:", "").trim().toIntOrNull()
            val durationText = document.selectFirst("div.film-bilgileri div.sure")?.text() ?: ""
            val durationStr = durationText.replace("Süre:", "").trim()
            val duration = durationStr.split(" ").firstOrNull()?.toIntOrNull() ?: 0

            // Benzer filmler ve oyuncular seçicileri güncel
            val recommendations = document.select("div.benzer-filmler div.film-kutusu").mapNotNull { 
                it.toRecommendResult() 
            }
            val actors = document.select("div.film-bilgileri div.oyuncular a").map {
                Actor(it.text().trim())
            }

            // Fragman seçicisi güncel
            val trailer = fixUrlNull(document.selectFirst("div.fragman iframe")?.attr("src"))

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } catch (e: Exception) {
            Log.e("FilmMakinesi", "Error loading movie: ${e.message}")
            return null
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val document = app.get(data).document
            // Video iframe seçicisi güncel
            val iframe = document.selectFirst("div.player-area iframe")?.attr("src") ?: ""

            if (iframe.isNotBlank()) {
                loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
            }

            // Alternatif kaynaklar için seçici güncel
            document.select("div.player-tabs a").forEach { tab ->
                val tabUrl = fixUrlNull(tab.attr("href")) ?: return@forEach
                val tabDoc = app.get(tabUrl).document
                val tabIframe = tabDoc.selectFirst("div.player-area iframe")?.attr("src") ?: return@forEach

                if (tabIframe.isNotBlank()) {
                    loadExtractor(tabIframe, "${mainUrl}/", subtitleCallback, callback)
                }
            }

            return true
        } catch (e: Exception) {
            Log.e("FilmMakinesi", "Error loading links: ${e.message}")
            return false
        }
    }
}
