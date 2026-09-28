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
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber

/**
 * Staffel- und Episodenübersicht für TV-Serien auf dem Android Auto Display.
 */
@UnstableApi
class CarSeriesDetailScreen(
    carContext: CarContext,
    private val apiClient: ApiClient,
    private val playerManager: CarVideoPlayerManager,
    private val imageHelper: CarImageHelper,
    private val series: BaseItemDto,
) : Screen(carContext) {

    private var seasons: List<BaseItemDto> = emptyList()
    private var isLoading = true

    init {
        loadSeasons()
    }

    private fun loadSeasons() {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    apiClient.tvShowsApi.getSeasons(seriesId = series.id)
                }
                seasons = response.content.items ?: emptyList()
            } catch (e: Exception) {
                Timber.e(e, "Fehler beim Laden der Staffeln für ${series.name}")
            } finally {
                isLoading = false
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()

        if (isLoading) {
            listBuilder.setNoItemsMessage("Lade Staffeln...")
        } else if (seasons.isEmpty()) {
            listBuilder.setNoItemsMessage("Keine Staffeln gefunden")
        } else {
            seasons.forEach { season ->
                val seasonTitle = season.name ?: "Staffel"
                val epCount = season.childCount
                val subtitle = if (epCount != null) "$epCount Episoden" else ""

                listBuilder.addItem(
                    Row.Builder()
                        .setTitle(seasonTitle)
                        .apply {
                            if (subtitle.isNotEmpty()) addText(subtitle)
                        }
                        .setOnClickListener {
                            screenManager.push(
                                CarEpisodesScreen(
                                    carContext,
                                    apiClient,
                                    playerManager,
                                    imageHelper,
                                    series,
                                    season
                                )
                            )
                        }
                        .build()
                )
            }
        }

        return ListTemplate.Builder()
            .setTitle(series.name.orEmpty())
            .setHeaderAction(Action.BACK)
            .setSingleList(listBuilder.build())
            .build()
    }
}

/**
 * Anzeige der Episoden einer ausgewählten Staffel.
 */
@UnstableApi
class CarEpisodesScreen(
    carContext: CarContext,
    private val apiClient: ApiClient,
    private val playerManager: CarVideoPlayerManager,
    private val imageHelper: CarImageHelper,
    private val series: BaseItemDto,
    private val season: BaseItemDto,
) : Screen(carContext) {

    private var episodes: List<BaseItemDto> = emptyList()
    private var isLoading = true

    init {
        loadEpisodes()
    }

    private fun loadEpisodes() {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    apiClient.tvShowsApi.getEpisodes(
                        seriesId = series.id,
                        seasonId = season.id,
                    )
                }
                episodes = response.content.items ?: emptyList()
            } catch (e: Exception) {
                Timber.e(e, "Fehler beim Laden der Episoden für ${season.name}")
            } finally {
                isLoading = false
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()

        if (isLoading) {
            listBuilder.setNoItemsMessage("Lade Episoden...")
        } else if (episodes.isEmpty()) {
            listBuilder.setNoItemsMessage("Keine Episoden in dieser Staffel")
        } else {
            episodes.forEach { ep ->
                val epNum = ep.indexNumber?.let { "E$it: " } ?: ""
                val epTitle = "$epNum${ep.name ?: "Episode"}"
                val runTimeMs = ep.runTimeTicks?.let { it / 10_000L } ?: 0L
                val runtimeStr = if (runTimeMs > 0) "${runTimeMs / 60_000} Min." else ""

                listBuilder.addItem(
                    Row.Builder()
                        .setTitle(epTitle)
                        .apply {
                            if (runtimeStr.isNotEmpty()) addText(runtimeStr)
                        }
                        .setOnClickListener {
                            screenManager.push(
                                CarMediaDetailScreen(
                                    carContext,
                                    apiClient,
                                    playerManager,
                                    imageHelper,
                                    ep
                                )
                            )
                        }
                        .build()
                )
            }
        }

        return ListTemplate.Builder()
            .setTitle("${series.name}: ${season.name}")
            .setHeaderAction(Action.BACK)
            .setSingleList(listBuilder.build())
            .build()
    }
}
