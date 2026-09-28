package org.jellyfin.mobile.auto

import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.util.UnstableApi
import org.jellyfin.mobile.R
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber

/**
 * Vollwertiger Video-Player-Bildschirm für das Auto-Display in Jelly-Car.
 *
 * Nutzt das [NavigationTemplate] der Android Car App Library, um ein natives
 * Hardware-[android.view.Surface] bereitzustellen. Dieses Surface wird an den
 * [CarVideoPlayerManager] übergeben, wo der [androidx.media3.exoplayer.ExoPlayer]
 * die Videoframes hardwarebeschleunigt direkt auf das Armaturenbrett-Display projiziert.
 */
@UnstableApi
class CarVideoPlayerScreen(
    carContext: CarContext,
    private val playerManager: CarVideoPlayerManager,
    private val item: BaseItemDto,
    private val startPositionMs: Long = 0L,
) : Screen(carContext), SurfaceCallback, CarVideoPlayerManager.Listener {

    private var isPlaying = false
    private var currentPosMs = 0L
    private var durationMs = 0L
    private var lastToastTime = 0L

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                // SurfaceCallback beim Car AppManager registrieren
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(this@CarVideoPlayerScreen)
                playerManager.addListener(this@CarVideoPlayerScreen)
                playerManager.playVideo(item, startPositionMs)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                playerManager.removeListener(this@CarVideoPlayerScreen)
                playerManager.setSurface(null)
            }
        })
    }

    // --- SurfaceCallback Implementierung ---

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        Timber.d("Jelly-Car Surface verfügbar: ${surfaceContainer.surface}")
        playerManager.setSurface(surfaceContainer.surface)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        Timber.d("Jelly-Car Surface zerstört")
        playerManager.setSurface(null)
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        Timber.d("Jelly-Car Sichtbarer Bereich geändert: $visibleArea")
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        Timber.d("Jelly-Car Stabiler Bereich geändert: $stableArea")
    }

    override fun onClick(x: Float, y: Float) {
        // Bei Bildschirmberührung Wiedergabe umschalten und aktuellen Status anzeigen
        val now = System.currentTimeMillis()
        if (now - lastToastTime > 1500) {
            lastToastTime = now
            val timeText = "${formatTime(currentPosMs)} / ${formatTime(durationMs)}"
            val statusText = if (isPlaying) "Wiedergabe: $timeText" else "Pausiert: $timeText"
            CarToast.makeText(carContext, statusText, CarToast.LENGTH_SHORT).show()
        }
        playerManager.togglePlayPause()
    }

    // --- CarVideoPlayerManager.Listener Implementierung ---

    override fun onPlaybackStateChanged(isPlaying: Boolean, positionMs: Long, durationMs: Long) {
        this.isPlaying = isPlaying
        this.currentPosMs = positionMs
        this.durationMs = durationMs
        invalidate()
    }

    override fun onMediaItemTransition(item: BaseItemDto?) {
        invalidate()
    }

    override fun onError(message: String) {
        CarToast.makeText(carContext, message, CarToast.LENGTH_LONG).show()
    }

    // --- Template-Erstellung ---

    override fun onGetTemplate(): Template {
        val titleText = formatTitle(item)
        val timeText = "${formatTime(currentPosMs)} / ${formatTime(durationMs)}"

        // 1. Primäre Wiedergabesteuerung (Play, Pause, Vor-/Rücklauf)
        val playPauseIconRes = if (isPlaying) R.drawable.ic_pause_black_42dp else R.drawable.ic_play_black_42dp
        val playPauseIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, playPauseIconRes)).build()
        val rewindIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_rewind_black_32dp)).build()
        val forwardIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_fast_forward_black_32dp)).build()

        val mapActionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(rewindIcon)
                    .setOnClickListener {
                        playerManager.seekBy(-10_000L) // 10s zurück
                        CarToast.makeText(carContext, "-10s", CarToast.LENGTH_SHORT).show()
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(playPauseIcon)
                    .setOnClickListener {
                        playerManager.togglePlayPause()
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(forwardIcon)
                    .setOnClickListener {
                        playerManager.seekBy(30_000L) // 30s vor
                        CarToast.makeText(carContext, "+30s", CarToast.LENGTH_SHORT).show()
                    }
                    .build()
            )
            .build()

        // 2. Sekundäre Steuerleiste (Zurück, Format, Audiospuren)
        val actionStripBuilder = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle(playerManager.currentAspectRatio.displayName)
                    .setOnClickListener {
                        val newMode = playerManager.cycleAspectRatio()
                        CarToast.makeText(carContext, "Format: ${newMode.displayName}", CarToast.LENGTH_SHORT).show()
                        invalidate()
                    }
                    .build()
            )

        // Audio-Spur Umschalter falls mehrere vorhanden
        val audioTracks = playerManager.getAvailableAudioTracks()
        if (audioTracks.size > 1) {
            val audioIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_volume_white_24dp)).build()
            actionStripBuilder.addAction(
                Action.Builder()
                    .setIcon(audioIcon)
                    .setOnClickListener {
                        screenManager.push(CarTrackSelectionScreen(carContext, playerManager))
                    }
                    .build()
            )
        }

        // Stop & Zurück zur Mediathek
        actionStripBuilder.addAction(
            Action.Builder()
                .setTitle("Stop")
                .setOnClickListener {
                    playerManager.stop()
                    screenManager.pop()
                }
                .build()
        )

        return NavigationTemplate.Builder()
            .setMapActionStrip(mapActionStrip)
            .setActionStrip(actionStripBuilder.build())
            .setBackgroundColor(CarColor.PRIMARY)
            .build()
    }

    private fun formatTitle(item: BaseItemDto): String {
        return buildString {
            if (!item.seriesName.isNullOrEmpty()) {
                append(item.seriesName)
                if (item.parentIndexNumber != null && item.indexNumber != null) {
                    append(" S${item.parentIndexNumber}E${item.indexNumber}")
                }
                append(": ")
            }
            append(item.name.orEmpty())
        }
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return "00:00"
        val totalSeconds = ms / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}
