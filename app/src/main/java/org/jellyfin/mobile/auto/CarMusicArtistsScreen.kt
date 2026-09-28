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
import org.jellyfin.sdk.api.client.extensions.artistsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import timber.log.Timber
import java.util.UUID

/**
 * Artist-View für Android Auto.
 *
 * Zeigt alle Interpreten an. Bei Klick auf einen Interpreten
 * öffnet sich die Alben-Übersicht dieses Künstlers.
 */
@UnstableApi
class CarMusicArtistsScreen(
    carContext: CarContext,
    private val apiClient: ApiClient,
    private val playerManager: CarVideoPlayerManager,
    private val imageHelper: CarImageHelper,
) : Screen(carContext) {

    private var artists: List<BaseItemDto> = emptyList()
    private val artistIcons = mutableMapOf<UUID, CarIcon>()
    private var isLoading = true

    init {
        loadArtists()
    }

    private fun loadArtists() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    apiClient.artistsApi.getArtists(
                        sortBy = listOf(ItemSortBy.SORT_NAME),
                        sortOrder = listOf(SortOrder.ASCENDING),
                        enableImageTypes = listOf(ImageType.PRIMARY),
                        limit = 100,
                    ).content.items ?: emptyList()
                }
                artists = result
            } catch (e: Exception) {
                Timber.e(e, "Fehler beim Laden der Interpreten")
            } finally {
                isLoading = false
                invalidate()
            }

            // Asynchrones Nachladen der Interpreten-Bilder
            artists.forEach { artist ->
                try {
                    val icon = imageHelper.loadCarIcon(
                        itemId = artist.id,
                        imageType = ImageType.PRIMARY,
                        fallbackResId = R.drawable.ic_artist,
                    )
                    artistIcons[artist.id] = icon
                    invalidate()
                } catch (e: Exception) {
                    Timber.w(e, "Konnte Bild für Interpret nicht laden: ${artist.name}")
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        if (isLoading) {
            return ListTemplate.Builder()
                .setTitle("Interpreten")
                .setHeaderAction(Action.BACK)
                .setLoading(true)
                .build()
        }

        val listBuilder = ItemList.Builder()
            .setNoItemsMessage("Keine Interpreten gefunden")

        artists.forEach { artist ->
            val rowBuilder = Row.Builder()
                .setTitle(artist.name.orEmpty())

            val icon = artistIcons[artist.id]
            if (icon != null) {
                rowBuilder.setImage(icon)
            }

            rowBuilder.setOnClickListener {
                screenManager.push(
                    CarMusicAlbumsScreen(
                        carContext,
                        apiClient,
                        playerManager,
                        imageHelper,
                        artistId = artist.id,
                        screenTitle = artist.name ?: "Alben",
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
        actionStripBuilder.addAction(
            Action.Builder()
                .setTitle("Suche")
                .setOnClickListener {
                    screenManager.push(CarSearchScreen(carContext, apiClient, playerManager, imageHelper))
                }
                .build()
        )

        return ListTemplate.Builder()
            .setTitle("Interpreten")
            .setHeaderAction(Action.BACK)
            .setActionStrip(actionStripBuilder.build())
            .setSingleList(listBuilder.build())
            .build()
    }
}
