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
import timber.log.Timber
import java.util.UUID

/**
 * Playlists-Übersicht für Android Auto.
 */
@UnstableApi
class CarMusicPlaylistsScreen(
    carContext: CarContext,
    private val apiClient: ApiClient,
    private val playerManager: CarVideoPlayerManager,
    private val imageHelper: CarImageHelper,
) : Screen(carContext) {

    private var playlists: List<BaseItemDto> = emptyList()
    private val playlistIcons = mutableMapOf<UUID, CarIcon>()
    private var isLoading = true

    init {
        loadPlaylists()
    }

    private fun loadPlaylists() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    apiClient.itemsApi.getItems(
                        includeItemTypes = listOf(BaseItemKind.PLAYLIST),
                        sortBy = listOf(ItemSortBy.SORT_NAME),
                        sortOrder = listOf(SortOrder.ASCENDING),
                        recursive = true,
                        enableImageTypes = listOf(ImageType.PRIMARY),
                        limit = 100,
                    ).content.items ?: emptyList()
                }
                playlists = result
            } catch (e: Exception) {
                Timber.e(e, "Fehler beim Laden der Playlists")
            } finally {
                isLoading = false
                invalidate()
            }

            // Playlist-Cover nachladen
            playlists.forEach { playlist ->
                try {
                    val icon = imageHelper.loadCarIcon(
                        itemId = playlist.id,
                        imageType = ImageType.PRIMARY,
                        fallbackResId = R.drawable.ic_playlist,
                    )
                    playlistIcons[playlist.id] = icon
                    invalidate()
                } catch (e: Exception) {
                    Timber.w(e, "Konnte Bild für Playlist nicht laden: ${playlist.name}")
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        if (isLoading) {
            return ListTemplate.Builder()
                .setTitle("Wiedergabelisten")
                .setHeaderAction(Action.BACK)
                .setLoading(true)
                .build()
        }

        val listBuilder = ItemList.Builder()
            .setNoItemsMessage("Keine Wiedergabelisten gefunden")

        playlists.forEach { playlist ->
            val rowBuilder = Row.Builder()
                .setTitle(playlist.name.orEmpty())
                .addText("Wiedergabeliste")

            val icon = playlistIcons[playlist.id]
            if (icon != null) {
                rowBuilder.setImage(icon)
            }

            rowBuilder.setOnClickListener {
                screenManager.push(
                    CarMusicTracksScreen(
                        carContext,
                        apiClient,
                        playerManager,
                        imageHelper,
                        mode = TrackListMode.PLAYLIST,
                        parentId = playlist.id,
                        title = playlist.name ?: "Wiedergabeliste",
                    )
                )
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
            .setTitle("Wiedergabelisten")
            .setHeaderAction(Action.BACK)
            .setActionStrip(actionStripBuilder.build())
            .setSingleList(listBuilder.build())
            .build()
    }
}
