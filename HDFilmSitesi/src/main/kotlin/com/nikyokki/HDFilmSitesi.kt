// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.nikyokki

import android.util.Log
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
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toRatingInt
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.jsoup.nodes.Element

class HDFilmSitesi : MainAPI() {
    override var mainUrl = "https://hdfilmsitesi.net"
    override var name = "HDFilmSitesi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/filmizle/aile-filmleri-izle"        to "Aile",
        "${mainUrl}/filmizle/aksiyon-filmleri-izle"     to "Aksiyon",
        "${mainUrl}/filmizle/belgesel-filmleri-izle"    to "Belgesel",
        "${mainUrl}/filmizle/dram-filmleri-izle"        to "Dram",
        "${mainUrl}/filmizle/fantastik-filmleri-izle"   to "Fantastik",
        "${mainUrl}/filmizle/komedi-filmleri-hd-izle"   to "Komedi",
        "${mainUrl}/filmizle/korku-filmleri-izle"       to "Korku",
        "${mainUrl}/filmizle/romantik-filmleri-hd-izle" to "Romantik",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}/${page}/").document
        val home = document.select("div.movie_box").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title = this.selectFirst("h2")?.text() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/arama/${query}").document

        return document.select("div.movie_box").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title =
            document.selectFirst("h1 span")?.text()?.substringBefore(" İzle")?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description =
            document.selectFirst("div[itemprop='description']")?.text()?.substringAfter("⭐")
                ?.substringAfter("izleyin.")?.substringAfter("konusu:")?.trim()
        val year = document.selectFirst("span[itemprop='name']")?.text()?.trim()?.toIntOrNull()
        val tags =
            document.select("a[rel='category']").map { it.text().substringBefore(" Filmleri") }
        val rating =
            document.selectFirst("div.puanlar span")?.text()?.trim()?.substringAfter("IMDb")
                .toRatingInt()
        val duration =
            document.selectFirst("span[itemprop='duration']")?.text()?.split(" ")?.first()?.trim()
                ?.toIntOrNull()
        val actors = document.select("a.cast").map { Actor(it.text(), it.attr("href")) }
        val trailer = fixUrlNull(document.selectFirst("[property='og:video']")?.attr("content"))

        if (document.selectFirst("div.part_buton_sec")?.text()?.contains("Sezon") == true) {
            val episodes = mutableListOf<Episode>()

            val iframeSkici = IframeKodlayici()

            val pdataMatches = Regex("""pdata\[\'(.*?)'\] = \'(.*?)\';""").findAll(document.html())
            val pdataList = pdataMatches.map { it.destructured }.toList()

            for (pdata in pdataList) {
                val key = pdata.component1();
                val value = pdata.component2();

                val iframeData = iframeSkici.iframeCoz(value!!)
                val iframeLink = app.get(iframeData, referer = "${mainUrl}/").url.toString()

                //val sz_num = partNumber.takeIf { it.contains("sezon") }?.substringBefore("sezon")?.toIntOrNull() ?: 1
                //val ep_num = partName.substringBefore(".")?.trim()?.toIntOrNull() ?: 1

                val sz_num = key.substringAfter("prt_").substringBefore("sezon").toIntOrNull() ?: 1
                var ep_num = key.substringAfter("sezon").toIntOrNull()
                if (ep_num != null) {
                    ep_num += 1
                } else {
                    ep_num = 1
                }

                episodes.add(
                    Episode(
                        data = iframeLink,
                        name = "${sz_num}. Sezon ${ep_num}. Bölüm",
                        season = sz_num,
                        episode = ep_num
                    )
                )
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
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("HDFS", "data » ${data}")
        if (data.contains("vidmody")) {
            Log.d("HDFS", "Vidmody var")
            val aa = app.get(data, referer = "${mainUrl}/").document
            val bb = aa.body().selectFirst("script").toString()
                .substringAfter("var id =").substringBefore(";")
                .replace("'", "").trim()
            Log.d("HDFS", aa.body().selectFirst("script").toString())
            Log.d(
                "HDFS", aa.body().selectFirst("script").toString()
                    .substringAfter("var id =").substringBefore(";")
                    .replace("'", "").trim()
            )
            val m3uLink = "https://vidmody.com/vs/$bb"
            Log.d("HDFS", m3uLink)
            val m3uicerik = app.get(m3uLink, referer = mainUrl).text

            val audioRegex = Regex(
                "#EXT-X-MEDIA:TYPE=AUDIO,.*NAME=\"(.*?)\".*URI=\"(.*?)\"",
                RegexOption.MULTILINE
            )
            val audioMatches = audioRegex.findAll(m3uicerik)
            println("--- AUDIO ---")
            audioMatches.forEach { matchResult ->
                val name = matchResult.groupValues[1]
                val uri = matchResult.groupValues[2]
                println("Name: $name, URI: $uri")
            }
            // SUBTITLES verilerini al
            val subtitlesRegex = Regex(
                "#EXT-X-MEDIA:TYPE=SUBTITLES,.*NAME=\"(.*?)\".*URI=\"(.*?)\"",
                RegexOption.MULTILINE
            )
            val subtitlesMatches = subtitlesRegex.findAll(m3uicerik)
            println("\n--- SUBTITLES ---")
            subtitlesMatches.forEach { matchResult ->
                val name = matchResult.groupValues[1]
                val uri = matchResult.groupValues[2]
                subtitleCallback.invoke(
                    SubtitleFile(
                        lang = name,
                        url = fixUrl(uri)
                    )
                )
            }

            // STREAM verilerini al
            val streamRegex = Regex(
                "#EXT-X-STREAM-INF:.*RESOLUTION=(\\d+x\\d+).*\\s+(https?://\\S+)",
                RegexOption.MULTILINE
            )
            val streamMatches = streamRegex.findAll(m3uicerik)
            println("\n--- STREAM ---")

            streamMatches.forEachIndexed { index, matchResult ->
                val resolution = matchResult.groupValues[1]
                val uri = matchResult.groupValues[2]
                val uriv2 = matchResult.groupValues[2].replace("a1.gif", "a2.gif")
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = "$resolution - " + (audioMatches.firstOrNull()?.groupValues?.getOrNull(
                            index
                        )
                            ?: ""),
                        url = uri,
                        referer = uri,
                        quality = getQualityFromName("4k"),
                        isM3u8 = true
                    )
                )
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = resolution + (audioMatches.firstOrNull()?.groupValues?.getOrNull(
                            index
                        )
                            ?: ""),
                        url = uriv2,
                        referer = uriv2,
                        quality = getQualityFromName("4k"),
                        isM3u8 = true
                    )
                )
            }
        }
        /*if (!data.contains(mainUrl)) {
            loadExtractor(data, "${mainUrl}/", subtitleCallback, callback)
            return true
        }*/

        val document = app.get(data).document
        val iframeSkici = IframeKodlayici()
        val pdataMatches = Regex("""pdata\[\'(.*?)'\] = \'(.*?)\';""").findAll(document.html())
        val pdataList = pdataMatches.map { it.destructured }.toList()

        for (pdata in pdataList) {
            val key = pdata.component1()
            val value = pdata.component2()
            Log.d("HDFS", "Key: $key")
            Log.d("HDFS", "Value: $value")
            val iframeData = iframeSkici.iframeCoz(value!!)
            val iframeLink = app.get(iframeData, referer = "${mainUrl}/").url.toString()
            Log.d("HDFS", "iframeLink » ${iframeLink}")
            if (iframeLink.contains("vidmody")) {
                Log.d("HDFS", "Vidmody var")
                val aa = app.get(iframeLink, referer = "${mainUrl}/").document
                Log.d("HDFS", aa.toString())
                Log.d("HDFS", aa.body().toString())
                Log.d("HDFS", aa.body().selectFirst("script").toString())
                val bb = aa.body().selectFirst("script").toString()
                    .substringAfter("var id =").substringBefore(";")
                    .replace("'", "").trim()
                Log.d("HDFS", aa.body().selectFirst("script").toString())
                Log.d(
                    "HDFS", aa.body().selectFirst("script").toString()
                        .substringAfter("var id =").substringBefore(";")
                        .replace("'", "").trim()
                )
                val m3uLink = "https://vidmody.com/vs/$bb"
                Log.d("HDFS", m3uLink)
                val m3uicerik = app.get(m3uLink, referer = mainUrl).text
                println(m3uicerik)
                val audioRegex = Regex(
                    "#EXT-X-MEDIA:TYPE=AUDIO,.*NAME=\"(.*?)\".*URI=\"(.*?)\"",
                    RegexOption.MULTILINE
                )
                val audioMatches = audioRegex.findAll(m3uicerik)
                println("--- AUDIO ---")
                audioMatches.forEach { matchResult ->
                    val name = matchResult.groupValues[1]
                    val uri = matchResult.groupValues[2]
                    println("Name: $name, URI: $uri")
                }
                // SUBTITLES verilerini al
                val subtitlesRegex = Regex(
                    "#EXT-X-MEDIA:TYPE=SUBTITLES,.*NAME=\"(.*?)\".*URI=\"(.*?)\"",
                    RegexOption.MULTILINE
                )
                val subtitlesMatches = subtitlesRegex.findAll(m3uicerik)
                println("\n--- SUBTITLES ---")
                subtitlesMatches.forEach { matchResult ->
                    val name = matchResult.groupValues[1]
                    val uri = matchResult.groupValues[2]
                    println("Subtitle: $name +  $uri")
                    subtitleCallback.invoke(
                        SubtitleFile(
                            lang = name,
                            url = fixUrl(uri)
                        )
                    )
                }

                // STREAM verilerini al
                val streamRegex = Regex(
                    "#EXT-X-STREAM-INF:.*RESOLUTION=(\\d+x\\d+).*\\s+(https?://\\S+)",
                    RegexOption.MULTILINE
                )
                val streamMatches = streamRegex.findAll(m3uicerik)
                println("\n--- STREAM ---")
                streamMatches.forEachIndexed { index, matchResult ->
                    val resolution = matchResult.groupValues[1]
                    val uri = matchResult.groupValues[2]
                    val uriv2 = matchResult.groupValues[2].replace("a1.gif", "a2.gif")
                    callback.invoke(
                        ExtractorLink(
                            source = this.name,
                            name = "$resolution - " + (audioMatches.firstOrNull()?.groupValues?.getOrNull(
                                index
                            )
                                ?: ""),
                            url = uri,
                            referer = uri,
                            quality = getQualityFromName("4k"),
                            isM3u8 = true
                        )
                    )
                    callback.invoke(
                        ExtractorLink(
                            source = this.name,
                            name = "$resolution - " + (audioMatches.firstOrNull()?.groupValues?.getOrNull(
                                index
                            )
                                ?: ""),
                            url = uriv2,
                            referer = uriv2,
                            quality = getQualityFromName("4k"),
                            isM3u8 = true
                        )
                    )
                }

            }
        }
        return true
    }
}