package com.tribalflix.app.api

import com.tribalflix.app.crypto.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.Proxy
import java.net.URLEncoder
import kotlin.random.Random
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * OkHttp-based MovieBox API client.
 * Port of [client.py] — visitor login, header signing, 7-host failover.
 *
 * All network calls suspend on Dispatchers.IO.
 * Uses org.json (built into Android) for JSON — no extra dependency needed.
 */

/** The 7-gateway host pool from the findings. */
internal val DEFAULT_HOST_POOL: List<String> = listOf(
    "https://api6.aoneroom.com",
    "https://api5.aoneroom.com",
    "https://api4.aoneroom.com",
    "https://api4sg.aoneroom.com",
    "https://api3.aoneroom.com",
    "https://api6sg.aoneroom.com",
    "https://api.inmoviebox.com",
)

internal val PATH_VISITOR_LOGIN = "/wefeed-mobile-bff/user-api/visitor-login"
internal val PATH_SEARCH_V2 = "/wefeed-mobile-bff/subject-api/search/v2"
internal val PATH_SUBJECT_GET = "/wefeed-mobile-bff/subject-api/get"
internal val PATH_SEASON_INFO = "/wefeed-mobile-bff/subject-api/season-info"
internal val PATH_PLAY_INFO_V2 = "/wefeed-mobile-bff/subject-api/play-info/v2"
internal val PATH_RESOURCE = "/wefeed-mobile-bff/subject-api/resource"
internal val PATH_RESOURCE_POSITION_V2 = "/wefeed-mobile-bff/subject-api/resource-position/v2"
internal val PATH_EXT_CAPTIONS = "/wefeed-mobile-bff/subject-api/get-ext-captions"

data class DeviceProfile(
    val userAgent: String,
    val clientInfo: Map<String, Any>
)

data class VisitorLoginResult(
    val token: String,
    val userId: String?,
    val expiresAt: Long?
)

data class SearchSubject(
    val subjectId: String,
    val title: String?,
    val poster: String?,
    val subjectType: Int = 1,
    val year: Int? = null,
    val rating: Double? = null,
    val synopsis: String? = null
)

data class PlayableSource(
    val id: String,
    val format: String,
    val codec: String?,
    val resolutions: List<String>,
    val url: String,
    val cookie: String?,
    val referer: String,
    val userAgent: String,
    val resourceId: String? = null,
    val sizeBytes: Long? = null,
    val subtitleUrl: String? = null
)

data class CaptionTrack(
    val language: String,
    val url: String,
    val size: Int
)

data class ResolvedSources(
    val subjectId: String,
    val season: Int,
    val episode: Int,
    val dashStreams: List<PlayableSource> = emptyList(),
    val directStreams: List<PlayableSource> = emptyList(),
    val subtitles: List<CaptionTrack> = emptyList()
)

