package com.music.innertube.utils

import com.music.innertube.YouTube
import com.music.innertube.pages.LibraryContinuationPage
import com.music.innertube.pages.LibraryPage
import com.music.innertube.pages.PlaylistContinuationPage
import com.music.innertube.pages.PlaylistPage
import kotlinx.coroutines.delay
import java.security.MessageDigest

// a page that fails, repeats or comes back empty stops here and leaves its token in songsContinuation, so a sync knows the list is short
@JvmName("completedLibrary")
suspend fun Result<PlaylistPage>.completed(): Result<PlaylistPage> = runCatching {
    val page = getOrThrow()
    val songs = page.songs.toMutableList()
    var continuation = page.songsContinuation
    val seenContinuations = mutableSetOf<String>()
    var requestCount = 0
    val maxRequests = 200

    while (continuation != null && requestCount < maxRequests) {
        if (continuation in seenContinuations) {
            break
        }
        seenContinuations.add(continuation)
        requestCount++

        var continuationPage: PlaylistContinuationPage? = null
        for (attempt in 0..2) {
            val result = YouTube.playlistContinuation(continuation)
            if (result.isSuccess) {
                continuationPage = result.getOrNull()
                break
            }
            if (attempt < 2) {
                delay(500L * (attempt + 1))
            }
        }
        if (continuationPage == null) break
        // an empty page never carries a next token, and youtube only hands out a token when more songs follow
        if (continuationPage.songs.isEmpty()) break

        songs += continuationPage.songs
        continuation = continuationPage.continuation
    }
    PlaylistPage(
        playlist = page.playlist,
        songs = songs,
        songsContinuation = continuation,
        continuation = page.continuation
    )
}

// when paging stops early, continuation keeps the token it stopped on, so a sync knows the list is short
@JvmName("completedPlaylist")
suspend fun Result<LibraryPage>.completed(): Result<LibraryPage> = runCatching {
    val page = getOrThrow()
    val items = page.items.toMutableList()
    var continuation = page.continuation
    val seenContinuations = mutableSetOf<String>()
    var requestCount = 0
    val maxRequests = 200
    var consecutiveEmptyResponses = 0

    while (continuation != null && requestCount < maxRequests) {
        if (continuation in seenContinuations) {
            break
        }
        seenContinuations.add(continuation)
        requestCount++

        var continuationPage: LibraryContinuationPage? = null
        for (attempt in 0..2) {
            val result = YouTube.libraryContinuation(continuation)
            if (result.isSuccess) {
                continuationPage = result.getOrNull()
                break
            }
            if (attempt < 2) {
                delay(500L * (attempt + 1))
            }
        }
        if (continuationPage == null) break

        if (continuationPage.items.isEmpty()) {
            consecutiveEmptyResponses++
            if (consecutiveEmptyResponses >= 2) break
        } else {
            consecutiveEmptyResponses = 0
            items += continuationPage.items
        }

        continuation = continuationPage.continuation
    }
    LibraryPage(
        items = items,
        continuation = continuation
    )
}

fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }

fun sha1(str: String): String = MessageDigest.getInstance("SHA-1").digest(str.toByteArray()).toHex()

fun parseCookieString(cookie: String): Map<String, String> =
    cookie.split(';')
        .mapNotNull { part ->
            val trimmed = part.trim()
            val splitIndex = trimmed.indexOf('=')
            if (splitIndex <= 0) null
            else trimmed.substring(0, splitIndex).trim() to trimmed.substring(splitIndex + 1).trim()
        }
        .toMap()

// some languages write durations with a dot instead of a colon (indonesian "3.45", finnish, danish), so either separator is accepted
private val DURATION_REGEX = Regex("^(?:(\\p{Nd}+)[:.])?(\\p{Nd}+)[:.](\\p{Nd}{2})$")

fun String.parseTime(): Int? {
    val (hours, minutes, seconds) = DURATION_REGEX.matchEntire(trim())?.destructured ?: return null
    return try {
        (hours.toIntOrNull() ?: 0) * 3600 + minutes.toInt() * 60 + seconds.toInt()
    } catch (e: NumberFormatException) {
        null
    }
}

fun isPrivateId(browseId: String): Boolean {
    return browseId.contains("privately")
}
