package com.nikyokki

import android.util.Base64
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
import com.lagradost.cloudstream3.utils.DataStoreHelper.toYear
import org.jsoup.Jsoup

class SelcukFlix : MainAPI() {
    override var mainUrl              = "https://selcukflix.com"
    override var name                 = "SelcukFlix"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/film-kategori/aile/"      to "Aile",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) {

        }
        val document = app.get(request.data).document
        val home     = document.select("div.filter-tabs a.relative").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title     = this.selectFirst("div.flbaslik")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchReq = app.post(
            "${mainUrl}/api/bg/searchcontent?searchterm=$query",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to "en-US,en;q=0.5",
                "X-Requested-With" to "XMLHttpRequest",
                "Sec-Fetch-Site" to "same-origin",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Dest" to "empty",
                "Referer" to "${mainUrl}/"
            ),
            referer = "${mainUrl}/",
        )
        val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val searchResult: SearchResult = objectMapper.readValue(searchReq.toString())
        val decodedSearch = base64Decode(searchResult.response.toString())
        val contentJson: SearchData = objectMapper.readValue(decodedSearch)
        if (contentJson.state != true) {
            throw ErrorLoadingException("Invalid Json response")
        }
        val veriler = mutableListOf<SearchResponse>()
        contentJson.result?.forEach {
            val name = it.title.toString()
            val link = fixUrl(it.slug.toString())
            val posterLink = it.poster.toString().replace("images-macellan-online.cdn.ampproject.org/i/s/", "")
            val type = it.type.toString()
            val toSearchResponse = toSearchResponse(name, link, posterLink, type)
            veriler.add(toSearchResponse)
        }
        return veriler
    }

    private fun toSearchResponse(ad: String, link: String, posterLink: String, type: String): SearchResponse {
        if (type == "Movies") {
            return newMovieSearchResponse(
                ad,
                link,
                TvType.Movie,
            ) {
                this.posterUrl = posterLink
            }
        } else {
            return newTvSeriesSearchResponse(
                ad,
                link,
                TvType.TvSeries,
            ) {
                this.posterUrl = posterLink
            }
        }

    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val encodedDoc = app.get(url).document
        val script = encodedDoc.selectFirst("script#__NEXT_DATA__")?.data()
        val secureData = objectMapper.readTree(script).get("props").get("pageProps").get("secureData")
        val decodedJson = base64Decode(secureData.toString().replace("\"", ""))
        val root: Root = objectMapper.readValue(decodedJson)
        val item = root.contentItem
        val orgTitle        = item.originalTitle
        val cultTitle       = item.cultureTitle
        val title           = if (orgTitle == cultTitle) orgTitle else "$orgTitle - $cultTitle"
        val poster          = fixUrlNull(item.posterUrl?.replace("images-macellan-online.cdn.ampproject.org/i/s/", ""))
        val description     = item.description
        val year            = item.releaseYear
        val tags            = item.categories?.split(",")
        val rating          = item.imdbPoint
        val duration        = item.totalMinutes
        val actors          = root.relatedResults.getMovieCastsById?.result?.map { Actor(it.name!!, fixUrlNull(it.castImage?.replace("images-macellan-online.cdn.ampproject.org/i/s/", ""))) }
        val trailer         = root.relatedResults.getContentTrailers?.result?.get(0)?.rawUrl


        return newMovieLoadResponse(title!!, url, TvType.Movie, url) {
            this.posterUrl       = poster
            this.plot            = description
            this.year            = year
            this.tags            = tags
            this.rating          = rating
            this.duration        = duration
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
        Log.d("SFX", "data » ${data}")
        val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val encodedDoc = app.get(data).document
        val script = encodedDoc.selectFirst("script#__NEXT_DATA__")?.data()
        val secureData = objectMapper.readTree(script).get("props").get("pageProps").get("secureData")
        val decodedJson = base64Decode(secureData.toString().replace("\"", ""))
        val root: Root = objectMapper.readValue(decodedJson)
        val ids = mutableListOf<Int>()
        objectMapper.readTree(decodedJson).get("RelatedResults").get("getMoviePartsById").get("result").forEach { it ->
            ids.add(it.get("id").asInt())
        }
        val iframes = mutableListOf<SourceItem>()
        val relatedResults = root.relatedResults
        if (relatedResults.getMoviePartsById?.state == true){
            relatedResults.getMoviePartsById.result?.forEach { it ->
                objectMapper.readTree(decodedJson).get("RelatedResults")
                    .get("getMoviePartSourcesById_${it.id}")
                    .get("result").forEach { ifs ->
                        iframes.add(SourceItem(ifs.get("source_content").asText(), ifs.get("quality_name").asText()))
                    }
            }
        }
        iframes.forEach { it ->
            val iframe = fixUrlNull(Jsoup.parse(it.sourceContent).attr("src"))
            loadExtractor(iframe!!, "${mainUrl}/", subtitleCallback, callback)
        }

        return true
    }
}