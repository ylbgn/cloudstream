package com.nikyokki

import com.fasterxml.jackson.annotation.JsonProperty

data class IframeResponse(
    @JsonProperty("hls") val type: Int,
    @JsonProperty("videoImage") val videoImage: String,
    @JsonProperty("videoSource") val videoSource: String,
    @JsonProperty("securedLink") val securedLink: String
)