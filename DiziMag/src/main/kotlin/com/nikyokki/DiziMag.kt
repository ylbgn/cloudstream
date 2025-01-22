// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.nikyokki

import android.util.Log
import com.fasterxml.jackson.databind.ObjectMapper
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
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toRatingInt
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.cookies
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.regex.Pattern

class DiziMag : MainAPI() {
    override var mainUrl = "https://dizimag.org"
    override var name = "DiziMag"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    var cf_clearance = ""
    var ci_session = ""
    var level = ""
    var udys = ""

    // ! CloudFlare bypass
    override var sequentialMainPage =
        true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    override var sequentialMainPageDelay = 250L  // ? 0.05 saniye
    override var sequentialMainPageScrollDelay = 250L  // ? 0.05 saniye

    override val mainPage = mainPageOf(
        "${mainUrl}/kesfet/eyJjYXRlZ29yeSI6W3siaWQiOiI4OSIsIm5hbWUiOiJBa3NpeW9uICYgTWFjZXJhIn1dfQ==" to "Aksiyon",

        "${mainUrl}/film/tur/aile" to "Aile Film"
    )

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            println("Chain: $chain")
            val request = chain.request()
            println("Request: $request")
            val response = chain.proceed(request)
            println("Request: $response")
            val doc = Jsoup.parse(response.peekBody(1024 * 1024).string())
            val intercept = cloudflareKiller.intercept(chain)
            println("Saved Cookies: ${cloudflareKiller.savedCookies}")
            println("Cookies: ${intercept.cookies}")
            return intercept

            //return response
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val mainReq = app.get("${request.data}/${page}")

