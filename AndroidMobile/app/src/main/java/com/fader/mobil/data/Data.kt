package com.fader.mobil.data

enum class SourceKind { Local, WebDav }

data class Source(
    val id: String,
    val name: String,
    val detail: String,
    val kind: SourceKind,
)

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val duration: String,
    val art: Int,
    val artKey: String? = null,
    val artUri: String? = null,
)