class MovieBoxClient(
    private val hostPool: List<String> = DEFAULT_HOST_POOL,
    private val deviceProfile: DeviceProfile = generateDeviceProfile(),
    private val spoofedIp: String = randomSpoofedIp()
) {

    /**
     * Diagnostic: header names + value lengths OkHttp actually put on the wire.
     * Compared against client.py's header set to spot extra/altered headers.
     */
    private val wireHeaders = java.util.concurrent.atomic.AtomicReference("")

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .proxy(Proxy.NO_PROXY)
        .protocols(listOf(Protocol.HTTP_1_1))
        .addNetworkInterceptor { chain ->
            val req = chain.request()
            wireHeaders.set(req.headers.joinToString(", ") { "${it.first}(${it.second.length})" })
            chain.proceed(req)
        }
        .build()

    private var token: String? = SessionCache.token
    private var userId: String? = SessionCache.userId
    private var expiresAt: Long = SessionCache.expiresAt
    private var activeHostIdx: Int = 0

    suspend fun visitorLogin(): VisitorLoginResult = withContext(Dispatchers.IO) {
        val body = JSONObject().toString()
        val response = request("POST", PATH_VISITOR_LOGIN, body)
        val token = response.optString("token") ?: error("Visitor login failed: no token in $response")

        // client.py derives uid/exp from the JWT payload, not the response body.
        val (claimUid, claimExp) = parseJwtClaims(token)
        val uid = response.optString("uid")
            .ifBlank { response.optString("userId") }
            .ifBlank { claimUid.orEmpty() }
            .takeIf { it.isNotBlank() }
        val exp = claimExp.takeIf { it > 0 }
            ?: response.optLong("exp", -1).takeIf { it > 0 }

        // Inside withContext the implicit receiver is the CoroutineScope, so the
        // client's own fields must be qualified explicitly.
        this@MovieBoxClient.token = token
        this@MovieBoxClient.userId = uid
        this@MovieBoxClient.expiresAt = exp ?: 0
        SessionCache.save(token, uid, exp ?: 0)

        VisitorLoginResult(token = token, userId = uid, expiresAt = exp)
    }

    suspend fun ensureSession(): String {
        val now = System.currentTimeMillis() / 1000
        if (token != null && (expiresAt == 0L || now + 60 < expiresAt)) {
            return token!!
        }
        return visitorLogin().token
    }

    suspend fun search(query: String, page: Int = 1, perPage: Int = 15): List<SearchSubject> =
        withContext(Dispatchers.IO) {
            val payload = JSONObject()
            payload.put("keyword", query)
            payload.put("page", page)
            payload.put("perPage", perPage)
            payload.put("subjectType", 0)
            val response = request("POST", PATH_SEARCH_V2, payload.toString())
            parseSearchSubjects(response)
        }

    suspend fun getDetails(subjectId: String): JSONObject =
        withContext(Dispatchers.IO) {
            val path = "$PATH_SUBJECT_GET?subjectId=$subjectId"
            val details = request("GET", path, null)
            // client.py attaches season-info for TV series (subjectType == 2).
            val subject = details.optJSONObject("subject") ?: details
            val stype = subject.optInt("subjectType", subject.optInt("stype", 1))
            if (stype == 2) {
                try {
                    details.put("seasons", getSeasonInfo(subjectId))
                } catch (e: Exception) {
                    // seasons are optional; details still usable without them
                }
            }
            details
        }

    suspend fun getSeasonInfo(subjectId: String): JSONObject =
        withContext(Dispatchers.IO) {
            val path = "$PATH_SEASON_INFO?subjectId=$subjectId"
            request("GET", path, null)
        }

    suspend fun getPlayInfo(subjectId: String, season: Int = 0, episode: Int = 0): JSONObject =
        withContext(Dispatchers.IO) {
            val path = if (season == 0 && episode == 0) {
                "$PATH_PLAY_INFO_V2?subjectId=$subjectId"
            } else {
                "$PATH_PLAY_INFO_V2?subjectId=$subjectId&se=$season&ep=$episode"
            }
            request("GET", path, null)
        }

    suspend fun getResources(
        subjectId: String,
        season: Int = 0,
        episode: Int = 0,
        page: Int = 1,
        perPage: Int = 20,
        resolution: Int? = null
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val resStr = if (resolution != null) "&resolution=$resolution" else ""
            val path = if (season == 0 && episode == 0) {
                "$PATH_RESOURCE?subjectId=$subjectId&page=$page&perPage=$perPage$resStr"
            } else {
                "$PATH_RESOURCE?subjectId=$subjectId&se=$season&ep=$episode&page=$page&perPage=$perPage$resStr"
            }
            request("GET", path, null)
        }

    suspend fun recoverResourcePosition(
        subjectId: String,
        resourceId: String,
        failedUrl: String
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val q = "subjectId=${URLEncoder.encode(subjectId, "UTF-8")}&" +
                "resourceId=${URLEncoder.encode(resourceId, "UTF-8")}&" +
                "failUrl=${URLEncoder.encode(failedUrl, "UTF-8")}&" +
                "failCode=404&resourceNum=0&isVip=true"
            val path = "$PATH_RESOURCE_POSITION_V2?$q"
            request("GET", path, null)
        }

    suspend fun getExtCaptions(subjectId: String, resourceId: String): List<CaptionTrack> =
        withContext(Dispatchers.IO) {
            val path = "$PATH_EXT_CAPTIONS?subjectId=$subjectId&resourceId=$resourceId"
            try {
                val response = request("GET", path, null)
                val extCaptions = response.optJSONArray("extCaptions")
                if (extCaptions == null) return@withContext emptyList()
                val valid = mutableListOf<CaptionTrack>()
                for (i in 0 until extCaptions.length()) {
                    val c = extCaptions.getJSONObject(i)
                    val url = c.optString("url", "")
                    if (url.isBlank() || url.contains("aa348f2541d13ffe")) continue
                    val size = c.optInt("size", 0)
                    if (size > 0 && size <= 50) continue
                    valid.add(CaptionTrack(
                        language = c.optString("lanName", c.optString("lan", "Unknown")),
                        url = url,
                        size = size
                    ))
                }
                valid.toList()
            } catch (e: Exception) {
                emptyList()
            }
        }

    suspend fun getAllPlayableSources(
        subjectId: String,
        season: Int = 0,
        episode: Int = 0
    ): ResolvedSources =
        withContext(Dispatchers.IO) {
            ensureSession()
            val dashStreams = mutableListOf<PlayableSource>()

            // 1. DASH manifests from play-info/v2
            try {
                val playInfo = getPlayInfo(subjectId, season, episode)
                val streams = playInfo.optJSONArray("streams")
                if (streams != null) {
                    for (i in 0 until streams.length()) {
                        val s = streams.getJSONObject(i)
                        val rawUrl = s.optString("url", "")
                        val signCookie = s.optString("signCookie", "")
                        val resStr = s.optString("resolutions", "1080,720,480")

                        var manifestUrl: String? = null

                        if (signCookie.contains("urlprefix=")) {
                            manifestUrl = extractEdgeCacheManifest(signCookie)
                        }
                        if (manifestUrl == null && signCookie.contains("CloudFront-Policy=")) {
                            manifestUrl = extractCloudFrontManifest(signCookie)
                        }
                        if (manifestUrl == null && rawUrl.isNotBlank() && !isPlaceholderUrl(rawUrl)) {
                            manifestUrl = rawUrl
                        }

                        manifestUrl?.let { url ->
                            val resList = resStr.split(",").map { it.trim() }.filter { it.isNotBlank() }
                            dashStreams.add(PlayableSource(
                                id = s.optString("id", ""),
                                format = s.optString("format", "DASH"),
                                codec = s.optString("codecName", s.optString("codec", null)),
                                resolutions = resList,
                                url = url,
                                cookie = signCookie.takeIf { it.isNotBlank() },
                                referer = STREAM_REFERER,
                                userAgent = deviceProfile.userAgent
                            ))
                        }
                    }
                }
            } catch (e: Exception) {
                // swallow; direct streams may still work
            }

            // 2. Direct MP4 resources
            val directStreams = mutableListOf<PlayableSource>()
            var subtitleTracks = emptyList<CaptionTrack>()

            try {
                val resData = getResources(subjectId, season, episode, page = 1, perPage = 20)
                val items = resData.optJSONArray("list")
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        val link = item.optString("resourceLink", item.optString("url", ""))
                        val resId = item.optString("resourceId", item.optString("id", ""))
                        val resNum = item.optInt("resolution", 1080)
                        val se = item.optInt("se", 0)
                        val ep = item.optInt("ep", 0)

                        if ((season != 0 || episode != 0) && (se != season || ep != episode)) continue

                        var resolvedLink = link
                        if (isPlaceholderUrl(link) && resId.isNotBlank()) {
                            try {
                                val recovered = recoverResourcePosition(subjectId, resId, link)
                                val rList = recovered.optJSONArray("list")
                                if (rList != null) {
                                    for (j in 0 until rList.length()) {
                                        val cand = rList.getJSONObject(j)
                                        val candId = cand.optString("resourceId", cand.optString("id", ""))
                                        val candLink = cand.optString("resourceLink", cand.optString("url", ""))
                                        if (candId == resId && candLink.isNotBlank() && !isPlaceholderUrl(candLink)) {
                                            resolvedLink = candLink
                                            break
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // ignore
                            }
                        }

                        if (resolvedLink.isNotBlank() && !isPlaceholderUrl(resolvedLink)) {
                            directStreams.add(PlayableSource(
                                id = resId,
                                format = "direct",
                                codec = item.optString("codecName", item.optString("codec", "h264")),
                                resolutions = listOf("${resNum}p"),
                                url = resolvedLink,
                                cookie = null,
                                // Direct MP4 rejects the DASH Referer with HTTP 429
                                // (FINDINGS.md §5.2) — only the User-Agent is allowed.
                                referer = "",
                                userAgent = deviceProfile.userAgent,
                                resourceId = resId.takeIf { it.isNotBlank() },
                                sizeBytes = item.optLong("size")
                            ))
                        }

                        if (resId.isNotBlank() && subtitleTracks.isEmpty()) {
                            val captions = getExtCaptions(subjectId, resId)
                            if (captions.isNotEmpty()) subtitleTracks = captions
                        }
                    }
                }
            } catch (e: Exception) {
                // swallow
            }

            ResolvedSources(
                subjectId = subjectId,
                season = season,
                episode = episode,
                dashStreams = dashStreams,
                directStreams = directStreams,
                subtitles = subtitleTracks
            )
        }

    private suspend fun request(method: String, pathAndQuery: String, body: String?): JSONObject {
        // The visitor-login call must not call ensureSession(), or it would
        // recurse into itself forever on the very first request.
        if (pathAndQuery != PATH_VISITOR_LOGIN) ensureSession()

        var lastError: Exception? = null
        val failures = mutableListOf<String>()
        val attempts = hostPool.size
        for (i in 0 until attempts) {
            val idx = (activeHostIdx + i) % hostPool.size
            val base = hostPool[idx]
            val host = base.substringAfter("//")
            val url = "$base$pathAndQuery"
            val headers = buildHeaders(method, url, body)

            try {
                val mediaType = "application/json".toMediaType()
                // Must stay a BYTE body. OkHttp's String.toRequestBody rewrites
                // the media type to "application/json; charset=utf-8" (31 chars),
                // but x-tr-signature is computed over "application/json" (16).
                // That mismatch makes the gateway reject the request with 407,
                // which is why a bare SSLSocket/HttpURLConnection succeed with
                // the identical signature. ByteArray.toRequestBody passes the
                // media type through untouched.
                val requestBody = body?.toByteArray()?.toRequestBody(mediaType)
                val headerBuilder = Headers.Builder()
                headers.forEach { (name, value) -> headerBuilder.add(name, value) }
                val okHeaders = headerBuilder.build()

                val finalRequest = if (method.equals("GET", ignoreCase = true)) {
                    Request.Builder().url(url).headers(okHeaders).get().build()
                } else {
                    Request.Builder().url(url).headers(okHeaders)
                        .post(requestBody ?: ByteArray(0).toRequestBody(null))
                        .build()
                }

                val response = httpClient.newCall(finalRequest).execute()
                val responseBody = response.body?.string() ?: ""

                // Capture x-user header (may contain refreshed token)
                val xUser = response.header("x-user")
                if (!xUser.isNullOrBlank()) {
                    try {
                        val userData = JSONObject(xUser)
                        val newToken = userData.optString("token")
                        if (newToken.isNotBlank()) {
                            val (claimUid, claimExp) = parseJwtClaims(newToken)
                            val newUid = userData.optString("uid")
                                .ifBlank { userData.optString("userId") }
                                .ifBlank { claimUid.orEmpty() }
                                .takeIf { it.isNotBlank() }
                            this@MovieBoxClient.token = newToken
                            this@MovieBoxClient.userId = newUid
                            if (claimExp > 0) this@MovieBoxClient.expiresAt = claimExp
                            SessionCache.save(newToken, newUid, this@MovieBoxClient.expiresAt)
                        }
                    } catch (e: Exception) { /* ignore */ }
                }

                response.close()

                if (response.code == 401 || response.code == 403) {
                    token = null
                    SessionCache.save(null, null, 0)
                    if (pathAndQuery != PATH_VISITOR_LOGIN) {
                        try { visitorLogin(); continue } catch (e: Exception) { /* fall through */ }
                    }
                    failures += "$host HTTP ${response.code} ${responseBody.take(120).replace('\n', ' ')}"
                    lastError = RuntimeException("HTTP ${response.code} for $method $url")
                    continue
                }

                if (!response.isSuccessful) {
                    failures += "$host HTTP ${response.code} ${responseBody.take(120).replace('\n', ' ')}"
                    lastError = RuntimeException("HTTP ${response.code} for $method $url")
                    continue
                }

                activeHostIdx = idx
                val payload = JSONObject(responseBody)
                return if (payload.has("data") && !payload.isNull("data")) {
                    payload.getJSONObject("data")
                } else {
                    payload
                }
            } catch (e: Exception) {
                failures += "$host ${e.javaClass.simpleName}: ${e.message}"
                lastError = e
                continue
            }
        }
        // Diagnostic only: replay the same signed request over two other
        // Android transports so the failure message can distinguish
        // "server rejects this device's TLS" from "OkHttp-specific".
        val probeUrl = "${hostPool[activeHostIdx]}$pathAndQuery"
        val probeHeaders = buildHeaders(method, probeUrl, body)
        val probe = runCatching {
            probeRawTls(probeUrl, method, probeHeaders, body) + "; " +
                probeHttpUrlConnection(probeUrl, method, probeHeaders, body)
        }.getOrElse { "probe-error=${it.javaClass.simpleName}: ${it.message}" }

        throw RuntimeException(
            "All $attempts hosts failed for $method $pathAndQuery -> " +
                failures.joinToString(" | ") + " | transport probe: $probe" +
                " | sent: ${wireHeaders.get()}",
            lastError
        )
    }

    /** Sends the identical request over a bare SSLSocket (platform TLS, no OkHttp). */
    private fun probeRawTls(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?
    ): String {
        return try {
            val uri = java.net.URI(url)
            val host = uri.host ?: return "raw=no-host"
            val port = if (uri.port > 0) uri.port else 443
            val path = (uri.rawPath ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
            val payload = body?.toByteArray()

            val ssl = javax.net.ssl.SSLSocketFactory.getDefault()
                .createSocket(host, port) as javax.net.ssl.SSLSocket
            ssl.soTimeout = 8000
            ssl.startHandshake()

            val head = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: $host\r\n")
                for ((k, v) in headers) {
                    if (k.equals("Host", true) || k.equals("Content-Length", true)) continue
                    append("$k: $v\r\n")
                }
                append("Content-Length: ${payload?.size ?: 0}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            ssl.outputStream.apply {
                write(head.toByteArray())
                if (payload != null) write(payload)
                flush()
            }
            val status = ssl.inputStream.bufferedReader().readLine() ?: "raw=empty"
            ssl.close()
            "raw=$status"
        } catch (e: Exception) {
            "raw=${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Sends the identical request over HttpURLConnection (Android's OkHttp 2.x stack). */
    private fun probeHttpUrlConnection(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?
    ): String {
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = false
            for ((k, v) in headers) {
                if (k.equals("Host", true) || k.equals("Content-Length", true)) continue
                try { conn.setRequestProperty(k, v) } catch (e: Exception) { /* ignore */ }
            }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            conn.disconnect()
            "conn=$code"
        } catch (e: Exception) {
            "conn=${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun buildHeaders(method: String, url: String, body: String?): Map<String, String> {
        val tsMs = System.currentTimeMillis()
        val buildTag = generateXClientBuild(tsMs)
        val clientToken = generateXClientToken(tsMs)
        val sig = generateXTrSignature(method, "application/json", "application/json", url, body, tsMs)

        val infoDict = LinkedHashMap(deviceProfile.clientInfo)
        infoDict["X-Client-Build"] = buildTag
        val infoJson = JSONObject(infoDict).toString()

        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = deviceProfile.userAgent
        headers["Accept"] = "application/json"
        headers["Content-Type"] = "application/json"
        headers["Connection"] = "keep-alive"
        headers["x-client-token"] = clientToken
        headers["x-tr-signature"] = sig
        headers["x-client-build"] = buildTag
        headers["x-client-info"] = infoJson
        headers["x-client-status"] = "0"
        headers["x-forwarded-for"] = spoofedIp
        if (token != null) headers["Authorization"] = "Bearer $token"
        return headers
    }

    private fun parseSearchSubjects(response: JSONObject): List<SearchSubject> {
        val results = response.optJSONArray("results")
        if (results == null) return emptyList()
        val first = results.optJSONObject(0)
        val subjects = first?.optJSONArray("subjects") ?: return emptyList()
        val out = mutableListOf<SearchSubject>()
        for (i in 0 until subjects.length()) {
            val s = subjects.getJSONObject(i)
            out.add(SearchSubject(
                subjectId = s.optString("subjectId", s.optString("id", "")),
                title = s.optString("title", null),
                poster = s.optString("poster", s.optString("posterUrl", null)),
                subjectType = s.optInt("subjectType", 1),
                year = s.optInt("year", -1).takeIf { it > 0 },
                rating = s.optDouble("rating", -1.0).takeIf { it >= 0 },
                synopsis = s.optString("synopsis", s.optString("description", null))
            ))
        }
        return out
    }
}

private fun generateDeviceProfile(): DeviceProfile {
    val versionCode = listOf(50020117, 50020118, 50020119, 50020120, 50020121).random()
    val androidVersions = listOf(
        "9" to "PQ3A.190605.03081104",
        "10" to "QP1A.191005.007.A3",
        "11" to "RP1A.200720.011",
        "12" to "S1B.220414.015",
        "13" to "TQ2A.230405.003"
    )
    val redmiDevices = listOf(
        "23078RKD5C" to "Redmi",
        "2201117TY" to "Redmi",
        "2201117TG" to "Redmi",
        "22101316G" to "Redmi",
        "21121210G" to "Redmi",
        "M2012K11AG" to "Redmi"
    )
    val (androidVersion, buildId) = androidVersions.random()
    val (model, brand) = redmiDevices.random()
    val deviceId = (1..32).map { "0123456789abcdef".random() }.joinToString("")
    val gaid = UUID.randomUUID().toString()

    val ua = "com.community.oneroom/$versionCode (Linux; U; Android $androidVersion; en_US; $model; Build/$buildId; Cronet/135.0.7012.3)"

    val info = mapOf<String, Any>(
        "package_name" to "com.community.oneroom",
        "version_name" to "4.0.01.0813.03",
        "version_code" to versionCode,
        "os" to "android",
        "os_version" to androidVersion,
        "install_ch" to "ps",
        "device_id" to deviceId,
        "install_store" to "ps",
        "gaid" to gaid,
        "brand" to brand,
        "model" to model,
        "system_language" to "en",
        "net" to "NETWORK_WIFI",
        "region" to "US",
        "timezone" to "America/New_York",
        "sp_code" to "40401",
        "X-Play-Mode" to "2"
    )
    return DeviceProfile(ua, info)
}

private fun randomSpoofedIp(): String {
    val prefixes = listOf(
        "103.241", "49.36", "117.195", "106.198", "122.162",
        "157.32", "182.70", "103.58", "27.60", "59.90"
    )
    val p = prefixes.random()
    return "$p.${Random.nextInt(1, 255)}.${Random.nextInt(1, 255)}"
}

/** Extracts MPD URL from Edge-Cache signed cookie (urlprefix=...). */
fun extractEdgeCacheManifest(signCookie: String): String? {
    try {
        val part = signCookie
            .split("urlprefix=")[1]
            .split(";")[0]
            .split(":")[0]
        val pad = (4 - part.length % 4) % 4
        val norm = part.replace("-", "+").replace("_", "/") + "=".repeat(pad)
        val decUrl = String(java.util.Base64.getDecoder().decode(norm)).removeSuffix("*/")
        return if ("/dash/" in decUrl) "$decUrl/index.mpd" else null
    } catch (e: Exception) {
        return null
    }
}

/** Extracts MPD URL from CloudFront-Policy cookie. */
fun extractCloudFrontManifest(signCookie: String): String? {
    try {
        val part = signCookie
            .split("CloudFront-Policy=")[1]
            .split(";")[0]
            .trim()
        val pad = (4 - part.length % 4) % 4
        val norm = part.replace("-", "+").replace("_", "=").replace("~", "/") + "=".repeat(pad)
        val policy = JSONObject(String(java.util.Base64.getDecoder().decode(norm)))
        val resource = policy.optJSONArray("Statement")?.optJSONObject(0)?.optString("Resource", "") ?: ""
        return if ("/dash/" in resource) resource.removeSuffix("*/") + "/index.mpd" else null
    } catch (e: Exception) {
        return null
    }
}

/**
 * Decodes a JWT payload to recover the authenticated uid and expiry, matching
 * `client.py`'s `_parse_jwt_claims`. Returns (uid, expSeconds); uid is null and
 * exp is 0 when the token is not a decodable JWT.
 */
fun parseJwtClaims(token: String): Pair<String?, Long> {
    return try {
        val parts = token.split(".")
        if (parts.size < 2) return null to 0L
        val payload = JSONObject(String(base64UrlDecode(parts[1])))
        val uid = payload.optString("userId")
            .ifBlank { payload.optString("uid") }
            .ifBlank { payload.optString("sub") }
            .takeIf { it.isNotBlank() }
        uid to payload.optLong("exp", 0L)
    } catch (e: Exception) {
        null to 0L
    }
}
