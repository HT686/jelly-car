package org.jellyfin.mobile.auto

import androidx.media3.common.MimeTypes
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class CarUrlHelperTest {

    @Test
    fun `redactUrl removes sensitive api_key from query params`() {
        val rawUrl = "https://jellyfin.example.com/Videos/123/stream.mp4?api_key=secret_token_12345&DeviceId=auto_unit_1"
        val redacted = CarUrlHelper.redactUrl(rawUrl)

        redacted shouldNotContain "secret_token_12345"
        redacted shouldContain "api_key=<redacted>"
        redacted shouldContain "DeviceId=auto_unit_1"
    }

    @Test
    fun `redactUrl removes token and x-emby-token`() {
        val rawUrl = "https://jellyfin.example.com/Items/abc/Images/Primary?token=my_secret_token&X-Emby-Token=another_secret"
        val redacted = CarUrlHelper.redactUrl(rawUrl)

        redacted shouldNotContain "my_secret_token"
        redacted shouldNotContain "another_secret"
        redacted shouldContain "token=<redacted>"
        redacted shouldContain "X-Emby-Token=<redacted>"
    }

    @Test
    fun `redactUrl removes embedded basic auth credentials`() {
        val rawUrl = "https://admin:supersecret@jellyfin.example.com/stream"
        val redacted = CarUrlHelper.redactUrl(rawUrl)

        redacted shouldNotContain "supersecret"
        redacted shouldContain "admin:<redacted>@jellyfin.example.com"
    }

    @Test
    fun `containerToMimeType correctly maps video formats`() {
        CarUrlHelper.containerToMimeType("mp4") shouldBe MimeTypes.VIDEO_MP4
        CarUrlHelper.containerToMimeType("m4v") shouldBe MimeTypes.VIDEO_MP4
        CarUrlHelper.containerToMimeType("mkv") shouldBe MimeTypes.VIDEO_MATROSKA
        CarUrlHelper.containerToMimeType("matroska") shouldBe MimeTypes.VIDEO_MATROSKA
        CarUrlHelper.containerToMimeType("webm") shouldBe MimeTypes.VIDEO_WEBM
        CarUrlHelper.containerToMimeType("m3u8") shouldBe MimeTypes.APPLICATION_M3U8
        CarUrlHelper.containerToMimeType("ts") shouldBe MimeTypes.VIDEO_MP2T
        CarUrlHelper.containerToMimeType("mpd") shouldBe MimeTypes.APPLICATION_MPD
        CarUrlHelper.containerToMimeType("avi") shouldBe MimeTypes.VIDEO_AVI
        CarUrlHelper.containerToMimeType("flv") shouldBe MimeTypes.VIDEO_FLV
        CarUrlHelper.containerToMimeType("mov") shouldBe MimeTypes.VIDEO_MP4
    }

    @Test
    fun `containerToMimeType correctly maps audio formats`() {
        CarUrlHelper.containerToMimeType("mp3") shouldBe MimeTypes.AUDIO_MPEG
        CarUrlHelper.containerToMimeType("aac") shouldBe MimeTypes.AUDIO_AAC
        CarUrlHelper.containerToMimeType("flac") shouldBe MimeTypes.AUDIO_FLAC
        CarUrlHelper.containerToMimeType("ogg") shouldBe MimeTypes.AUDIO_OGG
        CarUrlHelper.containerToMimeType("opus") shouldBe MimeTypes.AUDIO_OPUS
        CarUrlHelper.containerToMimeType("unknown_format") shouldBe null
    }

    @Test
    fun `checkHostConsistency handles matching and mismatching URLs`() {
        // Should not throw or crash on valid or invalid URLs
        CarUrlHelper.checkHostConsistency("https://jellyfin.example.com", "https://jellyfin.example.com/Videos/1/stream")
        CarUrlHelper.checkHostConsistency("https://jellyfin.example.com", "http://192.168.1.100:8096/Videos/1/stream")
        CarUrlHelper.checkHostConsistency(null, null)
        CarUrlHelper.checkHostConsistency("https://jellyfin.example.com", "")
    }
}
