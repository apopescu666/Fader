package com.fader.mobil.core

/**
 * Pulls the cover picture out of the first bytes of a track: ID3v2 APIC for
 * MP3 and the FLAC PICTURE metadata block. Enough for the common library
 * layouts without downloading whole files.
 */
internal object EmbeddedArt {

    fun fromBytes(bytes: ByteArray): ByteArray? = when {
        startsWith(bytes, "ID3") -> fromId3(bytes)
        startsWith(bytes, "fLaC") -> fromFlac(bytes)
        else -> null
    }

    /** M4A/AAC stores the cover in a moov/udta/meta/ilst/covr box, often near the end. */
    /**
     * How much of the file is worth reading: for MP3 the ID3v2 tag declares its
     * own size, so a typical file needs ~60 KB instead of a fixed 768 KB.
     */
    fun desiredReadSize(head: ByteArray, defaultSize: Int, maxSize: Int): Int {
        if (!startsWith(head, "ID3") || head.size < 10) return defaultSize
        val size = syncSafe(head, 6) ?: return defaultSize
        return (10 + size).coerceIn(minOf(defaultSize, 64 * 1024), maxSize)
    }

    fun isMp4(bytes: ByteArray): Boolean =
        bytes.size > 8 && String(bytes, 4, 4, Charsets.ISO_8859_1) == "ftyp"

    fun fromMp4(head: ByteArray, tail: ByteArray?): ByteArray? =
        mp4Cover(head) ?: tail?.let { mp4Cover(it) }

    private fun startsWith(bytes: ByteArray, tag: String): Boolean {
        if (bytes.size < tag.length) return false
        for (i in tag.indices) {
            if (bytes[i] != tag[i].code.toByte()) return false
        }
        return true
    }

    private fun fromId3(bytes: ByteArray): ByteArray? {
        if (bytes.size < 10) return null
        val major = bytes[3].toInt() and 0xFF
        if (major < 2 || major > 4) return null
        val size = syncSafe(bytes, 6) ?: return null
        val end = minOf(10 + size, bytes.size)
        var pos = 10
        val tagEnd = end
        while (pos + 10 <= tagEnd) {
            val id = String(bytes, pos, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            var frameSize = if (major == 4) {
                syncSafe(bytes, pos + 4) ?: break
            } else {
                readInt(bytes, pos + 4)
            }
            if (frameSize <= 0 || pos + 10 + frameSize > tagEnd) break
            if (id == "APIC") {
                return apicImage(bytes, pos + 10, pos + 10 + frameSize)
            }
            pos += 10 + frameSize
        }
        return null
    }

    private fun apicImage(bytes: ByteArray, start: Int, end: Int): ByteArray? {
        var pos = start
        if (pos >= end) return null
        val encoding = bytes[pos].toInt() and 0xFF
        pos++
        // MIME type: latin-1, null terminated
        while (pos < end && bytes[pos] != 0.toByte()) pos++
        pos++
        pos++ // picture type
        // Description: terminated by one null (latin-1/utf-8) or two (utf-16)
        if (encoding == 1 || encoding == 2) {
            while (pos + 1 < end && !(bytes[pos] == 0.toByte() && bytes[pos + 1] == 0.toByte())) pos += 2
            pos += 2
        } else {
            while (pos < end && bytes[pos] != 0.toByte()) pos++
            pos++
        }
        if (pos >= end) return null
        return bytes.copyOfRange(pos, end)
    }

    private fun fromFlac(bytes: ByteArray): ByteArray? {
        var pos = 4
        while (pos + 4 <= bytes.size) {
            val header = bytes[pos].toInt() and 0xFF
            val last = header and 0x80 != 0
            val type = header and 0x7F
            val length = ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            pos += 4
            if (pos + length > bytes.size) return null
            if (type == 6) return flacPicture(bytes, pos, pos + length)
            pos += length
            if (last) break
        }
        return null
    }

    private fun flacPicture(bytes: ByteArray, start: Int, end: Int): ByteArray? {
        var pos = start + 4 // picture type
        if (pos + 4 > end) return null
        val mimeLength = readInt(bytes, pos)
        pos += 4 + mimeLength
        if (pos + 4 > end) return null
        val descriptionLength = readInt(bytes, pos)
        pos += 4 + descriptionLength
        pos += 16 // width, height, depth, colours
        if (pos + 4 > end) return null
        val dataLength = readInt(bytes, pos)
        pos += 4
        if (dataLength <= 0 || pos + dataLength > end) return null
        return bytes.copyOfRange(pos, pos + dataLength)
    }

    private fun mp4Cover(bytes: ByteArray): ByteArray? {
        val covr = findBox(bytes, 0, bytes.size, listOf("moov", "udta", "meta", "ilst", "covr"))
            ?: return null
        val jpeg = indexOf(covr, byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        val png = indexOf(covr, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        val start = listOfNotNull(jpeg, png).minOrNull() ?: return null
        return covr.copyOfRange(start, covr.size)
    }

    private fun findBox(bytes: ByteArray, start: Int, end: Int, path: List<String>): ByteArray? {
        var pos = start
        while (pos + 8 <= end) {
            var size = readInt(bytes, pos)
            if (size < 8) return null
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1) {
                if (pos + 16 > end) return null
                size = readLong(bytes, pos + 8).toInt()
                header = 16
            }
            if (size <= 0 || pos + size > end) return null
            if (type == path[0]) {
                if (path.size == 1) return bytes.copyOfRange(pos + header, pos + size)
                val childStart = pos + header + if (type == "meta") 4 else 0
                return findBox(bytes, childStart, pos + size, path.drop(1))
            }
            pos += size
        }
        return null
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int? {
        if (needle.isEmpty() || haystack.size < needle.size) return null
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return null
    }

    private fun readLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return value
    }

    private fun syncSafe(bytes: ByteArray, offset: Int): Int? {
        if (offset + 4 > bytes.size) return null
        var value = 0
        for (i in 0 until 4) {
            val b = bytes[offset + i].toInt() and 0xFF
            if (b and 0x80 != 0) return null
            value = (value shl 7) or b
        }
        return value
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }
}
