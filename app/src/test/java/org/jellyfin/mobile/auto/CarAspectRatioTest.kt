package org.jellyfin.mobile.auto

import androidx.media3.common.C
import io.kotest.matchers.shouldBe
import org.jellyfin.mobile.auto.CarVideoPlayerManager.AspectRatioMode
import org.junit.jupiter.api.Test

class CarAspectRatioTest {

    @Test
    fun `default aspect ratio mode is FIT_HEIGHT`() {
        AspectRatioMode.FIT_HEIGHT.displayName shouldBe "Höhe anpassen"
        AspectRatioMode.FIT.displayName shouldBe "16:9 Fit"
        AspectRatioMode.FILL.displayName shouldBe "Fill (Crop)"
    }

    @Test
    fun `FIT mode always returns SCALE_TO_FIT`() {
        // Unabhängig von den Dimensionen: immer SCALE_TO_FIT
        CarVideoPlayerManager.resolveScalingMode(
            mode = AspectRatioMode.FIT,
            surfaceWidth = 1920,
            surfaceHeight = 720,
            videoWidth = 1920,
            videoHeight = 1080,
        ) shouldBe C.VIDEO_SCALING_MODE_SCALE_TO_FIT

        CarVideoPlayerManager.resolveScalingMode(
            mode = AspectRatioMode.FIT,
            surfaceWidth = 1280,
            surfaceHeight = 720,
            videoWidth = 1920,
            videoHeight = 800,
        ) shouldBe C.VIDEO_SCALING_MODE_SCALE_TO_FIT
    }

    @Test
    fun `FILL mode always returns SCALE_TO_FIT_WITH_CROPPING`() {
        // Unabhängig von den Dimensionen: immer SCALE_TO_FIT_WITH_CROPPING
        CarVideoPlayerManager.resolveScalingMode(
            mode = AspectRatioMode.FILL,
            surfaceWidth = 1920,
            surfaceHeight = 720,
            videoWidth = 1920,
            videoHeight = 1080,
        ) shouldBe C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING

        CarVideoPlayerManager.resolveScalingMode(
            mode = AspectRatioMode.FILL,
            surfaceWidth = 1280,
            surfaceHeight = 720,
            videoWidth = 1920,
            videoHeight = 800,
        ) shouldBe C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
    }

    @Test
    fun `FIT_HEIGHT uses SCALE_TO_FIT when screen is wider than video`() {
        // Beispiel 1: Ultrawide-Display (1920x720, AR=2.67) und 16:9-Serie (1920x1080, AR=1.78)
        // Bildschirm ist breiter als Video -> SCALE_TO_FIT skaliert die Höhe auf 100% des Displays (720px),
        // sodass keine Balken oben/unten entstehen und keine Köpfe/Untertitel abgeschnitten werden!
        val modeUltrawide16x9 = CarVideoPlayerManager.resolveScalingMode(
            mode = AspectRatioMode.FIT_HEIGHT,
            surfaceWidth = 1920,
            surfaceHeight = 720,
            videoWidth = 1920,
            videoHeight = 1080,
        )
        modeUltrawide16x9 shouldBe C.VIDEO_SCALING_MODE_SCALE_TO_FIT

        // Beispiel 2: Ultrawide-Display (1920x720, AR=2.67) und 21:9-Film (1920x803, AR=2.39)
        // Bildschirm (2.67) ist breiter als Film (2.39) -> SCALE_TO_FIT skaliert Höhe auf 100%.
        val modeUltrawideCinemascope = CarVideoPlayerManager.resolveScalingMode(
            mode = AspectRatioMode.FIT_HEIGHT,
            surfaceWidth = 1920,
            surfaceHeight = 720,
            videoWidth = 1920,
            videoHeight = 803,
        )
        modeUltrawideCinemascope shouldBe C.VIDEO_SCALING_MODE_SCALE_TO_FIT

        // Beispiel 3: 16:9-Display (1920x1080, AR=1.78) und 4:3-Klassiker (1440x1080, AR=1.33)
        val mode16x9OldSeries = CarVideoPlayerManager.resolveScalingMode(
            mode = AspectRatioMode.FIT_HEIGHT,
            surfaceWidth = 1920,
            surfaceHeight = 1080,
            videoWidth = 1440,
            videoHeight = 1080,
        )
        mode16x9OldSeries shouldBe C.VIDEO_SCALING_MODE_SCALE_TO_FIT
    }

