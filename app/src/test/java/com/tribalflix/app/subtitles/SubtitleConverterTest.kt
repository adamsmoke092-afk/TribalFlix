package com.tribalflix.app.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SubtitleConverter].
 * Pure JVM — no Android dependencies, runs under testDebugUnitTest.
 */
class SubtitleConverterTest {

    @Test
    fun `converts basic srt to webvtt`() {
        val srt = "1\n" +
            "00:00:01,000 --> 00:00:03,000\n" +
            "Hello world\n" +
            "\n" +
            "2\n" +
            "00:00:04,500 --> 00:00:06,000\n" +
            "Second line\n"

        val vtt = SubtitleConverter.toWebVtt(srt)

        assertTrue("must start with WEBVTT header", vtt.startsWith("WEBVTT"))
        assertTrue(vtt.contains("00:00:01.000 --> 00:00:03.000"))
        assertTrue(vtt.contains("00:00:04.500 --> 00:00:06.000"))
        assertTrue(vtt.contains("Hello world"))
        assertTrue(vtt.contains("Second line"))
    }

    @Test
    fun `strips utf8 bom`() {
        val srt = "\uFEFF1\n00:00:01,000 --> 00:00:02,000\nHi\n"
        val vtt = SubtitleConverter.toWebVtt(srt)
        assertFalse("BOM must be removed", vtt.contains("\uFEFF"))
        assertTrue(vtt.startsWith("WEBVTT"))
    }

    @Test
    fun `normalises crlf line endings`() {
        val srt = "1\r\n00:00:01,000 --> 00:00:02,000\r\nHi\r\n"
        val vtt = SubtitleConverter.toWebVtt(srt)
        assertFalse("no carriage returns", vtt.contains("\r"))
        assertTrue(vtt.contains("00:00:01.000 --> 00:00:02.000"))
    }

    @Test
    fun `drops sequence-number-only lines`() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nHi\n"
        val vtt = SubtitleConverter.toWebVtt(srt)
        // The bare "1" index line must not survive as a cue body.
        val body = vtt.removePrefix("WEBVTT").trim()
        assertFalse(body.startsWith("1\n"))
        assertTrue(body.startsWith("00:00:01.000"))
    }

    @Test
    fun `comma timecodes become dots`() {
        val srt = "1\n00:00:01,000 --> 00:00:03,500\nX\n"
        val vtt = SubtitleConverter.toWebVtt(srt)
        assertTrue(vtt.contains("00:00:01.000 --> 00:00:03.500"))
    }
}