        val document = mainReq.document
        val home = document.select("li.w-full").mapNotNull { it.diziler() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.diziler(): SearchResponse? {
        val title = this.selectFirst("div.filter-result-box-subject-top-left h2")?.text() ?: return null
        val href = fixUrlNull(this.selectFirst("div.filter-result-box-subject-top-left a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.filter-result-box-image img")?.attr("data-src"))

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

    private fun toSearchResponse(ad: String, link: String, posterLink: String): SearchResponse? {
        if (link.contains("dizi")) {
            return newTvSeriesSearchResponse(
                ad ?: return null,
                link,
                TvType.TvSeries,
            ) {
                this.posterUrl = posterLink
            }
        } else {
            return newMovieSearchResponse(
                ad ?: return null,
                link,
                TvType.Movie,
            ) {
                this.posterUrl = posterLink
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val mainReq = app.get(mainUrl)
        val mainPage = mainReq.document
        val cKey = mainPage.selectFirst("input[name='cKey']")?.attr("value") ?: return emptyList()
        val cValue =
            mainPage.selectFirst("input[name='cValue']")?.attr("value") ?: return emptyList()
        val cookie = mainReq.cookies["PHPSESSID"].toString()
        println("Ckey: $cKey ---- Cvalue: $cValue ---- cookie: $cookie")

        val veriler = mutableListOf<SearchResponse>()

        val searchReq = app.post(
            "${mainUrl}/bg/searchcontent",
            data = mapOf(
                "cKey" to cKey,
                "cValue" to cValue,
                "searchterm" to query
            ),
            headers = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest",
                "Cookie" to "PHPSESSID=$cookie"
            ),
            referer = "${mainUrl}/",
            cookies = mapOf(
                "CNT" to "vakTR",
                "PHPSESSID" to cookie
            )
        ).parsedSafe<SearchResult>()
        println("SearchReq: $searchReq")

        if (searchReq?.success != true) {
            throw ErrorLoadingException("Invalid Json response")
        }

        val searchDoc = searchReq.theme?.trim()

        searchDoc?.trim()?.split("</a>")?.forEach { item ->

            val bb = item.substringAfter("<a href=\"").substringBefore("\"")
            val diziUrl = bb.trim()
            val cc = item.substringAfter("data-srcset=\"").substringBefore(" 1x")
            val posterLink = cc.trim()
            val dd = item.substringAfter("<span class=\"text-white\">").substringBefore("</span>")
            val ad = dd.trim()
            toSearchResponse(ad, diziUrl, posterLink)?.let { veriler.add(it) }


        }
        return veriler
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {

        val mainReq = app.get(url, referer = mainUrl)
        val document = mainReq.document

        if (url.contains("/dizi/")) {
            val title = document.selectFirst("div.page-title h1")?.selectFirst("a")?.text() ?: return null
            val poster = fixUrlNull(document.selectFirst("div.series-profile-image img")?.attr("src"))
            val year = document.select("li.w-auto.sm").last()?.select("p")?.text()?.toIntOrNull()
            val description = document.selectFirst("div.series-profile-summary p")?.text()?.trim()
            val tags = document.selectFirst("div.series-profile-type")?.select("a")?.mapNotNull { it.text().trim() }
            var rating = 0

            document.select("div.w-auto.sm").forEach { it ->
                    if (it.selectFirst("span")?.text()?.contains("Puan") == true){
                        rating = it.select("span.color-imdb").text().toRatingInt()!!
                    }
                }
            val actors = mutableListOf<Actor>()
            document.select("div.series-profile-cast li").forEach { it ->
                val img = fixUrlNull(it.selectFirst("img")?.attr("data-src"))
                val name = it.selectFirst("h5.truncate")?.text()?.trim() ?: return null
                actors.add(Actor(name, img))
            }

            val episodeses = mutableListOf<Episode>()
            var szn = 1
            for (sezon in document.select("div.series-profile-episode-list")) {
                Log.d("DMG", sezon.toString())
                var blm = 1
                for (bolum in sezon.select("li")) {
                    val epName = bolum.selectFirst("h6.truncate a")?.text() ?: continue
                    Log.d("DMG","epName: $epName")
                    val epHref = fixUrlNull(bolum.select("h6.truncate a").attr("href")) ?: continue
                    Log.d("DMG","epHref: $epHref")
                    val epEpisode = blm++
                    Log.d("DMG","epEpisode: $epEpisode")
                    //val epSeason  = bolum.selectFirst("div.seasons-menu")?.text()?.substringBefore(".Sezon")?.trim()?.toIntOrNull()
                    val epSeason = szn
                    Log.d("DMG","epSeason: $epSeason")

                    episodeses.add(
                        Episode(
                            data = epHref,
                            name = epName,
                            season = epSeason,
                            episode = epEpisode
                        )
                    )
                }
                szn++
            }
            println("Episodes : " + episodeses.size)

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeses) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                addActors(actors)
            }
        } else {
            var yil: Int? = null;
            val title = document.selectFirst("div.poster.hidden h2")?.text() ?: return null
            val poster = fixUrlNull(document.selectFirst("div.flex.items-start.gap-4.slider-top img")?.attr("src"))
            val description = document.selectFirst("div.mt-2.text-md")?.text()?.trim()
            document.select("div.flex.items-center").forEach { item ->
                if (item.selectFirst("span")?.text()?.contains("tarih") == true) {
                   yil  = item.selectFirst("div.w-fit.rounded-lg")?.text()?.toIntOrNull()
                }
            }
            val tags = document.select("div.text-white.text-md.opacity-90.flex.items-center.gap-2.overflow-auto.mt-1 a").map { it.text() }
            val rating =
                document.selectFirst("div.flex.items-center")?.selectFirst("span.text-white.text-sm")
                    ?.text()?.trim().toRatingInt()
            val actors = mutableListOf<Actor>()
            document.select("div.w-fit.min-w-fit.rounded-lg") .forEach { a ->
                if (a.selectFirst("span")?.text()?.contains("Aktör") == true) {
                    actors.add(Actor(a.selectFirst("a")?.text()!!))
                }
            }
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl       = poster
                this.year            = yil
                this.plot            = description
                this.rating          = rating
                this.tags            = tags
                addActors(actors)
            }
        }

    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DMG", "data » ${data}")
        val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        var aa = app.get(mainUrl)
        val ci_session = aa.cookies["ci_session"].toString()
        val document = app.get(data, headers = headers, cookies = mapOf(
            "ci_session" to ci_session
        )).document
        Log.d("DMG", document.toString())
        var iframe =
            fixUrlNull(document.selectFirst("div.tv-spoox2 iframe")?.attr("src")) ?: return false
        Log.d("DMG", "iframe » ${iframe}")

        val docum  = app.get(iframe, headers = headers, referer = "$mainUrl/").document
        docum.select("script").forEach { sc ->
            if (sc.text().contains("bePlayer")) {
                val pattern = Pattern.compile("bePlayer\\('(.*?)', '(.*?)'\\)")
                val matcher = pattern.matcher(sc.text().trimIndent())
                if (matcher.find()) {
                    val key = matcher.group(1)
                    val jsonCipher = matcher.group(2)
                    Log.d("DMG", "key » $key")
                    Log.d("DMG", "jsonCipher » $jsonCipher")
                    val cipherData = ObjectMapper().readValue(jsonCipher?.replace("\\/", "/"), Cipher::class.java)
                    val ctt = cipherData.ct
                    val iv = cipherData.iv
                    val s = cipherData.s
                    Log.d("DMG", "ctt » $ctt")
                    Log.d("DMG", "iv » $iv")
                    Log.d("DMG", "ctt » $s")
                    val decrypt = key?.let { CryptoJS.decrypt(it, ctt, iv, s) }
                    Log.d("DMG", "decrypt » $decrypt")

                    val jsonData = ObjectMapper().readValue(decrypt, JsonData::class.java)
                    Log.d("DMG", "jsonData » $jsonData")

                    callback.invoke(
                        ExtractorLink(
                            source  = this.name,
                            name    = this.name,
                            url     = jsonData.videoLocation,
                            referer = jsonData.referer,
                            quality = Qualities.Unknown.value,
                            isM3u8  = true
                        )
                    )


                }
            }
        }

        loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
        return true
    }
}