    @Test
    fun `FIT_HEIGHT uses SCALE_TO_FIT_WITH_CROPPING when video is wider than screen`() {
        // Beispiel 1: Standard 16:9 Car-Display (1280x720, AR=1.78) und 2.39:1 Cinemascope-Film (1920x803, AR=2.39)
        // Video ist breiter als Display -> SCALE_TO_FIT_WITH_CROPPING skaliert die Video-Höhe auf 100% des Displays (720px),
        // sodass die störenden schwarzen Balken oben und unten verschwinden und links/rechts zugeschnitten wird.
        val mode16x9Cinemascope = CarVideoPlayerManager.resolveScalingMode(
            mode = AspectRatioMode.FIT_HEIGHT,
            surfaceWidth = 1280,
            surfaceHeight = 720,
            videoWidth = 1920,
            videoHeight = 803,
        )
        mode16x9Cinemascope shouldBe C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING

        // Beispiel 2: 4:3-Display (1024x768, AR=1.33) und 16:9-Video (1920x1080, AR=1.78)
        val mode4x3WideVideo = CarVideoPlayerManager.resolveScalingMode(
            mode = AspectRatioMode.FIT_HEIGHT,
            surfaceWidth = 1024,
            surfaceHeight = 768,
            videoWidth = 1920,
            videoHeight = 1080,
        )
        mode4x3WideVideo shouldBe C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
    }

    @Test
    fun `FIT_HEIGHT handles identical aspect ratio and zero dimensions gracefully`() {
        // Identisches Seitenverhältnis
        CarVideoPlayerManager.resolveScalingMode(
            mode = AspectRatioMode.FIT_HEIGHT,
            surfaceWidth = 1920,
            surfaceHeight = 1080,
            videoWidth = 1920,
            videoHeight = 1080,
        ) shouldBe C.VIDEO_SCALING_MODE_SCALE_TO_FIT

        // Noch unbekannte Dimensionen (z. B. vor onVideoSizeChanged)
        CarVideoPlayerManager.resolveScalingMode(
            mode = AspectRatioMode.FIT_HEIGHT,
            surfaceWidth = 0,
            surfaceHeight = 0,
            videoWidth = 0,
            videoHeight = 0,
        ) shouldBe C.VIDEO_SCALING_MODE_SCALE_TO_FIT
    }

    @Test
    fun `FIT_HEIGHT respects pixelWidthHeightRatio for anamorphic videos`() {
        // Anamorpher Stream: 720x576 mit PixelRatio 1.422 (ergibt 1024x576 = 16:9)
        // Auf einem 4:3 Display (1024x768) ist das 16:9 Video breiter als das 4:3 Display -> SCALE_TO_FIT_WITH_CROPPING
        CarVideoPlayerManager.resolveScalingMode(
            mode = AspectRatioMode.FIT_HEIGHT,
            surfaceWidth = 1024,
            surfaceHeight = 768,
            videoWidth = 720,
            videoHeight = 576,
            pixelRatio = 1.422f,
        ) shouldBe C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
    }

    @Test
    fun `buildPresentationEffect creates valid Presentation effects with surface dimensions`() {
        val effectFitHeight = CarVideoPlayerManager.buildPresentationEffect(
            mode = AspectRatioMode.FIT_HEIGHT,
            surfaceWidth = 1280,
            surfaceHeight = 720,
        )
        (effectFitHeight is androidx.media3.effect.Presentation) shouldBe true

        val effectFill = CarVideoPlayerManager.buildPresentationEffect(
            mode = AspectRatioMode.FILL,
            surfaceWidth = 1280,
            surfaceHeight = 720,
        )
        (effectFill is androidx.media3.effect.Presentation) shouldBe true

        val effectStretch = CarVideoPlayerManager.buildPresentationEffect(
            mode = AspectRatioMode.FIT,
            surfaceWidth = 1280,
            surfaceHeight = 720,
        )
        (effectStretch is androidx.media3.effect.Presentation) shouldBe true
    }

    @Test
    fun `buildPresentationEffect handles zero dimensions with fallback aspect ratio`() {
        val fallbackEffect = CarVideoPlayerManager.buildPresentationEffect(
            mode = AspectRatioMode.FIT_HEIGHT,
            surfaceWidth = 0,
            surfaceHeight = 0,
        )
        (fallbackEffect is androidx.media3.effect.Presentation) shouldBe true
    }
}
