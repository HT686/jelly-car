package org.jellyfin.mobile.auto

import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.player.source.MediaSourceResolver
import org.jellyfin.mobile.player.source.RemoteJellyfinMediaSource
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.universalAudioApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.extensions.inWholeTicks
import org.jellyfin.sdk.model.extensions.ticks
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

/**
 * Zentraler Video-Playback-Manager für Jelly-Car auf Android Auto.
 *
 * Verwaltet den [ExoPlayer], bindet das Car-Head-Unit-Hardware-Surface an,
 * löst direkte Streams sowie HLS-Transcodes über die Jellyfin-API auf und
 * meldet Wiedergabestatus (Start, Fortschritt alle 10s, Stop, Mark Played)
 * an den Jellyfin-Server zurück, sodass "Weiter ansehen" und der
 * Wiedergabefortschritt stets synchronisiert bleiben.
 */
@UnstableApi
class CarVideoPlayerManager private constructor(
    private val context: Context,
    private val apiClient: ApiClient,
    private val mediaSourceResolver: MediaSourceResolver,
) {
    companion object {
        @Volatile
        private var instance: CarVideoPlayerManager? = null

        fun getInstance(
            context: Context,
            apiClient: ApiClient,
            mediaSourceResolver: MediaSourceResolver,
        ): CarVideoPlayerManager {
            return instance ?: synchronized(this) {
                instance ?: CarVideoPlayerManager(
                    context.applicationContext,
                    apiClient,
                    mediaSourceResolver,
                ).also { instance = it }
            }
        }
    }

    interface Listener {
        fun onPlaybackStateChanged(isPlaying: Boolean, positionMs: Long, durationMs: Long)
        fun onMediaItemTransition(item: BaseItemDto?)
        fun onError(message: String)
    }

    enum class AspectRatioMode(val displayName: String, val scalingMode: Int) {
        FIT("16:9 Fit", C.VIDEO_SCALING_MODE_SCALE_TO_FIT),
        FILL("Fill (Crop)", C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING),
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private var progressReportingJob: Job? = null
    private val listeners = CopyOnWriteArrayList<Listener>()

    var currentItem: BaseItemDto? = null
        private set
    var currentMediaSource: RemoteJellyfinMediaSource? = null
        private set
    var currentAspectRatio: AspectRatioMode = AspectRatioMode.FIT
        private set

    var playbackQueue: List<BaseItemDto> = emptyList()
        private set
    var queueIndex: Int = -1
        private set

    fun hasNext(): Boolean = queueIndex in playbackQueue.indices && queueIndex < playbackQueue.lastIndex
    fun hasPrevious(): Boolean = queueIndex > 0

    private var activeSurface: Surface? = null

    // ExoPlayer-Instanz optimiert für Fahrzeug-Audio und Video
    val exoPlayer: ExoPlayer by lazy {
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Jelly-Car/AndroidAuto")
            .setAllowCrossProtocolRedirects(true)

        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build().apply {
                videoScalingMode = currentAspectRatio.scalingMode
                addListener(playerListener)
            }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            notifyPlaybackState()
            if (isPlaying) {
                startProgressReporting()
            } else {
                stopProgressReporting(false)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            notifyPlaybackState()
            if (playbackState == Player.STATE_ENDED) {
                onPlaybackCompleted()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Timber.e(error, "Jelly-Car Player Fehler: ${error.message}")
            listeners.forEach { it.onError(error.localizedMessage ?: "Wiedergabefehler aufgetreten") }
        }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onPlaybackStateChanged(
            exoPlayer.isPlaying,
            exoPlayer.currentPosition,
            exoPlayer.duration.coerceAtLeast(0),
        )
        listener.onMediaItemTransition(currentItem)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * Bindet das vom Car App Host gelieferte Surface für die Videodarstellung an.
     */
    fun setSurface(surface: Surface?) {
        Timber.d("Jelly-Car: setSurface: $surface")
        activeSurface = surface
        if (surface != null && surface.isValid) {
            exoPlayer.setVideoSurface(surface)
        } else {
            exoPlayer.clearVideoSurface()
        }
    }

    fun playQueue(items: List<BaseItemDto>, startIndex: Int = 0) {
        if (items.isEmpty()) return
        playbackQueue = items
        queueIndex = startIndex.coerceIn(items.indices)
        playMedia(items[queueIndex], 0L)
    }

    fun playNext() {
        if (hasNext()) {
            queueIndex++
            playMedia(playbackQueue[queueIndex], 0L)
        }
    }

    fun playPrevious() {
        if (hasPrevious()) {
            queueIndex--
            playMedia(playbackQueue[queueIndex], 0L)
        }
    }

    /**
     * Startet die Wiedergabe eines Films, einer Episode oder eines Videos.
     *
     * @param item Das Jellyfin BaseItemDto
     * @param startPositionMs Startposition in Millisekunden (z. B. für "Weiter ansehen")
     */
    fun playVideo(item: BaseItemDto, startPositionMs: Long = 0L) {
        playbackQueue = listOf(item)
        queueIndex = 0
        playMedia(item, startPositionMs)
    }

    /**
     * Startet die Wiedergabe eines beliebigen Mediums (Audio oder Video).
     */
    fun playMedia(item: BaseItemDto, startPositionMs: Long = 0L) {
        Timber.i("Jelly-Car: Starte Wiedergabe für Item: ${item.name} (${item.id}) bei ${startPositionMs}ms")
        currentItem = item
        listeners.forEach { it.onMediaItemTransition(item) }

        scope.launch {
            try {
                // 1. Stream-Auflösung via MediaSourceResolver
                val resolvedResult = withContext(Dispatchers.IO) {
                    mediaSourceResolver.resolveMediaSource(
                        itemId = item.id,
                        startTime = startPositionMs.milliseconds,
                    )
                }

                val remoteSource = resolvedResult.getOrNull()
                currentMediaSource = remoteSource

                // 2. Ermitteln der Streaming-URL
                val streamUrl = determineStreamUrl(item, remoteSource)
                Timber.d("Jelly-Car Stream-URL: $streamUrl")

                val mediaItemBuilder = MediaItem.Builder()
                    .setUri(streamUrl.toUri())
                    .setMediaId(item.id.toString())

                val mediaItem = mediaItemBuilder.build()

                // 3. Vorbereitung & Start des Players
                exoPlayer.setMediaItem(mediaItem)
                if (startPositionMs > 0L) {
                    exoPlayer.seekTo(startPositionMs)
                }
                exoPlayer.prepare()
                exoPlayer.play()

                // Surface sicherstellen (nur bei Video)
                val isAudio = item.type == BaseItemKind.AUDIO || item.mediaType == MediaType.AUDIO
                if (!isAudio) {
                    activeSurface?.let {
                        if (it.isValid) exoPlayer.setVideoSurface(it)
                    }
                }

                // 4. Server-Reporting: Playback Start
                reportPlaybackStart(item, remoteSource, startPositionMs)
                startProgressReporting()
            } catch (e: Exception) {
                Timber.e(e, "Fehler beim Vorbereiten des Medien-Streams")
                listeners.forEach { it.onError("Fehler beim Laden: ${e.localizedMessage}") }
            }
        }
    }

    /**
     * Erstellt die optimale Stream-URL (HLS Transcode, Universal Audio oder Direct Stream).
     */
    private fun determineStreamUrl(item: BaseItemDto, remoteSource: RemoteJellyfinMediaSource?): String {
        val apiKey = apiClient.accessToken
        val deviceId = apiClient.deviceInfo.id

        // Falls RemoteSource aufgelöst wurde und Transcoding anbietet
        if (remoteSource != null) {
            val transUrl = remoteSource.sourceInfo.transcodingUrl
            if (!transUrl.isNullOrEmpty()) {
                val fullTransUrl = apiClient.createUrl(transUrl)
                return appendAuthParam(fullTransUrl, apiKey)
            }
        }

        // Falls Audio-Item (Musik, Song, Audiobook)
        val isAudio = item.type == BaseItemKind.AUDIO || item.mediaType == MediaType.AUDIO
        if (isAudio) {
            val audioUrl = apiClient.universalAudioApi.getUniversalAudioStreamUrl(
                itemId = item.id,
                deviceId = deviceId,
                maxStreamingBitrate = 140000000,
                container = listOf(
                    "opus",
                    "mp3|mp3",
                    "aac",
                    "m4a",
                    "m4b|aac",
                    "flac",
                    "webma",
                    "webm",
                    "wav",
                    "ogg",
                ),
                transcodingProtocol = MediaStreamProtocol.HLS,
                transcodingContainer = "ts",
                audioCodec = "aac",
                enableRemoteMedia = true,
            )
            return appendAuthParam(audioUrl, apiKey)
        }

        // Standardmäßiger Direct Stream über videosApi für Filme, Serien etc.
        val directUrl = apiClient.videosApi.getVideoStreamUrl(
            itemId = item.id,
            static = false,
            deviceId = deviceId,
            playSessionId = remoteSource?.playSessionId,
        )
        return appendAuthParam(directUrl, apiKey)
    }

    private fun appendAuthParam(url: String, apiKey: String?): String {
        if (apiKey.isNullOrEmpty() || url.contains("api_key", ignoreCase = true)) {
            return url
        }
        val separator = if (url.contains("?")) "&" else "?"
        return "$url${separator}api_key=$apiKey"
    }

    /**
     * Umschalten zwischen Pause und Wiedergabe.
     */
    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    fun play() {
        exoPlayer.play()
    }

    fun pause() {
        exoPlayer.pause()
        reportPlaybackProgress(isPaused = true)
    }

    /**
     * Schneller Vor-/Rücklauf in Millisekunden (z.B. +30.000ms oder -10.000ms).
     */
    fun seekBy(deltaMs: Long) {
        val newPos = (exoPlayer.currentPosition + deltaMs).coerceIn(0L, exoPlayer.duration.coerceAtLeast(0L))
        exoPlayer.seekTo(newPos)
        notifyPlaybackState()
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs.coerceIn(0L, exoPlayer.duration.coerceAtLeast(0L)))
        notifyPlaybackState()
    }

    /**
     * Wechselt zwischen Bildformaten (16:9 Fit oder Fill Crop).
     */
    fun cycleAspectRatio(): AspectRatioMode {
        currentAspectRatio = when (currentAspectRatio) {
            AspectRatioMode.FIT -> AspectRatioMode.FILL
            AspectRatioMode.FILL -> AspectRatioMode.FIT
        }
        exoPlayer.videoScalingMode = currentAspectRatio.scalingMode
        return currentAspectRatio
    }

    /**
     * Beendet die Wiedergabe und sendet den Stop-Report an Jellyfin.
     */
    fun stop() {
        stopProgressReporting(true)
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        currentItem = null
        currentMediaSource = null
        notifyPlaybackState()
    }

    /**
     * Gibt verfügbare Audiospuren zurück.
     */
    fun getAvailableAudioTracks(): List<String> {
        val tracks = exoPlayer.currentTracks
        val result = mutableListOf<String>()
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val label = format.label ?: format.language ?: "Audio Spur ${result.size + 1}"
                    result.add(label)
                }
            }
        }
        return result
    }

    /**
     * Wählt eine bestimmte Audiospur aus.
     */
    fun selectAudioTrack(trackIndex: Int) {
        var count = 0
        val tracks = exoPlayer.currentTracks
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    if (count == trackIndex) {
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(
                                androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, i)
                            )
                            .build()
                        return
                    }
                    count++
                }
            }
        }
    }

    // --- Server Reporting ---

    private fun startProgressReporting() {
        progressReportingJob?.cancel()
        progressReportingJob = scope.launch {
            while (isActive) {
                delay(10_000L) // Alle 10 Sekunden
                if (exoPlayer.isPlaying) {
                    reportPlaybackProgress(isPaused = false)
                }
            }
        }
    }

    private fun stopProgressReporting(isStop: Boolean) {
        progressReportingJob?.cancel()
        progressReportingJob = null
        if (isStop) {
            reportPlaybackStop()
        }
    }

    private fun reportPlaybackStart(item: BaseItemDto, remoteSource: RemoteJellyfinMediaSource?, startPosMs: Long) {
        scope.launch(Dispatchers.IO) {
            try {
                apiClient.playStateApi.reportPlaybackStart(
                    PlaybackStartInfo(
                        itemId = item.id,
                        playSessionId = remoteSource?.playSessionId,
                        playMethod = remoteSource?.playMethod ?: PlayMethod.DIRECT_PLAY,
                        isPaused = false,
                        isMuted = false,
                        canSeek = true,
                        positionTicks = startPosMs.milliseconds.inWholeTicks,
                        repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                    )
                )
            } catch (e: Exception) {
                Timber.w(e, "Konnte PlaybackStart nicht an Jellyfin senden")
            }
        }
    }

    private fun reportPlaybackProgress(isPaused: Boolean) {
        val item = currentItem ?: return
        val currentTicks = exoPlayer.currentPosition.milliseconds.inWholeTicks
        scope.launch(Dispatchers.IO) {
            try {
                apiClient.playStateApi.reportPlaybackProgress(
                    PlaybackProgressInfo(
                        itemId = item.id,
                        playSessionId = currentMediaSource?.playSessionId,
                        playMethod = currentMediaSource?.playMethod ?: PlayMethod.DIRECT_PLAY,
                        isPaused = isPaused,
                        isMuted = false,
                        canSeek = true,
                        positionTicks = currentTicks,
                        repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                    )
                )
            } catch (e: Exception) {
                Timber.w(e, "Konnte PlaybackProgress nicht an Jellyfin senden")
            }
        }
    }

    private fun reportPlaybackStop() {
        val item = currentItem ?: return
        val currentTicks = exoPlayer.currentPosition.milliseconds.inWholeTicks
        scope.launch(Dispatchers.IO) {
            try {
                apiClient.playStateApi.reportPlaybackStopped(
                    PlaybackStopInfo(
                        itemId = item.id,
                        playSessionId = currentMediaSource?.playSessionId,
                        positionTicks = currentTicks,
                        failed = false,
                    )
                )
            } catch (e: Exception) {
                Timber.w(e, "Konnte PlaybackStopped nicht an Jellyfin senden")
            }
        }
    }

    private fun onPlaybackCompleted() {
        val item = currentItem ?: return
        Timber.i("Jelly-Car: Video vollständig wiedergegeben: ${item.name}")
        scope.launch(Dispatchers.IO) {
            try {
                apiClient.playStateApi.reportPlaybackStopped(
                    PlaybackStopInfo(
                        itemId = item.id,
                        playSessionId = currentMediaSource?.playSessionId,
                        positionTicks = exoPlayer.duration.milliseconds.inWholeTicks,
                        failed = false,
                    )
                )
                apiClient.playStateApi.markPlayedItem(itemId = item.id)
            } catch (e: Exception) {
                Timber.w(e, "Fehler beim Markieren des Mediums als abgespielt")
            }
        }

        if (hasNext()) {
            playNext()
        }
    }

    private fun notifyPlaybackState() {
        val isPlaying = exoPlayer.isPlaying
        val position = exoPlayer.currentPosition
        val duration = exoPlayer.duration.coerceAtLeast(0L)
        listeners.forEach { it.onPlaybackStateChanged(isPlaying, position, duration) }
    }

    fun release() {
        stop()
        exoPlayer.release()
    }
}
