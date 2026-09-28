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
 * Album-View für Android Auto.
 *
 * Zeigt Musikalben mit Cover-Artworks an. Klick auf ein Album
 * öffnet die Trackliste zum direkten Abspielen.
 */
@UnstableApi
class CarMusicAlbumsScreen(
    carContext: CarContext,
    private val apiClient: ApiClient,
    private val playerManager: CarVideoPlayerManager,
    private val imageHelper: CarImageHelper,
    private val artistId: UUID? = null,
    private val screenTitle: String = "Alben",
) : Screen(carContext) {

    private var albums: List<BaseItemDto> = emptyList()
    private val albumIcons = mutableMapOf<UUID, CarIcon>()
    private var isLoading = true

    init {
        loadAlbums()
    }

    private fun loadAlbums() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    apiClient.itemsApi.getItems(
                        includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                        artistIds = artistId?.let { listOf(it) },
                        sortBy = listOf(ItemSortBy.SORT_NAME),
                        sortOrder = listOf(SortOrder.ASCENDING),
                        recursive = true,
                        enableImageTypes = listOf(ImageType.PRIMARY),
                        limit = 100,
                    ).content.items ?: emptyList()
                }
                albums = result
            } catch (e: Exception) {
                Timber.e(e, "Fehler beim Laden der Alben")
            } finally {
                isLoading = false
                invalidate()
            }

            // Cover-Artworks asynchron nachladen
            albums.forEach { album ->
                try {
                    val icon = imageHelper.loadCarIcon(
                        itemId = album.id,
                        imageType = ImageType.PRIMARY,
                        fallbackResId = R.drawable.ic_album,
                    )
                    albumIcons[album.id] = icon
                    invalidate()
                } catch (e: Exception) {
                    Timber.w(e, "Konnte Albumcover nicht laden: ${album.name}")
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        if (isLoading) {
            return ListTemplate.Builder()
                .setTitle(screenTitle)
                .setHeaderAction(Action.BACK)
                .setLoading(true)
                .build()
        }

        val listBuilder = ItemList.Builder()
            .setNoItemsMessage("Keine Alben gefunden")

        albums.forEach { album ->
            val rowBuilder = Row.Builder()
                .setTitle(album.name.orEmpty())

            val artistText = album.albumArtist ?: album.artists?.firstOrNull() ?: album.productionYear?.toString()
            if (!artistText.isNullOrEmpty()) {
                rowBuilder.addText(artistText)
            }

            val icon = albumIcons[album.id]
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
                        mode = TrackListMode.ALBUM,
                        parentId = album.id,
                        title = album.name ?: "Titel",
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
            .setTitle(screenTitle)
            .setHeaderAction(Action.BACK)
            .setActionStrip(actionStripBuilder.build())
            .setSingleList(listBuilder.build())
            .build()
    }
}
