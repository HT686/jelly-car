package org.jellyfin.mobile.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.R
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.extensions.ticks
import timber.log.Timber
import java.util.UUID

enum class TrackListMode {
    ALBUM,
    ALL_SONGS,
    PLAYLIST,
    FAVORITES,
}

/**
 * Titelliste für Alben, Playlists, Favoriten oder alle Songs in Android Auto.
 */
@UnstableApi
class CarMusicTracksScreen(
    carContext: CarContext,
    private val apiClient: ApiClient,
    private val playerManager: CarVideoPlayerManager,
    private val imageHelper: CarImageHelper,
    private val mode: TrackListMode,
    private val parentId: UUID? = null,
    private val title: String,
) : Screen(carContext) {

    private var tracks: List<BaseItemDto> = emptyList()
    private val trackIcons = mutableMapOf<UUID, CarIcon>()
    private var isLoading = true

    init {
        loadTracks()
    }

    private fun loadTracks() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    when (mode) {
                        TrackListMode.ALBUM -> {
                            apiClient.itemsApi.getItems(
                                parentId = parentId,
                                includeItemTypes = listOf(BaseItemKind.AUDIO),
                                sortBy = listOf(ItemSortBy.INDEX_NUMBER, ItemSortBy.PARENT_INDEX_NUMBER),
                                sortOrder = listOf(SortOrder.ASCENDING),
                                limit = 100,
                            ).content.items ?: emptyList()
                        }
                        TrackListMode.ALL_SONGS -> {
                            apiClient.itemsApi.getItems(
                                includeItemTypes = listOf(BaseItemKind.AUDIO),
                                sortBy = listOf(ItemSortBy.SORT_NAME),
                                sortOrder = listOf(SortOrder.ASCENDING),
                                recursive = true,
                                limit = 100,
                            ).content.items ?: emptyList()
                        }
                        TrackListMode.PLAYLIST -> {
                            apiClient.itemsApi.getItems(
                                parentId = parentId,
                                includeItemTypes = listOf(BaseItemKind.AUDIO),
                                limit = 100,
                            ).content.items ?: emptyList()
                        }
                        TrackListMode.FAVORITES -> {
                            apiClient.itemsApi.getItems(
                                includeItemTypes = listOf(BaseItemKind.AUDIO),
                                isFavorite = true,
                                sortBy = listOf(ItemSortBy.SORT_NAME),
                                recursive = true,
                                limit = 100,
                            ).content.items ?: emptyList()
                        }
                    }
                }
                tracks = result
            } catch (e: Exception) {
                Timber.e(e, "Fehler beim Laden der Musiktitel")
            } finally {
                isLoading = false
                invalidate()
            }

            // Cover/Icon nachladen
            tracks.forEach { track ->
                try {
                    val icon = imageHelper.loadCarIcon(
                        itemId = track.id,
                        imageType = ImageType.PRIMARY,
                        fallbackResId = R.drawable.ic_music_note_white_24dp,
                    )
                    trackIcons[track.id] = icon
                    invalidate()
                } catch (e: Exception) {
                    Timber.w(e, "Konnte Bild für Titel nicht laden: ${track.name}")
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        if (isLoading) {
            return ListTemplate.Builder()
                .setTitle(title)
                .setHeaderAction(Action.BACK)
                .setLoading(true)
                .build()
        }

        val listBuilder = ItemList.Builder()
            .setNoItemsMessage("Keine Titel vorhanden")

        tracks.forEachIndexed { index, track ->
            val trackNumber = track.indexNumber?.let { "$it. " } ?: ""
            val trackTitle = "$trackNumber${track.name.orEmpty()}"

            val rowBuilder = Row.Builder()
                .setTitle(trackTitle)

            val artist = track.artists?.firstOrNull() ?: track.albumArtist ?: track.album
            val durationText = formatDuration(track.runTimeTicks?.ticks?.inWholeMilliseconds ?: 0L)
            val subText = if (!artist.isNullOrEmpty() && durationText.isNotEmpty()) {
                "$artist  •  $durationText"
            } else {
                artist ?: durationText
            }

            if (subText.isNotEmpty()) {
                rowBuilder.addText(subText)
            }

            val icon = trackIcons[track.id]
            if (icon != null) {
                rowBuilder.setImage(icon)
            }

            rowBuilder.setOnClickListener {
                playerManager.playQueue(tracks, index)
                screenManager.push(CarAudioPlayerScreen(carContext, apiClient, playerManager, imageHelper))
            }

            listBuilder.addItem(rowBuilder.build())
        }

        val actionStripBuilder = ActionStrip.Builder()
        if (playerManager.currentItem != null) {
            actionStripBuilder.addAction(
                Action.Builder()
                    .setTitle("Wiedergabe")
                    .setOnClickListener {
                        screenManager.push(CarAudioPlayerScreen(carContext, apiClient, playerManager, imageHelper))
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .setActionStrip(actionStripBuilder.build())
            .setSingleList(listBuilder.build())
            .build()
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0L) return ""
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }
}
