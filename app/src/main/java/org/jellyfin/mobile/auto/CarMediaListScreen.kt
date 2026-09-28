package org.jellyfin.mobile.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
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
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.extensions.ticks
import timber.log.Timber
import java.util.UUID

/**
 * Typen von Medienlisten, die auf dem Auto-Display dargestellt werden können.
 */
enum class MediaListType {
    RESUME,
    MOVIES,
    SERIES,
    LATEST,
    FOLDER,
}

/**
 * Listenansicht für Filme, Serien, Episoden oder "Weiter ansehen" in Android Auto.
 */
@UnstableApi
class CarMediaListScreen(
    carContext: CarContext,
    private val apiClient: ApiClient,
    private val playerManager: CarVideoPlayerManager,
    private val imageHelper: CarImageHelper,
    private val listType: MediaListType,
    private val screenTitle: String,
    private val parentId: UUID? = null,
) : Screen(carContext) {

    private var items: List<BaseItemDto> = emptyList()
    private val itemIcons = mutableMapOf<UUID, CarIcon>()
    private var isLoading = true

    init {
        loadMediaItems()
    }

    private fun loadMediaItems() {
        lifecycleScope.launch {
            try {
                val fetchedItems = withContext(Dispatchers.IO) {
                    when (listType) {
                        MediaListType.RESUME -> {
                            val response = apiClient.itemsApi.getResumeItems(
                                limit = 20,
                                mediaTypes = listOf(MediaType.VIDEO),
                            )
                            response.content.items ?: emptyList()
                        }
                        MediaListType.MOVIES -> {
                            val response = apiClient.itemsApi.getItems(
                                parentId = parentId,
                                includeItemTypes = listOf(BaseItemKind.MOVIE),
                                sortBy = listOf(ItemSortBy.SORT_NAME),
                                sortOrder = listOf(SortOrder.ASCENDING),
                                recursive = true,
                                limit = 50,
                            )
                            response.content.items ?: emptyList()
                        }
                        MediaListType.SERIES -> {
                            val response = apiClient.itemsApi.getItems(
                                parentId = parentId,
                                includeItemTypes = listOf(BaseItemKind.SERIES),
                                sortBy = listOf(ItemSortBy.SORT_NAME),
                                sortOrder = listOf(SortOrder.ASCENDING),
                                recursive = true,
                                limit = 50,
                            )
                            response.content.items ?: emptyList()
                        }
                        MediaListType.LATEST -> {
                            val response = apiClient.userLibraryApi.getLatestMedia(
                                parentId = parentId,
                                limit = 20,
                            )
                            response.content ?: emptyList()
                        }
                        MediaListType.FOLDER -> {
                            val response = apiClient.itemsApi.getItems(
                                parentId = parentId,
                                recursive = false,
                                limit = 50,
                            )
                            response.content.items ?: emptyList()
                        }
                    }
                }
                items = fetchedItems

                // Asynchron Cover-Icons nachladen
                launch(Dispatchers.IO) {
                    items.take(15).forEach { item ->
                        val icon = imageHelper.loadCarIcon(item.id, maxWidth = 150, maxHeight = 150)
                        itemIcons[item.id] = icon
                    }
                    invalidate()
                }
            } catch (e: Exception) {
                Timber.e(e, "Fehler beim Laden der Medienliste für $screenTitle")
            } finally {
                isLoading = false
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()

        if (isLoading) {
            listBuilder.setNoItemsMessage("Lade Videos...")
        } else if (items.isEmpty()) {
            listBuilder.setNoItemsMessage("Keine Videos in dieser Kategorie vorhanden")
        } else {
            items.forEach { item ->
                val title = item.name.orEmpty()
                val subtitle = buildSubtitle(item)
                val icon = itemIcons[item.id]

                val rowBuilder = Row.Builder()
                    .setTitle(title)
                    .apply {
                        if (subtitle.isNotEmpty()) addText(subtitle)
                        if (icon != null) setImage(icon)
                    }
                    .setOnClickListener {
                        if (item.type == BaseItemKind.SERIES) {
                            screenManager.push(
                                CarSeriesDetailScreen(
                                    carContext,
                                    apiClient,
                                    playerManager,
                                    imageHelper,
                                    item,
                                )
                            )
                        } else {
                            screenManager.push(
                                CarMediaDetailScreen(
                                    carContext,
                                    apiClient,
                                    playerManager,
                                    imageHelper,
                                    item,
                                )
                            )
                        }
                    }

                listBuilder.addItem(rowBuilder.build())
            }
        }

        return ListTemplate.Builder()
            .setTitle(screenTitle)
            .setHeaderAction(Action.BACK)
            .setSingleList(listBuilder.build())
            .build()
    }

    private fun buildSubtitle(item: BaseItemDto): String {
        return buildString {
            if (item.type == BaseItemKind.EPISODE && !item.seriesName.isNullOrEmpty()) {
                append(item.seriesName)
                if (item.parentIndexNumber != null && item.indexNumber != null) {
                    append(" S${item.parentIndexNumber}E${item.indexNumber}")
                }
                append("  •  ")
            }
            if (item.productionYear != null) {
                append("${item.productionYear}  •  ")
            }
            val runTimeMs = item.runTimeTicks?.ticks?.inWholeMilliseconds ?: 0L
            if (runTimeMs > 0L) {
                append("${runTimeMs / 60_000} Min.")
            }
            val resumeMs = item.userData?.playbackPositionTicks?.ticks?.inWholeMilliseconds ?: 0L
            if (resumeMs > 0L && runTimeMs > 0L) {
                val percent = ((resumeMs.toDouble() / runTimeMs) * 100).toInt()
                append("  ($percent% gesehen)")
            }
        }.removeSuffix("  •  ")
    }
}
