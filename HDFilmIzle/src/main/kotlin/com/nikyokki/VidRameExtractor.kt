package com.nikyokki

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.fixUrl


open class VidRameExtractor : ExtractorApi() {
    override val name = "VidRame"
    override val mainUrl = "https://vidrame.pro"
    override val requiresReferer = true

    private fun rs(s: String): String {
        return s.reversed()
    }

    private fun rr(s: String): String {
        return s.replace(Regex("[a-zA-Z]")) { matchResult ->
            val c = matchResult.value[0]
            val charCode = c.code
            val base = if (c <= 'Z') 90 else 122
            val newCharCode = charCode + 13
            val resultCharCode = if (base >= newCharCode) newCharCode else newCharCode - 26
            resultCharCode.toChar().toString()
        }
    }

    private fun ee(s: String): String {
        val r = rs(s)
        val a = rr(r)
        val b = Base64.encodeToString(a.toByteArray(), Base64.DEFAULT)
        return b.replace("+", "-").replace("/", "_").replace("=+$".toRegex(), "")
    }

    private fun String.addMarks(str: String): String {
        return this.replace(Regex("\"?$str\"?"), "\"$str\"")
    }


    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("VidEx", url)
        val script = app.get(
            url,
            referer = mainUrl
        ).document.select("script").find { it.data().contains("sources:") }?.data() ?: ""

        val regex = """("file": )EE\.dd\(".*?"\)""".toRegex()
        val videoData = script.substringAfter("sources: [")
            .substringBefore("],").addMarks("file").addMarks("type")
        Log.d("VidEx", videoData)
        var output = videoData.replace(regex) { matchResult ->
            "${matchResult.groupValues[1]}\"${matchResult.value.substringAfter(matchResult.groupValues[1])}\""
        }
        output = output.addMarks("type").replace("\r", "").replace("\n", "")
            .replace("(\"", "(").replace("\")", ")")
        val subData =
            script.substringAfter("configs.tracks = ").substringBefore(";").addMarks("file")
                .addMarks("label").addMarks("kind")

        val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        val captions: List<SubSource>? = subData.let { objectMapper.readValue(it) }
        if (captions != null) {
            for (cap in captions) {
                subtitleCallback.invoke(
                    SubtitleFile(
                        lang = cap.label.toString(),
                        url = fixUrl(cap.file.toString())
                    )
                )
            }
        }
        tryParseJson<List<SubSource>>(subData)
            ?.filter { it.kind == "captions" }?.map {
                subtitleCallback.invoke(
                    SubtitleFile(
                        it.label.toString(),
                        fixUrl(it.file.toString())
                    )
                )
            }
        val videos = output.let { objectMapper.readValue<Source>(it).file }
        println(videos)
        var video = videos?.substringAfter(".dd(")?.substringBefore(")")
        video = video?.replace("-", "+")?.replace("_", "/")
        while (video!!.length % 4 != 0) {
            video += "="
        }
        val a = String(Base64.decode(video, Base64.DEFAULT))
        val b = rr(a)
        val m3uLink = rs(b)
        Log.d("VidEx", m3uLink)
        M3u8Helper.generateM3u8(
            name,
            m3uLink,
            "$mainUrl/"
        )
    }
}


private data class Source(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("type") val type: String? = null,
)

private data class SubSource(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("kind") val kind: String? = null,
    @JsonProperty("language") val language: String? = null,
)