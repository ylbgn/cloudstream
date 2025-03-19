// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toRatingInt
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Dizilla : MainAPI() {
    override var mainUrl = "https://dizilla.nl"
    override var name = "Dizilla"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)


    override val mainPage = mainPageOf(
        //"${mainUrl}/tum-bolumler" to "Altyazılı Bölümler",
        "${mainUrl}/arsiv" to "Yeni Eklenen Diziler",
        "${mainUrl}/dizi-turu/aile" to "Aile",
        "${mainUrl}/dizi-turu/aksiyon" to "Aksiyon",
        "${mainUrl}/dizi-turu/bilim-kurgu" to "Bilim Kurgu",
        "${mainUrl}/dizi-turu/romantik" to "Romantik",
        "${mainUrl}/dizi-turu/komedi" to "Komedi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = if (request.data.contains("dizi-turu")) {
            document.select("span.watchlistitem-").mapNotNull { it.diziler() }
        } else if (request.data.contains("/arsiv")) {
            document.select("a.w-full").mapNotNull { it.yeniEklenenler() }
        } else {
            document.select("div.col-span-3 a").mapNotNull { it.sonBolumler() }
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.diziler(): SearchResponse {
        val title = this.selectFirst("span.font-normal")?.text() ?: "return null"
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: "return null"
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.yeniEklenenler(): SearchResponse {
        val title = this.selectFirst("h2")?.text() ?: "return null"
        val href = fixUrlNull(this.attr("href")) ?: "return null"
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }
    private suspend fun Element.sonBolumler(): SearchResponse {
        Log.d("DZL", "this $this")
        val name = this.selectFirst("h2")?.text() ?: ""
        Log.d("DZL", "bolum name $name")
        val epName = this.selectFirst("div.opacity-80")!!.text().replace(". Sezon ", "x")
            .replace(". Bölüm", "")
        Log.d("DZL", "bolum epName $epName")

        val title = "$name - $epName"

        val epDoc = fixUrlNull(this.attr("href"))?.let { app.get(it).document }

        val href = fixUrlNull(epDoc?.selectFirst("div.poster a")?.attr("href")) ?: "return null"
        Log.d("DZL", "bolum href $href")

        val posterUrl = fixUrlNull(epDoc?.selectFirst("div.poster img")?.attr("src"))
        Log.d("DZL", "bolum posterUrl $posterUrl")

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun SearchItem.toSearchResponse(): SearchResponse? {
        return newTvSeriesSearchResponse(
            title ?: return null,
            "${mainUrl}/${slug}",
            TvType.TvSeries,
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val mainReq = app.get(mainUrl)
        val mainPage = mainReq.document
        val cKey = mainPage.selectFirst("input[name='cKey']")?.attr("value") ?: return emptyList()
        val cValue =
            mainPage.selectFirst("input[name='cValue']")?.attr("value") ?: return emptyList()
        val cookie = mainReq.cookies["PHPSESSID"].toString()

        val veriler = mutableListOf<SearchResponse>()

        val searchReq = app.post(
            "${mainUrl}/api/bg/searchcontent?searchterm=$query",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
                "Accept" to "application/json, text/plain, */*",
                "X-Requested-With" to "XMLHttpRequest",
            ),
            referer = "${mainUrl}/",

        )
        Log.d("DZL", "Search: $searchReq")
        val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val searchResult: SearchResult = objectMapper.readValue(searchReq.toString())
        val decodedSearch = base64Decode(searchResult.response.toString())
        val contentJson: SearchData = objectMapper.readValue(decodedSearch)
        if (contentJson.state != true) {
            throw ErrorLoadingException("Invalid Json response")
        }
        contentJson.result?.forEach {
            val name = it.title.toString()
            val link = fixUrl(it.slug.toString())
            val posterLink = it.poster.toString()
            Log.d("DZL", name)
            Log.d("DZL", link)
            Log.d("DZL", posterLink)
            val toSearchResponse = toSearchResponse(name, link, posterLink)
            veriler.add(toSearchResponse)
        }
        return veriler
    }

    private fun toSearchResponse(ad: String, link: String, posterLink: String): SearchResponse {
        return newTvSeriesSearchResponse(
            ad,
            link,
            TvType.TvSeries,
        ) {
            this.posterUrl = posterLink
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val mainReq = app.get(url)
        val document = mainReq.document
        val title = document.selectFirst("div.poster.poster h2")?.text() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.w-full.page-top.relative img")?.attr("src"))
        val year =
            document.select("div.w-fit.min-w-fit")[1].selectFirst("span.text-sm.opacity-60")?.text()
                ?.split(" ")?.last()?.toIntOrNull()
        val description = document.selectFirst("div.mt-2.text-sm")?.text()?.trim()
        val tags = document.selectFirst("div.poster.poster h3")?.text()?.split(",")?.map { it }
        val rating =
            document.selectFirst("div.flex.items-center")?.selectFirst("span.text-white.text-sm")
                ?.text()?.trim().toRatingInt()
        val actors = document.select("div.global-box h5").map {
            Actor(it.text())
        }

        val episodeses = mutableListOf<Episode>()

        for (sezon in document.select("div.flex.items-center.flex-wrap.gap-2.mb-4 a")) {
            val sezonhref = fixUrl(sezon.attr("href"))
            val sezonReq = app.get(sezonhref)
            val split = sezonhref.split("-")
            val season = split[split.size-2].toIntOrNull()
            val sezonDoc = sezonReq.document
            val episodes = sezonDoc.select("div.episodes")
            for (bolum in episodes.select("div.cursor-pointer")) {
                val epName = bolum.select("a").last()?.text() ?: continue
                val epHref = fixUrlNull(bolum.select("a").last()?.attr("href")) ?: continue
                val epEpisode = bolum.selectFirst("a")?.text()?.trim()?.toIntOrNull()
                val newEpisode = newEpisode(epHref) {
                    this.name = epName
                    this.season = season
                    this.episode = epEpisode
                }
                Log.d("DZL", newEpisode.name.toString() + " - " + newEpisode.season + " - " + newEpisode.data +  " - " + newEpisode.episode)
                episodeses.add(newEpisode)
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeses) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.rating = rating
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZL", "data » ${data}")
        val document = app.get(data).document
        Log.d("DZL", "Document: $document")
        val iframe =
            fixUrlNull(document.selectFirst("div#dizillaVideoP iframe")?.attr("src")) ?: return false
        Log.d("DZL", "iframe » $iframe")

        loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
        return true
    }
}
