package org.jellyfin.mobile.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import timber.log.Timber

/**
 * Now-Playing Bildschirm für Audiotitel in Android Auto.
 *
 * Zeigt Albumcover, Songtitel, Interpret, Albumname, aktuelle Spielzeit
 * sowie Steuerungselemente (Play/Pause, Vorheriger Titel, Nächster Titel) an.
 */
@UnstableApi
class CarAudioPlayerScreen(
    carContext: CarContext,
    private val apiClient: ApiClient,
    private val playerManager: CarVideoPlayerManager,
    private val imageHelper: CarImageHelper,
) : Screen(carContext), CarVideoPlayerManager.Listener {

    private var isPlaying = false
    private var currentPosMs = 0L
    private var durationMs = 0L
    private var currentItem: BaseItemDto? = null
    private var coverIcon: CarIcon? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                playerManager.addListener(this@CarAudioPlayerScreen)
                currentItem = playerManager.currentItem
                loadCover()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                playerManager.removeListener(this@CarAudioPlayerScreen)
            }
        })
    }

    private fun loadCover() {
        val item = currentItem ?: return
        lifecycleScope.launch {
            try {
                // Versuche zuerst das Album-Cover, danach das Item-Cover
                val targetId = item.albumId ?: item.id
                val icon = imageHelper.loadCarIcon(
                    itemId = targetId,
                    imageType = ImageType.PRIMARY,
                    fallbackResId = R.drawable.ic_album,
                    maxHeight = 400,
                    maxWidth = 400,
                )
                coverIcon = icon
                invalidate()
            } catch (e: Exception) {
                Timber.w(e, "Konnte Albumcover für Audioplayer nicht laden")
            }
        }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean, positionMs: Long, durationMs: Long) {
        this.isPlaying = isPlaying
        this.currentPosMs = positionMs
        this.durationMs = durationMs
        invalidate()
    }

    override fun onMediaItemTransition(item: BaseItemDto?) {
        this.currentItem = item
        loadCover()
        invalidate()
    }

    override fun onError(message: String) {
        Timber.e("Audio-Wiedergabefehler: $message")
        invalidate()
    }

    override fun onGetTemplate(): Template {
        val item = currentItem

        val paneBuilder = Pane.Builder()

        if (item == null) {
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("Keine aktive Wiedergabe")
                    .addText("Wähle einen Song aus der Mediathek aus.")
                    .build()
            )
            return PaneTemplate.Builder(paneBuilder.build())
                .setTitle("Musikplayer")
                .setHeaderAction(Action.BACK)
                .build()
        }

        // 1. Titel & Interpret
        val artistText = item.artists?.joinToString(", ") ?: item.albumArtist ?: "Unbekannter Interpret"
        paneBuilder.addRow(
            Row.Builder()
                .setTitle(item.name.orEmpty())
                .addText(artistText)
                .build()
        )

        // 2. Album & Laufzeit
        val albumName = item.album ?: "Album"
        val timeString = "${formatTime(currentPosMs)} / ${formatTime(durationMs)}"
        paneBuilder.addRow(
            Row.Builder()
                .setTitle(albumName)
                .addText(timeString)
                .build()
        )

        // 3. Steuerungsbuttons (Vorheriger, Play/Pause, Nächster)
        if (playerManager.hasPrevious()) {
            paneBuilder.addAction(
                Action.Builder()
                    .setTitle("Vorheriger")
                    .setOnClickListener {
                        playerManager.playPrevious()
                    }
                    .build()
            )
        }

        val playPauseTitle = if (isPlaying) "Pause" else "Abspielen"
        paneBuilder.addAction(
            Action.Builder()
                .setTitle(playPauseTitle)
                .setOnClickListener {
                    playerManager.togglePlayPause()
                }
                .build()
        )

        if (playerManager.hasNext()) {
            paneBuilder.addAction(
                Action.Builder()
                    .setTitle("Nächster")
                    .setOnClickListener {
                        playerManager.playNext()
                    }
                    .build()
            )
        }

        // Cover-Artwork
        coverIcon?.let {
            paneBuilder.setImage(it)
        }

        return PaneTemplate.Builder(paneBuilder.build())
            .setTitle(item.name.orEmpty())
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun formatTime(ms: Long): String {
        val totalSecs = (ms / 1000).coerceAtLeast(0)
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return "%d:%02d".format(mins, secs)
    }
}
