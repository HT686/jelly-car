package org.jellyfin.mobile.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
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
import org.jellyfin.sdk.api.client.extensions.liveTvApi
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
    LIVE_TV,
    LATEST,
    FOLDER,
}

/**
 * Medienübersicht für Filme, Serien, Episoden oder "Weiter ansehen" in Android Auto.
 *
 * Bildlastige Kategorien (Filme, Serien, Neueste, Weiter ansehen) werden als
 * Poster-Grid dargestellt; Kanäle und generische Ordner als kompakte Liste.
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

    /** Bildlastige Kategorien bekommen das Poster-Grid. */
    private val useGrid: Boolean = when (listType) {
        MediaListType.MOVIES,
        MediaListType.SERIES,
        MediaListType.LATEST,
        MediaListType.RESUME -> true
        MediaListType.LIVE_TV,
        MediaListType.FOLDER -> false
    }

    /** Vom Host erlaubte maximale Elementanzahl für das jeweilige Template. */
    private val contentLimit: Int by lazy {
        runCatching {
            val type = if (useGrid) {
                ConstraintManager.CONTENT_LIMIT_TYPE_GRID
            } else {
                ConstraintManager.CONTENT_LIMIT_TYPE_LIST
            }
            carContext.getCarService(ConstraintManager::class.java).getContentLimit(type)
        }.getOrDefault(DEFAULT_CONTENT_LIMIT).coerceIn(1, MAX_CONTENT_LIMIT)
    }

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
                        MediaListType.LIVE_TV -> {
                            val response = apiClient.liveTvApi.getLiveTvChannels(
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
                items = fetchedItems.take(contentLimit)

                // Platzhalter-Icons sofort setzen, damit jede Kachel/Zeile ein Bild hat ...
                items.forEach { item ->
                    itemIcons[item.id] = imageHelper.createResourceIcon(fallbackIconFor(item))
                }
                isLoading = false
                invalidate()

                // ... und anschließend die echten Cover asynchron nachladen.
                launch(Dispatchers.IO) {
                    items.forEach { item ->
                        val icon = imageHelper.loadCarIcon(
                            itemId = item.id,
                            fallbackResId = fallbackIconFor(item),
                            maxWidth = if (useGrid) POSTER_SIZE_PX else LIST_ICON_SIZE_PX,
                            maxHeight = if (useGrid) POSTER_SIZE_PX else LIST_ICON_SIZE_PX,
                        )
                        itemIcons[item.id] = icon
                    }
                    invalidate()
                }
            } catch (e: Exception) {
                Timber.e(e, "Fehler beim Laden der Medienliste für $screenTitle")
                isLoading = false
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        if (!isLoading && items.isEmpty()) {
            return MessageTemplate.Builder("Keine Videos in dieser Kategorie vorhanden")
                .setTitle(screenTitle)
                .setHeaderAction(Action.BACK)
                .build()
        }
        return if (useGrid) buildGridTemplate() else buildListTemplate()
    }

    private fun buildGridTemplate(): Template {
        val builder = GridTemplate.Builder()
            .setTitle(screenTitle)
            .setHeaderAction(Action.BACK)

        if (isLoading) {
            builder.setLoading(true)
            return builder.build()
        }

        val listBuilder = ItemList.Builder()
        items.forEach { item ->
            val icon = itemIcons[item.id] ?: imageHelper.createResourceIcon(fallbackIconFor(item))
            val gridItem = GridItem.Builder()
                .setTitle(item.name.orEmpty())
                .setImage(icon, GridItem.IMAGE_TYPE_LARGE)
                .apply {
                    val caption = buildGridCaption(item)
                    if (caption.isNotEmpty()) setText(caption)
                }
                .setOnClickListener { onItemClicked(item) }
                .build()
            listBuilder.addItem(gridItem)
        }

        return builder.setSingleList(listBuilder.build()).build()
    }

    private fun buildListTemplate(): Template {
        val listBuilder = ItemList.Builder()

        if (isLoading) {
            listBuilder.setNoItemsMessage("Lade Videos...")
        } else {
            items.forEach { item ->
                val subtitle = buildSubtitle(item)
                val icon = itemIcons[item.id]
                val row = Row.Builder()
                    .setTitle(item.name.orEmpty())
                    .apply {
                        if (subtitle.isNotEmpty()) addText(subtitle)
                        if (icon != null) setImage(icon)
                    }
                    .setOnClickListener { onItemClicked(item) }
                    .build()
                listBuilder.addItem(row)
            }
        }

        return ListTemplate.Builder()
            .setTitle(screenTitle)
            .setHeaderAction(Action.BACK)
            .setSingleList(listBuilder.build())
            .build()
    }

    private fun onItemClicked(item: BaseItemDto) {
        when {
            item.type == BaseItemKind.SERIES -> screenManager.push(
                CarSeriesDetailScreen(carContext, apiClient, playerManager, imageHelper, item)
            )
            item.type == BaseItemKind.LIVE_TV_CHANNEL || listType == MediaListType.LIVE_TV -> screenManager.push(
                CarVideoPlayerScreen(carContext, playerManager, item, 0L)
            )
            else -> screenManager.push(
                CarMediaDetailScreen(carContext, apiClient, playerManager, imageHelper, item)
            )
        }
    }

    /** Passendes Platzhalter-/Fallback-Symbol je nach Inhaltstyp. */
    private fun fallbackIconFor(item: BaseItemDto): Int = when {
        item.type == BaseItemKind.LIVE_TV_CHANNEL || listType == MediaListType.LIVE_TV -> R.drawable.ic_live_tv
        item.type == BaseItemKind.SERIES -> R.drawable.ic_tv_series
        else -> R.drawable.ic_local_movies_white_64
    }

    /** Kurze Zusatzzeile unter dem Poster im Grid (eine Zeile, knapp gehalten). */
    private fun buildGridCaption(item: BaseItemDto): String {
        if (item.type == BaseItemKind.LIVE_TV_CHANNEL || listType == MediaListType.LIVE_TV) {
            return item.currentProgram?.name.orEmpty().ifEmpty { "Live" }
        }
        if (item.type == BaseItemKind.EPISODE && item.parentIndexNumber != null && item.indexNumber != null) {
            return "S${item.parentIndexNumber} · E${item.indexNumber}"
        }
        val runTimeMs = item.runTimeTicks?.ticks?.inWholeMilliseconds ?: 0L
        val resumeMs = item.userData?.playbackPositionTicks?.ticks?.inWholeMilliseconds ?: 0L
        if (resumeMs > 0L && runTimeMs > 0L) {
            return "${((resumeMs.toDouble() / runTimeMs) * 100).toInt()}% gesehen"
        }
        return item.productionYear?.toString().orEmpty()
    }

    private fun buildSubtitle(item: BaseItemDto): String {
        return buildString {
            if (item.type == BaseItemKind.LIVE_TV_CHANNEL || listType == MediaListType.LIVE_TV) {
                val program = item.currentProgram?.name
                if (!program.isNullOrEmpty()) {
                    append(program)
                } else {
                    append("Live-Stream")
                }
                append("  •  ")
            }
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

    private companion object {
        const val DEFAULT_CONTENT_LIMIT = 12
        const val MAX_CONTENT_LIMIT = 50
        const val POSTER_SIZE_PX = 300
        const val LIST_ICON_SIZE_PX = 150
    }
}
