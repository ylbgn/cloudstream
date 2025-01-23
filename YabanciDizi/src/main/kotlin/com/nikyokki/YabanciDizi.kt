package com.nikyokki

import CryptoJS
import android.util.Log
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
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
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toRatingInt
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.regex.Pattern

class YabanciDizi : MainAPI() {
    override var mainUrl = "https://yabancidizi.tv"
    override var name = "YabanciDizi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries)

    // ! CloudFlare bypass
    override var sequentialMainPage =
        true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    override var sequentialMainPageDelay = 250L  // ? 0.05 saniye
    override var sequentialMainPageScrollDelay = 250L  // ? 0.05 saniye

    override val mainPage = mainPageOf(
        "${mainUrl}/dizi/tur/aile-izle"             to "Aile",
        "${mainUrl}/dizi/tur/aksiyon-izle-1"        to "Aksiyon",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}/${page}").document
        val home     = document.select("div.mofy-movbox").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("div.mofy-movbox-text a")?.text()?.trim() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    private fun Element.toPostSearchResult(): SearchResponse? {
        val title       = this.selectFirst("h2")?.text()?.trim() ?: return null
        val href        = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl   = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.post(
            "${mainUrl}/search",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            referer = "${mainUrl}/",
            data    = mapOf("query" to query)
        ).parsedSafe<SearchResult>()!!.theme

        val document = Jsoup.parse(response)
        val results  = mutableListOf<SearchResponse>()

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
        val document = app.get(url).document

        val title       = document.selectFirst("h1 a")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.series-profile-image img")?.attr("src")) ?: return null
        val year        = document.selectFirst("h1 span")?.text()?.substringAfter("(")?.substringBefore(")")?.toIntOrNull()
        val description = document.selectFirst("div.series-profile-summary p")?.text()?.trim()
        val tags        = document.select("div.series-profile-type a").mapNotNull { it?.text()?.trim() }
        val rating      = document.selectFirst("span.color-imdb")?.text()?.trim()?.toRatingInt()
        val duration    = document.selectXpath("//span[text()='Süre']//following-sibling::p").text().trim().split(" ").first().toIntOrNull()
        val trailer     = document.selectFirst("div.series-profile-trailer")?.attr("data-yt")
        val actors      = document.select("div.series-profile-cast li").map {
            Actor(it.selectFirst("h5")!!.text(), it.selectFirst("img")!!.attr("data-src"))
        }

        if (url.contains("/dizi/")) {
            val episodes    = mutableListOf<Episode>()
            document.select("div.series-profile-episode-list").forEach {
                val epSeason = it.parent()!!.id().split("-").last().toIntOrNull()

                it.select("li").forEach ep@ { episodeElement ->
                    val epHref    = fixUrlNull(episodeElement.selectFirst("h6 a")?.attr("href")) ?: return@ep
                    val epEpisode = episodeElement.selectFirst("a.truncate data")?.text()?.trim()?.toIntOrNull()

                    episodes.add(Episode(
                        data    = epHref,
                        name    = "${epSeason}. Sezon ${epEpisode}. Bölüm",
                        season  = epSeason,
                        episode = epEpisode
                    ))
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.rating    = rating
                this.duration  = duration
                addActors(actors)
                addTrailer("https://www.youtube.com/embed/${trailer}")
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.rating    = rating
                this.duration  = duration
                addActors(actors)
                addTrailer("https://www.youtube.com/embed/${trailer}")
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("YBD", "data » ${data}")
        val document = app.get(data).document

        document.select("div.alternatives-for-this div").forEach {
            val name = it.text()
            Log.d("YBD", name)
            val dataHash = it.attr("data-hash")
            val dataLink = it.attr("data-link")
            Log.d("YBD", dataLink)
            val dataQuery = it.attr("data-querytype")
            if (name.contains("Mac")) {
                val mac           = app.post("https://yabancidizi.tv/api/drive/" +
                                        dataLink.replace("/", "_").replace("+", "-"),
                                        referer = "$mainUrl/").document
                val subFrame      = mac.selectFirst("iframe")?.attr("src") ?: return false
                val iDoc          = app.get(subFrame, referer="${mainUrl}/",
                                        headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0")).text
                val cryptData     = Regex("""CryptoJS\.AES\.decrypt\(\"(.*)\",\"""").find(iDoc)?.groupValues?.get(1) ?: return false
                val cryptPass     = Regex("""\",\"(.*)\"\);""").find(iDoc)?.groupValues?.get(1) ?: return false
                val decryptedData = CryptoJS.decrypt(cryptPass, cryptData)
                val decryptedDoc  = Jsoup.parse(decryptedData)
                val vidUrl        = Regex("""file: \'(.*)',""").find(decryptedDoc.html())?.groupValues?.get(1) ?: return false
                Log.d("YBD", vidUrl)
                callback.invoke(
                    ExtractorLink(
                        source  = it.text(),
                        name    = it.text(),
                        url     = vidUrl,
                        referer = "$mainUrl/",
                        headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0"),
                        quality = getQualityFromName("4k"),
                        isM3u8  = true
                    )
                )
            } else if (name.contains("VidMoly")) {
                val mac         = app.post("https://yabancidizi.tv/api/moly/" +
                                        dataLink.replace("/", "_").replace("+", "-"), referer = "$mainUrl/").document
                val subFrame    = mac.selectFirst("iframe")?.attr("src") ?: return false
                Log.d("YBD", subFrame)
                loadExtractor(subFrame, "${mainUrl}/", subtitleCallback, callback)
            } else if (name.contains("Okru")) {
                val mac         = app.post("https://yabancidizi.tv/api/ruplay/" +
                                    dataLink.replace("/", "_").replace("+", "-"), referer = "$mainUrl/").document
                val subFrame    = mac.selectFirst("iframe")?.attr("src") ?: return false
                Log.d("YBD", subFrame)
                loadExtractor(subFrame, "${mainUrl}/", subtitleCallback, callback)
            }
        }
        return true
    }
}
