package org.jellyfin.mobile.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

/**
 * Suchfunktion für Filme und Serien auf dem Auto-Display.
 * Ermöglicht Tastatur- und Spracheingabe über Android Auto.
 */
@UnstableApi
class CarSearchScreen(
    carContext: CarContext,
    private val apiClient: ApiClient,
    private val playerManager: CarVideoPlayerManager,
    private val imageHelper: CarImageHelper,
) : Screen(carContext) {

    private var searchResults: List<BaseItemDto> = emptyList()
    private var searchJob: Job? = null
    private var isSearching = false

    private val searchCallback = object : SearchTemplate.SearchCallback {
        override fun onSearchTextChanged(searchText: String) {
            triggerSearch(searchText)
        }

        override fun onSearchSubmitted(searchText: String) {
            triggerSearch(searchText)
        }
    }

    private fun triggerSearch(query: String) {
        searchJob?.cancel()
        if (query.trim().length < 2) {
            searchResults = emptyList()
            isSearching = false
            invalidate()
            return
        }

        searchJob = lifecycleScope.launch {
            delay(400) // Debounce
            isSearching = true
            invalidate()

            try {
                val response = withContext(Dispatchers.IO) {
                    apiClient.itemsApi.getItems(
                        searchTerm = query.trim(),
                        includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE),
                        recursive = true,
                        limit = 20,
                    )
                }
                searchResults = response.content.items ?: emptyList()
            } catch (e: Exception) {
                Timber.e(e, "Fehler bei Video-Suche nach: $query")
            } finally {
                isSearching = false
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()

        if (isSearching) {
            listBuilder.setNoItemsMessage("Suche läuft...")
        } else if (searchResults.isEmpty()) {
            listBuilder.setNoItemsMessage("Tippe oder sprich einen Filmtitel ein")
        } else {
            searchResults.forEach { item ->
                val typeLabel = when (item.type) {
                    BaseItemKind.MOVIE -> "Film"
                    BaseItemKind.SERIES -> "Serie"
                    BaseItemKind.EPISODE -> "Episode"
                    else -> "Video"
                }
                val subtitle = buildString {
                    append(typeLabel)
                    if (item.productionYear != null) append("  •  ${item.productionYear}")
                    if (!item.seriesName.isNullOrEmpty()) append("  •  ${item.seriesName}")
                }

                listBuilder.addItem(
                    Row.Builder()
                        .setTitle(item.name.orEmpty())
                        .addText(subtitle)
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
                        .build()
                )
            }
        }

        return SearchTemplate.Builder(searchCallback)
            .setHeaderAction(Action.BACK)
            .setShowKeyboardByDefault(false)
            .setItemList(listBuilder.build())
            .build()
    }
}
