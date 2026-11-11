package com.tribalflix.app.subtitles

/**
 * Converts SubRip (.srt) text to WebVTT (.vtt).
 *
 * ExoPlayer's default extractors do not parse sidecar .srt files; they do
 * parse WebVTT. MovieBox captions come as SRT, so we convert in-app before
 * handing the track to ExoPlayer as a [SingleSampleMediaSource].
 *
 * Pure java.lang/java.util.regex — no Android dependencies, JVM unit-testable.
 *
 * Conversion steps:
 *  1. Strip a leading UTF-8 BOM (\uFEFF) if present.
 *  2. Normalise all line breaks to \n.
 *  3. Prepend the mandatory "WEBVTT\n\n" header.
 *  4. Convert SRT timecodes "HH:MM:SS,mmm" to WebVTT "HH:MM:SS.mmm".
 *  5. Drop SRT sequence-number-only lines (WebVTT has no such concept; blank
 *     line separation is preserved instead).
 */
object SubtitleConverter {

    private val timecodeRegex =
        Regex("(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})")

    /** Converts [srt] SubRip text to WebVTT text. */
    fun toWebVtt(srt: String): String {
        val text = if (srt.startsWith("\uFEFF")) srt.substring(1) else srt
            .replace("\r\n", "\n")
            .replace("\r", "\n")

        val lines = text.split("\n")
        val out = StringBuilder("WEBVTT\n\n")

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                out.append("\n")
                continue
            }
            // Skip pure sequence-number lines (e.g. "1", "42").
            if (trimmed.all { it.isDigit() }) continue

            out.append(timecodeRegex.replace(trimmed) { match ->
                "${match.groupValues[1]}:${match.groupValues[2]}:${match.groupValues[3]}.${match.groupValues[4]}"
            })
            out.append("\n")
        }

        return out.toString()
    }
}
