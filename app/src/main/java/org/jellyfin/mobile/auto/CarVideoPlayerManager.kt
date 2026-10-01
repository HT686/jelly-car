package org.jellyfin.mobile.auto

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.view.Surface
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.player.deviceprofile.DeviceProfileBuilder
import org.jellyfin.mobile.player.source.MediaSourceResolver
import org.jellyfin.mobile.player.source.RemoteJellyfinMediaSource
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.universalAudioApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaProtocol
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
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
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
 *
 * Behebt Race Conditions bei der Surface-Initialisierung: Verhindert Audio-Wiedergabe
 * ohne Videobild durch Verweilen im Vorbereitungszustand bis ein valides Surface vorliegt.
 * Bietet umfassende Track-Diagnostik und redigiert vertrauliche Anmeldedaten im Logging.
 */
@UnstableApi
class CarVideoPlayerManager private constructor(
    private val context: Context,
    private val apiClient: ApiClient,
    private val mediaSourceResolver: MediaSourceResolver,
) : KoinComponent {
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

    private val deviceProfileBuilder: DeviceProfileBuilder by inject()
    private val mediaSourceFactory: MediaSource.Factory by inject()

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

    private var currentRetryLevel = 0
    private var currentStartPositionMs = 0L

    fun hasNext(): Boolean = queueIndex in playbackQueue.indices && queueIndex < playbackQueue.lastIndex
    fun hasPrevious(): Boolean = queueIndex > 0

    private var activeSurface: Surface? = null
    private var isWaitingForSurface = false
    private var lastLoadedStreamUrl: String? = null
    private var lastLoadedMimeType: String? = null

    // ExoPlayer-Instanz optimiert für Fahrzeug-Audio und Video
    val exoPlayer: ExoPlayer by lazy {
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA)
            .build()

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build().apply {
                videoScalingMode = currentAspectRatio.scalingMode
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                    .build()
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

        override fun onTracksChanged(tracks: Tracks) {
            super.onTracksChanged(tracks)
            logTrackDiagnostics(tracks)
        }

        override fun onPlayerError(error: PlaybackException) {
            val item = currentItem
            val isAudio = item?.type == BaseItemKind.AUDIO || item?.mediaType == MediaType.AUDIO
            val streamUrlRedacted = CarUrlHelper.redactUrl(lastLoadedStreamUrl)

            Timber.e(
                error,
                "Jelly-Car Player Fehler: code=%d (%s), message='%s', cause='%s', item='%s' (id=%s, isAudio=%b), " +
                    "playMethod=%s, mime=%s, url=%s, surfacePresent=%b, surfaceValid=%b, playerState=%d",
                error.errorCode,
                error.errorCodeName,
                error.message,
                error.cause?.message,
                item?.name,
                item?.id,
                isAudio,
                currentMediaSource?.playMethod,
                lastLoadedMimeType,
                streamUrlRedacted,
                activeSurface != null,
                activeSurface?.isValid == true,
                exoPlayer.playbackState,
            )

            // Falls Video mit Fehler fehlschlägt und noch nicht alle Retries aufgebraucht sind
            if (!isAudio && item != null && currentRetryLevel < 3) {
                Timber.w("Versuche automatischen Fallback-Retry für Video (Stufe ${currentRetryLevel + 1})")
                val resumePos = exoPlayer.currentPosition.coerceAtLeast(currentStartPositionMs)
                playMedia(item, resumePos, currentRetryLevel + 1)
                return
            }

            listeners.forEach { it.onError("Wiedergabefehler: ${error.errorCodeName}") }
        }
    }

    private fun logTrackDiagnostics(tracks: Tracks) {
        Timber.i("=== Jelly-Car Track Diagnose Start ===")
        var videoTrackCount = 0
        var audioTrackCount = 0
        var selectedVideoTrack = false
        var selectedAudioTrack = false

        for (group in tracks.groups) {
            val trackType = group.type
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val isSelected = group.isTrackSelected(i)
                when (trackType) {
                    C.TRACK_TYPE_VIDEO -> {
                        videoTrackCount++
                        if (isSelected) selectedVideoTrack = true
                        Timber.i(
                            "VIDEO Track #%d: mime=%s, containerMime=%s, codec=%s, size=%dx%d, fps=%.2f, bitrate=%d, selected=%b, supported=%b",
                            videoTrackCount,
                            format.sampleMimeType,
                            format.containerMimeType,
                            format.codecs,
                            format.width,
                            format.height,
                            format.frameRate,
                            format.bitrate,
                            isSelected,
                            group.isTrackSupported(i),
                        )
                    }
                    C.TRACK_TYPE_AUDIO -> {
                        audioTrackCount++
                        if (isSelected) selectedAudioTrack = true
                        Timber.i(
                            "AUDIO Track #%d: mime=%s, codec=%s, channels=%d, sampleRate=%d, lang=%s, selected=%b, supported=%b",
                            audioTrackCount,
                            format.sampleMimeType,
                            format.codecs,
                            format.channelCount,
                            format.sampleRate,
                            format.language,
                            isSelected,
                            group.isTrackSupported(i),
                        )
                    }
                }
            }
        }
        Timber.i(
            "=== Track Zusammenfassung: Video-Tracks: %d (ausgewählt=%b), Audio-Tracks: %d (ausgewählt=%b), Surface vorhanden=%b, Surface valid=%b ===",
            videoTrackCount,
            selectedVideoTrack,
            audioTrackCount,
            selectedAudioTrack,
            activeSurface != null,
            activeSurface?.isValid == true,
        )
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
     * Stellt sicher, dass das Binding synchron auf dem Main-Thread erfolgt und wartendes
     * Playback erst nach erfolgreicher Surface-Bindung gestartet wird.
     */
    fun setSurface(surface: Surface?) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applySurface(surface)
        } else {
            scope.launch(Dispatchers.Main) {
                applySurface(surface)
            }
        }
    }

    private fun applySurface(surface: Surface?) {
        val isValid = surface != null && surface.isValid
        Timber.i(
            "Jelly-Car: applySurface: surface=%s, valid=%b, isWaitingForSurface=%b",
            surface,
            isValid,
            isWaitingForSurface,
        )
        activeSurface = surface
        if (surface != null && surface.isValid) {
            exoPlayer.setVideoSurface(surface)
            exoPlayer.videoScalingMode = currentAspectRatio.scalingMode
            if (isWaitingForSurface) {
                Timber.i("Jelly-Car: Surface eingetroffen! Starte nun zuvor wartende Videowiedergabe für ${currentItem?.name}")
                isWaitingForSurface = false
                exoPlayer.prepare()
                exoPlayer.play()
                startProgressReporting()
            }
        } else {
            exoPlayer.clearVideoSurface()
            val isAudio = currentItem?.type == BaseItemKind.AUDIO || currentItem?.mediaType == MediaType.AUDIO
            if (!isAudio && exoPlayer.isPlaying) {
                Timber.i("Jelly-Car: Surface entfernt -> Pausiere Videowiedergabe, damit Ton nicht ohne Bild weiterläuft")
                exoPlayer.pause()
                isWaitingForSurface = true
            }
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
        playMedia(item, startPositionMs, retryLevel = 0)
    }

    /**
     * Startet die Wiedergabe eines beliebigen Mediums (Audio oder Video).
     */
    fun playMedia(item: BaseItemDto, startPositionMs: Long = 0L, retryLevel: Int = 0) {
        Timber.i("Jelly-Car: Starte Wiedergabe für Item: ${item.name} (${item.id}) bei ${startPositionMs}ms (retryLevel: $retryLevel)")
        currentItem = item
        currentStartPositionMs = startPositionMs
        currentRetryLevel = retryLevel
        if (retryLevel == 0) {
            listeners.forEach { it.onMediaItemTransition(item) }
        }

        val isAudio = item.type == BaseItemKind.AUDIO || item.mediaType == MediaType.AUDIO

        scope.launch {
            try {
                if (isAudio) {
                    playAudioInternal(item, startPositionMs)
                } else {
                    playVideoInternal(item, startPositionMs, retryLevel)
                }
            } catch (e: Exception) {
                Timber.e(e, "Fehler beim Vorbereiten des Medien-Streams")
                listeners.forEach { it.onError("Fehler beim Laden: ${e.localizedMessage}") }
            }
        }
    }

    private suspend fun playAudioInternal(item: BaseItemDto, startPositionMs: Long) {
        val apiKey = apiClient.accessToken
        val deviceId = apiClient.deviceInfo.id

        // Für reine Audio-Wiedergabe verwenden wir die universalAudioApi
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
        val finalUrl = appendAuthParam(audioUrl, apiKey)
        lastLoadedStreamUrl = finalUrl
        lastLoadedMimeType = if (finalUrl.contains(".m3u8", ignoreCase = true)) MimeTypes.APPLICATION_M3U8 else null

        CarUrlHelper.checkHostConsistency(apiClient.baseUrl, finalUrl, "CarAudioPlayer")

        val mediaItem = MediaItem.Builder()
            .setUri(finalUrl.toUri())
            .setMediaId(item.id.toString())
            .build()

        val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)
        exoPlayer.setMediaSource(mediaSource)
        if (startPositionMs > 0L) {
            exoPlayer.seekTo(startPositionMs)
        }
        exoPlayer.prepare()
        exoPlayer.play()

        reportPlaybackStart(item, null, startPositionMs)
        startProgressReporting()
    }

    private suspend fun playVideoInternal(item: BaseItemDto, startPositionMs: Long, retryLevel: Int) {
        val isLiveTv = item.type == BaseItemKind.LIVE_TV_CHANNEL

        // Wenn Stufe 3 oder Live-TV mit Fehlschlag: direkt auf den zuverlässigen Server-Stream springen
        if (retryLevel >= 3) {
            Timber.i("Jelly-Car: Nutze Stufe 3 Universal-Fallback für Video/Live-TV (${item.name})")
            playDirectServerStream(item, startPositionMs, isLiveTv)
            return
        }

        val deviceProfile = deviceProfileBuilder.getDeviceProfile()

        // Fallback-Strategie gegen "Source Error":
        // Stufe 0: Normal über DeviceProfile aufgelöst (DirectPlay oder Server-Transcode)
        // Stufe 1: Direct Play deaktiviert -> Server nutzt Direct Stream (Remux)
        // Stufe 2: Direct Stream deaktiviert -> Server erzwingt HLS-Transcode
        val enableDirectPlay = if (retryLevel >= 1) false else null
        val enableDirectStream = if (retryLevel >= 2) false else null

        val resolvedResult = withContext(Dispatchers.IO) {
            mediaSourceResolver.resolveMediaSource(
                itemId = item.id,
                deviceProfile = deviceProfile,
                startTime = startPositionMs.milliseconds,
                enableDirectPlay = enableDirectPlay,
                enableDirectStream = enableDirectStream,
            )
        }

        val remoteSource = resolvedResult.getOrNull()
        currentMediaSource = remoteSource

        if (remoteSource == null) {
            Timber.w("Konnte RemoteMediaSource nicht auflösen -> Wechsle auf Direkt-Stream Fallback für ${item.name}")
            playDirectServerStream(item, startPositionMs, isLiveTv)
            return
        }

        val sourceInfo = remoteSource.sourceInfo
        val (streamUrl, forcedMimeType) = when (remoteSource.playMethod) {
            PlayMethod.DIRECT_PLAY -> {
                when (sourceInfo.protocol) {
                    MediaProtocol.FILE -> {
                        val url = apiClient.videosApi.getVideoStreamUrl(
                            itemId = remoteSource.itemId,
                            static = true,
                            playSessionId = remoteSource.playSessionId,
                            mediaSourceId = remoteSource.id,
                            deviceId = apiClient.deviceInfo.id,
                        )
                        url to null
                    }
                    MediaProtocol.HTTP -> {
                        val url = requireNotNull(sourceInfo.path)
                        val mime = when {
                            url.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                            url.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
                            url.contains(".ts", ignoreCase = true) -> MimeTypes.VIDEO_MP2T
                            url.contains(".mp4", ignoreCase = true) -> MimeTypes.VIDEO_MP4
                            url.contains(".mkv", ignoreCase = true) -> MimeTypes.VIDEO_MATROSKA
                            url.contains(".webm", ignoreCase = true) -> MimeTypes.VIDEO_WEBM
                            else -> null
                        }
                        url to mime
                    }
                    else -> {
                        val url = apiClient.videosApi.getVideoStreamUrl(
                            itemId = remoteSource.itemId,
                            static = false,
                            playSessionId = remoteSource.playSessionId,
                            deviceId = apiClient.deviceInfo.id,
                        )
                        url to null
                    }
                }
            }
            PlayMethod.DIRECT_STREAM -> {
                val container = sourceInfo.container ?: "mp4"
                val url = apiClient.videosApi.getVideoStreamByContainerUrl(
                    itemId = remoteSource.itemId,
                    container = container,
                    playSessionId = remoteSource.playSessionId,
                    mediaSourceId = remoteSource.id,
                    deviceId = apiClient.deviceInfo.id,
                )
                val mime = CarUrlHelper.containerToMimeType(container) ?: MimeTypes.VIDEO_MP4
                url to mime
            }
            PlayMethod.TRANSCODE -> {
                val transcodingPath = requireNotNull(sourceInfo.transcodingUrl) { "Missing transcode URL" }
                val transcodingUrl = apiClient.createUrl(transcodingPath)
                val mime = when {
                    transcodingUrl.contains(".m3u8", ignoreCase = true) || sourceInfo.transcodingSubProtocol == MediaStreamProtocol.HLS -> MimeTypes.APPLICATION_M3U8
                    transcodingUrl.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
                    transcodingUrl.contains(".ts", ignoreCase = true) -> MimeTypes.VIDEO_MP2T
                    else -> MimeTypes.APPLICATION_M3U8
                }
                transcodingUrl to mime
            }
        }

        val containerMime = CarUrlHelper.containerToMimeType(sourceInfo.container)
        val effectiveMimeType = forcedMimeType ?: containerMime
        val finalUrl = appendAuthParam(streamUrl, apiClient.accessToken)
        lastLoadedStreamUrl = finalUrl
        lastLoadedMimeType = effectiveMimeType

        // Host-Konsistenz & DNS-Diagnose für WireGuard
        CarUrlHelper.checkHostConsistency(apiClient.baseUrl, finalUrl, "CarVideoPlayer")
        scope.launch {
            try {
                CarUrlHelper.logDnsResolution(finalUrl.toUri().host, "CarVideoPlayer")
            } catch (e: Exception) {
                // ignore in logging
            }
        }

        Timber.i(
            "Jelly-Car Video Stream: playMethod=%s, mime=%s, url=%s",
            remoteSource.playMethod,
            effectiveMimeType,
            CarUrlHelper.redactUrl(finalUrl),
        )

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(finalUrl.toUri())
            .setMediaId(item.id.toString())

        if (effectiveMimeType != null) {
            mediaItemBuilder.setMimeType(effectiveMimeType)
        }

        // Externe Untertitel anbinden, falls vorhanden
        val subtitleConfigs = remoteSource.externalSubtitleStreams.map { stream ->
            val subUri = apiClient.createUrl(stream.deliveryUrl).toUri()
            MediaItem.SubtitleConfiguration.Builder(subUri).apply {
                setId("${stream.index}")
                setLabel(stream.displayTitle)
                setMimeType(stream.mimeType)
                setLanguage(stream.language)
            }.build()
        }
        if (subtitleConfigs.isNotEmpty()) {
            mediaItemBuilder.setSubtitleConfigurations(subtitleConfigs)
        }

        val mediaItem = mediaItemBuilder.build()
        val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)

        withContext(Dispatchers.Main) {
            exoPlayer.setMediaSource(mediaSource)
            if (startPositionMs > 0L) {
                exoPlayer.seekTo(startPositionMs)
            }

            val surface = activeSurface
            if (surface != null && surface.isValid) {
                Timber.i("Jelly-Car: Verbinde aktives Surface vor prepare an ExoPlayer: %s", surface)
                isWaitingForSurface = false
                exoPlayer.setVideoSurface(surface)
                exoPlayer.videoScalingMode = currentAspectRatio.scalingMode
                exoPlayer.prepare()
                exoPlayer.play()
                reportPlaybackStart(item, remoteSource, startPositionMs)
                startProgressReporting()
            } else {
                Timber.w(
                    "Jelly-Car: Kein gültiges Surface vorhanden vor prepare (activeSurface=%s, valid=%b). " +
                        "Warte auf onSurfaceAvailable() vor Wiedergabestart!",
                    surface,
                    surface?.isValid == true,
                )
                isWaitingForSurface = true
                exoPlayer.playWhenReady = false
                exoPlayer.prepare()
                reportPlaybackStart(item, remoteSource, startPositionMs)
            }
        }
    }

    private fun playDirectServerStream(item: BaseItemDto, startPositionMs: Long, isLiveTv: Boolean) {
        val streamUrl: String
        val mimeType: String?

        if (isLiveTv) {
            streamUrl = "${apiClient.baseUrl}/Videos/${item.id}/live.m3u8?api_key=${apiClient.accessToken}&DeviceId=${apiClient.deviceInfo.id}"
            mimeType = MimeTypes.APPLICATION_M3U8
        } else {
            val base = apiClient.videosApi.getVideoStreamUrl(
                itemId = item.id,
                static = false,
                deviceId = apiClient.deviceInfo.id,
            )
            streamUrl = appendAuthParam(base, apiClient.accessToken)
            mimeType = CarUrlHelper.containerToMimeType(item.container) ?: MimeTypes.VIDEO_MP4
        }

        lastLoadedStreamUrl = streamUrl
        lastLoadedMimeType = mimeType

        // Host-Konsistenz & DNS-Diagnose
        CarUrlHelper.checkHostConsistency(apiClient.baseUrl, streamUrl, "CarVideoPlayerFallback")
        scope.launch {
            try {
                CarUrlHelper.logDnsResolution(streamUrl.toUri().host, "CarVideoPlayerFallback")
            } catch (e: Exception) {
                // ignore in logging
            }
        }

        Timber.i("Jelly-Car Direkt-Fallback: mime=%s, url=%s", mimeType, CarUrlHelper.redactUrl(streamUrl))
        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl.toUri())
            .setMediaId(item.id.toString())
            .setMimeType(mimeType)
            .build()

        val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)

        scope.launch(Dispatchers.Main) {
            exoPlayer.setMediaSource(mediaSource)
            if (startPositionMs > 0L) {
                exoPlayer.seekTo(startPositionMs)
            }

            val surface = activeSurface
            if (surface != null && surface.isValid) {
                Timber.i("Jelly-Car Fallback: Verbinde aktives Surface vor prepare an ExoPlayer: %s", surface)
                isWaitingForSurface = false
                exoPlayer.setVideoSurface(surface)
                exoPlayer.videoScalingMode = currentAspectRatio.scalingMode
                exoPlayer.prepare()
                exoPlayer.play()
                reportPlaybackStart(item, null, startPositionMs)
                startProgressReporting()
            } else {
                Timber.w(
                    "Jelly-Car Fallback: Kein gültiges Surface vorhanden vor prepare (activeSurface=%s, valid=%b). " +
                        "Warte auf onSurfaceAvailable() vor Wiedergabestart!",
                    surface,
                    surface?.isValid == true,
                )
                isWaitingForSurface = true
                exoPlayer.playWhenReady = false
                exoPlayer.prepare()
                reportPlaybackStart(item, null, startPositionMs)
            }
        }
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
        isWaitingForSurface = false
        stopProgressReporting(true)
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        currentItem = null
        currentMediaSource = null
        lastLoadedStreamUrl = null
        lastLoadedMimeType = null
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
                                androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, i),
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
                    ),
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
                    ),
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
                    ),
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
                    ),
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
