package com.fnmusic.tv.core.data.api

import com.fnmusic.tv.core.data.server.NormalizedServer
import com.fnmusic.tv.core.data.server.ConnectionAccess
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class TrimMusicApi(
    private val server: NormalizedServer,
    private val client: OkHttpClient,
    private val token: () -> String?,
    private val access: ConnectionAccess = ConnectionAccess(),
) {
    fun apiBase(): String = server.apiBase.toString()
    suspend fun systemConfig(): SystemConfigDto = get("sys/config", authenticated = false)

    suspend fun login(username: String, password: String, deviceId: String): LoginResultDto {
        val passwordChars = password.toCharArray()
        return try {
            login(username, PasswordHash.fromPlaintext(passwordChars), deviceId)
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    internal suspend fun login(username: String, password: PasswordHash, deviceId: String): LoginResultDto =
        post(
            "user/password-login",
            PasswordLoginRequest(username, password.encoded, deviceId),
            authenticated = false,
        )

    suspend fun me(): UserDto = get("user/me")

    suspend fun logout() {
        val builder = Request.Builder().url(server.resolveApi("user/logout"))
            .post(ByteArray(0).toRequestBody())
        executeUnit(builder, authenticated = true)
    }

    suspend fun playlists(): List<PlaylistDto> = get<PageListDto<PlaylistDto>>("playlist/list").list

    suspend fun playlist(guid: String): PlaylistDetailDto = get("playlist/detail", "guid" to guid)

    suspend fun playlistTracks(guid: String, page: Int, size: Int = 50): SortedPageListDto<TrackDto> =
        get("track/playlist-detail/list", "playlistGUID" to guid, "page" to page, "size" to size, "sort" to "trackAddedAt,desc")

    suspend fun artists(page: Int, size: Int = 50): SortedPageListDto<ArtistDto> =
        get("artist/list", "page" to page, "size" to size, "sort" to "trackCount,desc")

    suspend fun artist(guid: String): ArtistDto = get("artist/detail", "guid" to guid)

    suspend fun artistTracks(guid: String, page: Int, size: Int = 50): SortedPageListDto<TrackDto> =
        get("track/artist-detail/list", "artistGUID" to guid, "page" to page, "size" to size, "sort" to "createdAt,desc")

    suspend fun artistAlbums(guid: String, page: Int, size: Int = 50): SortedPageListDto<AlbumDto> =
        get("album/artist-detail/list", "artistGUID" to guid, "page" to page, "size" to size, "sort" to "newTrackAddedAt,desc")

    suspend fun albums(page: Int, size: Int = 50): SortedPageListDto<AlbumDto> =
        get("album/list", "page" to page, "size" to size, "sort" to "newTrackAddedAt,desc")

    suspend fun album(guid: String): AlbumDto = get("album/detail", "guid" to guid)

    suspend fun albumTracks(guid: String, page: Int, size: Int = 50): SortedPageListDto<TrackDto> =
        get("track/album-detail/list", "albumGUID" to guid, "page" to page, "size" to size, "sort" to "trackNo,asc")

    suspend fun allTracks(page: Int, size: Int = 50): SortedPageListDto<TrackDto> =
        get("track/list", "page" to page, "size" to size, "sort" to "createdAt,desc")

    suspend fun favoriteTracks(page: Int, size: Int = 50): PageListDto<TrackDto> =
        get("favorite-track/list", "page" to page, "size" to size, "sort" to "favoriteAt,desc")

    suspend fun createFavorite(trackGuid: String) {
        postUnit("favorite-track/create", FavoriteTrackRequest(trackGuid))
    }

    suspend fun deleteFavorite(trackGuid: String) {
        postUnit("favorite-track/delete", FavoriteTrackRequest(trackGuid))
    }

    suspend fun sharedLibraries(): List<SharedLibraryDto> = get<ListDto<SharedLibraryDto>>("shared-library/list").list

    suspend fun metadata(guid: String): TrackMetadataDto = get("track/metadata", "guid" to guid)

    suspend fun lyrics(guid: String): LyricListDto = get("lyric/list", "trackGUID" to guid)

    suspend fun roamStart(deviceId: String): RoamStartDto? = getNullable("track/roam-start", "deviceId" to deviceId)

    suspend fun roamNext(deviceId: String, roamId: String): RoamWindowDto =
        get("track/roam-next", "deviceId" to deviceId, "relativeRoamId" to roamId)

    suspend fun roamPrevious(deviceId: String, roamId: String): RoamWindowDto =
        get("track/roam-previous", "deviceId" to deviceId, "relativeRoamId" to roamId)

    fun streamUrl(guid: String) = server.resolveApi("track/stream").newBuilder().addQueryParameter("guid", guid).build()

    fun coverUrl(coverId: String, size: Int?) = server.resolveApi("static/cover").newBuilder()
        .addQueryParameter("coverId", coverId)
        .apply { if (size != null) addQueryParameter("size", size.toString()) }
        .build()

    suspend fun cover(coverId: String, size: Int?): ByteArray = executeBytes(
        Request.Builder().url(coverUrl(coverId, size)),
        authenticated = true,
    )

    private suspend inline fun <reified T> get(path: String, authenticated: Boolean = true): T =
        execute(Request.Builder().url(server.resolveApi(path)), authenticated)

    private suspend inline fun <reified T> get(path: String, vararg query: Pair<String, Any>): T {
        val url = server.resolveApi(path).newBuilder().apply {
            query.forEach { (name, value) -> addQueryParameter(name, value.toString()) }
        }.build()
        return execute(Request.Builder().url(url), true)
    }

    private suspend inline fun <reified T> getNullable(path: String, vararg query: Pair<String, Any>): T? {
        val url = server.resolveApi(path).newBuilder().apply {
            query.forEach { (name, value) -> addQueryParameter(name, value.toString()) }
        }.build()
        return executeNullable(Request.Builder().url(url), true)
    }

    private suspend inline fun <reified RequestType, reified ResponseType> post(
        path: String,
        body: RequestType,
        authenticated: Boolean = true,
    ): ResponseType {
        val json = ApiDecoder.json.encodeToString(body)
        val builder = Request.Builder().url(server.resolveApi(path))
            .post(json.toRequestBody("application/json".toMediaType()))
        return execute(builder, authenticated)
    }

    private suspend inline fun <reified RequestType> postUnit(
        path: String,
        body: RequestType,
    ) {
        val json = ApiDecoder.json.encodeToString(body)
        val builder = Request.Builder().url(server.resolveApi(path))
            .post(json.toRequestBody("application/json".toMediaType()))
        executeUnit(builder, authenticated = true)
    }

    private suspend inline fun <reified T> execute(builder: Request.Builder, authenticated: Boolean): T =
        executeResponse(builder, authenticated) { response -> ApiDecoder.decode<T>(response.body.string()) }

    private suspend inline fun <reified T> executeNullable(builder: Request.Builder, authenticated: Boolean): T? =
        executeResponse(builder, authenticated) { response -> ApiDecoder.decodeNullable<T>(response.body.string()) }

    private suspend fun executeBytes(builder: Request.Builder, authenticated: Boolean): ByteArray =
        executeResponse(builder, authenticated) { response ->
            if (response.header("Content-Type")?.contains("json", ignoreCase = true) == true) {
                ApiDecoder.decode<JsonElement>(response.body.string())
                throw AppException(AppError.Unknown("invalid_json"))
            }
            response.body.bytes().takeIf(ByteArray::isNotEmpty) ?: throw AppException(AppError.Empty)
        }

    private suspend fun executeUnit(builder: Request.Builder, authenticated: Boolean) =
        executeResponse(builder, authenticated) { response -> ApiDecoder.decodeUnit(response.body.string()) }

    private suspend fun <T> executeResponse(
        builder: Request.Builder,
        authenticated: Boolean,
        decode: (Response) -> T,
    ): T {
        val authToken = if (authenticated) token() ?: throw AppException(AppError.Unauthenticated) else null
        for ((name, value) in access.headers(authToken)) {
            builder.header(name, value)
        }
        return suspendCancellableCoroutine { continuation ->
            var activeCall: Call? = null
            fun enqueue(request: Request, redirects: Int) {
                val call = client.newCall(request)
                activeCall = call
                call.enqueue(
                    object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(e.asRequestFailure())
                        }

                        override fun onResponse(call: Call, response: Response) {
                            if (access.relayMode && response.isRedirect && redirects < MAX_RELAY_REDIRECTS) {
                                val next = response.header("Location")?.let { request.url.resolve(it) }
                                response.close()
                                if (!continuation.isActive) return
                                if (next == null) {
                                    continuation.resumeWithException(AppException(AppError.NetworkUnavailable))
                                } else {
                                    enqueue(request.newBuilder().url(next).build(), redirects + 1)
                                }
                                return
                            }
                            val result = runCatching {
                                response.use {
                                    classifyStatus(it)
                                    decode(it)
                                }
                            }
                            if (!continuation.isActive) return
                            result.fold(
                                onSuccess = continuation::resume,
                                onFailure = { continuation.resumeWithException(it.asRequestFailure()) },
                            )
                        }
                    },
                )
            }
            continuation.invokeOnCancellation { activeCall?.cancel() }
            enqueue(builder.build(), redirects = 0)
        }
    }

    private fun Throwable.asRequestFailure(): Throwable = when (this) {
        is CancellationException, is AppException -> this
        is IOException -> AppException(
            AppError.NetworkUnavailable,
            ApiRequestFailure(retryable = true, cause = this),
        )
        else -> this
    }

    private fun classifyStatus(response: Response) {
        when {
            response.code == 401 -> throw AppException(
                AppError.Unauthenticated,
                ApiRequestFailure(retryable = false, statusCode = response.code),
            )
            response.code == 404 -> throw AppException(
                AppError.NotFound,
                ApiRequestFailure(retryable = false, statusCode = response.code),
            )
            response.isRedirect -> throw AppException(
                AppError.NetworkUnavailable,
                ApiRequestFailure(retryable = false, statusCode = response.code),
            )
            !response.isSuccessful -> throw AppException(
                AppError.NetworkUnavailable,
                ApiRequestFailure(
                    retryable = response.code == 408 || response.code == 429 || response.code >= 500,
                    statusCode = response.code,
                ),
            )
        }
    }

    companion object {
        private const val MAX_RELAY_REDIRECTS = 5
        private const val POOL_KEEP_ALIVE_MS = 30_000L

        // FNOS nginx drops idle keep-alive sockets without notice. Connections retire after a
        // window well inside that idle timeout, and transport retry transparently replaces a
        // reused socket that died before a response, so no stale-connection error reaches callers.
        fun client(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, POOL_KEEP_ALIVE_MS, TimeUnit.MILLISECONDS))
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }
}

@JvmInline
internal value class PasswordHash private constructor(val encoded: String) {
    companion object {
        fun fromPlaintext(password: CharArray): PasswordHash {
            val bytes = password.concatToString().toByteArray(Charsets.UTF_8)
            return try {
                PasswordHash(MessageDigest.getInstance("SHA-256").digest(bytes).toHex())
            } finally {
                bytes.fill(0)
            }
        }

        fun parse(encoded: String): PasswordHash? = encoded
            .takeIf { it.length == SHA256_HEX_LENGTH && it.all(::isLowercaseHex) }
            ?.let(::PasswordHash)

        private const val SHA256_HEX_LENGTH = 64
    }
}

private fun ByteArray.toHex(): String {
    val hex = "0123456789abcdef"
    return buildString(size * 2) {
        this@toHex.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0x0f])
        }
    }
}

private fun isLowercaseHex(value: Char): Boolean = value in '0'..'9' || value in 'a'..'f'
