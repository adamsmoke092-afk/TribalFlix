package com.tribalflix.app.crypto

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic primitives and header signing for the MovieBox API.
 * Pure Kotlin port of [client.py] in the MovieBox reverse-engineering findings.
 * No Android dependencies — safe to unit-test on the JVM.
 *
 * ⚠️ SECURITY: DEFAULT_SECRET_BYTES and STOCK_CERT_SHA256 are embedded in the
 * DEX. They are extractable via strings/jadx. Accepted for personal-use only.
 */

/** 30-byte HMAC secret used to sign x-tr-signature headers. */
internal val DEFAULT_SECRET_BYTES: ByteArray = byteArrayOf(
    0xef.toByte(), 0xa8.toByte(), 0x91.toByte(), 0x97.toByte(), 0x4e.toByte(), 0xec.toByte(), 0xd3.toByte(), 0x14.toByte(),
    0x8d.toByte(), 0xf6.toByte(), 0x3a.toByte(), 0xa6.toByte(), 0x11.toByte(), 0x60.toByte(), 0x2d.toByte(), 0xef.toByte(),
    0xd1.toByte(), 0x01.toByte(), 0x25.toByte(), 0x9b.toByte(), 0xa5.toByte(), 0x21.toByte(), 0x02.toByte(), 0x2c.toByte(),
    0x57.toByte(), 0xae.toByte(), 0x05.toByte(), 0x66.toByte(), 0xbd.toByte(), 0x8e.toByte()
)

/** Stock certificate SHA-256 used in x-client-build generation. */
internal val STOCK_CERT_SHA256: String =
    "03d4b741632d136bc23e2de4c6adcdb7712f1980b619c183221801730bbd0410"

internal val BUILD_TAG_SALT: String = "oneroom"
internal val STREAM_REFERER: String = "https://sportslive.wine"

/** Placeholder / deprecation clip signatures that indicate a stream is dead. */
internal val DEPRECATION_HASHES: List<String> = listOf(
    "1c7de0bd3393702d9191801f15f88f8d",
    "9a0461bc39da389663bf3dbb17091d3f",
    "b164fbfb4347792950bdfbfb563d39d9",
    "/notice.mp4"
)

/** True when [url] looks like a MovieBox deprecation/placeholder clip. */
fun isPlaceholderUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val lower = url.lowercase()
    if (DEPRECATION_HASHES.any { h -> h in lower }) return true
    return "macdn.aoneroom.com" in lower && "/other/" in lower
}

/** MD5 hex digest of [input] string. */
fun md5(input: String): String = md5(input.toByteArray())

/** MD5 hex digest of [input] bytes. */
fun md5(input: ByteArray): String {
    val digest = MessageDigest.getInstance("MD5").digest(input)
    return digest.joinToString("") { "%02x".format(it) }
}

/** HMAC-MD5 of [data] using [key]. */
fun hmacMd5(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacMD5")
    mac.init(SecretKeySpec(key, "HmacMD5"))
    return mac.doFinal(data)
}

/** Base64 encode (standard, no line wrapping). */
fun base64Encode(input: ByteArray): String = java.util.Base64.getEncoder().encodeToString(input)

/** Base64 decode (standard). */
fun base64Decode(input: String): ByteArray = java.util.Base64.getDecoder().decode(input)

/** Base64 URL-safe decode with padding repair. */
fun base64UrlDecode(input: String): ByteArray {
    var padded = input
    val pad = (4 - input.length % 4) % 4
    if (pad > 0) padded += "=".repeat(pad)
    return java.util.Base64.getUrlDecoder().decode(padded)
}

/** Builds the canonical string used for x-tr-signature. */
fun buildCanonicalString(
    method: String,
    accept: String?,
    contentType: String?,
    url: String,
    body: String?,
    timestampMs: Long
): String {
    val parsed = java.net.URL(url)
    val path = parsed.path
    val q = sortedQueryString(url)
    val canonicalUrl = if (q.isNotEmpty()) "$path?$q" else path

    val (bodyLen, bodyHash) = if (body != null) {
        val bodyBytes = body.toByteArray()
        val truncated = bodyBytes.take(102_400)
        bodyBytes.size.toString() to md5(truncated)
    } else {
        "" to ""
    }

    return listOf(
        method.uppercase(),
        accept.orEmpty(),
        contentType.orEmpty(),
        bodyLen,
        timestampMs.toString(),
        bodyHash,
        canonicalUrl
    ).joinToString("\n")
}

/**
 * Generates the x-tr-signature header value:
 * `{timestamp_ms}|2|{base64(HMAC-MD5(canonical_string, DEFAULT_SECRET_BYTES))}`
 */
fun generateXTrSignature(
    method: String,
    accept: String?,
    contentType: String?,
    url: String,
    body: String?,
    timestampMs: Long
): String {
    val canonical = buildCanonicalString(method, accept, contentType, url, body, timestampMs)
    val mac = hmacMd5(DEFAULT_SECRET_BYTES, canonical.toByteArray())
    val sig = base64Encode(mac)
    return "$timestampMs|2|$sig"
}

/**
 * Generates the x-client-token header value:
 * `{timestamp_ms},{md5(reversed_timestamp_string)}`
 */
fun generateXClientToken(timestampMs: Long): String {
    val tsStr = timestampMs.toString()
    val revTs = tsStr.reversed()
    val h = md5(revTs)
    return "$tsStr,$h"
}

/**
 * Generates the x-client-build header value:
 * `{stamp}.{md5(STOCK_CERT_SHA256 + "oneroom" + stamp)}`
 * where stamp = `{seconds}{ms_part}000{random_0_999}`
 */
fun generateXClientBuild(timestampMs: Long): String {
    val randPart = (Math.random() * 1000).toInt()
    val stamp = "${timestampMs / 1000}${timestampMs % 1000 % 1000}000${String.format("%03d", randPart)}"
    val h = md5("$STOCK_CERT_SHA256$BUILD_TAG_SALT$stamp")
    return "$stamp.$h"
}

/**
 * Sorts query parameters by key (then by value order of appearance) and
 * returns `key=value&key=value` — no leading `?`.
 */
fun sortedQueryString(url: String): String {
    val parsed = java.net.URL(url)
    val query = parsed.query ?: return ""
    val pairs = query.split("&").flatMap { it.split("=", limit = 2) }
    val byKey = mutableMapOf<String, MutableList<String>>()
    for (i in pairs.indices step 2) {
        val k = pairs[i]
        val v = if (i + 1 < pairs.size) pairs[i + 1] else ""
        byKey.getOrPut(k) { mutableListOf() }.add(v)
    }
    return byKey.entries
        .sortedBy { it.key }
        .flatMap { (k, vs) -> vs.map { v -> "$k=$v" } }
        .joinToString("&")
}
