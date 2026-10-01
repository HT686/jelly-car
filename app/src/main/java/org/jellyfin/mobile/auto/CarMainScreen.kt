package org.jellyfin.mobile.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.R
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import timber.log.Timber

/**
 * Hauptbildschirm (Dashboard) von Jelly-Car auf dem Android Auto Display.
 *
 * Bietet schnellen Zugriff auf:
 * - "Weiter ansehen" (Fortsetzen von Filmen & Episoden)
 * - "Filme" (Komplette Filmbibliothek)
 * - "Serien" (TV-Serienübersicht)
 * - "Neueste Videos" (Zuletzt hinzugefügt)
 * - Eigene Medienordner (UserViews)
 * - Volltext- & Sprachsuche im Auto
 */
@UnstableApi
class CarMainScreen(
    carContext: CarContext,
    private val apiClient: ApiClient,
    private val playerManager: CarVideoPlayerManager,
    private val imageHelper: CarImageHelper,
) : Screen(carContext) {

    private var videoLibraries: List<BaseItemDto> = emptyList()
    private var isLoading = true

    init {
        loadLibraries()
    }

    private fun loadLibraries() {
        lifecycleScope.launch {
            if (apiClient.baseUrl == null || apiClient.accessToken == null) {
                isLoading = false
                invalidate()
                return@launch
            }

            try {
                val views = withContext(Dispatchers.IO) {
                    apiClient.userViewsApi.getUserViews().content.items
                }
                // Nur video-relevante Mediatheken filtern
                videoLibraries = views.filter {
                    it.collectionType in setOf(
                        CollectionType.MOVIES,
                        CollectionType.TVSHOWS,
                        CollectionType.HOMEVIDEOS,
                        CollectionType.MUSICVIDEOS,
                        CollectionType.BOXSETS,
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Fehler beim Laden der Mediatheken für Android Auto")
            } finally {
                isLoading = false
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        // Falls noch nicht auf dem Smartphone angemeldet
        if (apiClient.baseUrl == null || apiClient.accessToken == null) {
            return MessageTemplate.Builder("Willkommen bei Jelly-Car!\n\nBitte öffne die App auf deinem Smartphone und melde dich mit deinem Jellyfin Server an.")
                .setTitle("Jelly-Car")
                .addAction(
                    Action.Builder()
                        .setTitle("Erneut prüfen")
                        .setOnClickListener {
                            isLoading = true
                            invalidate()
                            loadLibraries()
                        }
                        .build()
                )
                .build()
        }

        val listBuilder = ItemList.Builder()

        // 1. Serien
        val seriesIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_tv_series)).build()
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Serien")
                .addText("TV-Serien, Staffeln und Episoden")
                .setImage(seriesIcon)
                .setOnClickListener {
                    screenManager.push(
                        CarMediaListScreen(
                            carContext,
                            apiClient,
                            playerManager,
                            imageHelper,
                            MediaListType.SERIES,
                            "Serien",
                        )
                    )
                }
                .build()
        )

        // 3. Filme
        val moviesIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_local_movies_white_64)).build()
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Filme")
                .addText("Alle Spielfilme durchstöbern")
                .setImage(moviesIcon)
                .setOnClickListener {
                    screenManager.push(
                        CarMediaListScreen(
                            carContext,
                            apiClient,
                            playerManager,
                            imageHelper,
                            MediaListType.MOVIES,
                            "Filme",
                        )
                    )
                }
                .build()
        )

        // 4. Live-TV
        val liveTvIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_live_tv)).build()
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Live-TV")
                .addText("Live-Fernsehen & TV-Sender")
                .setImage(liveTvIcon)
                .setOnClickListener {
                    screenManager.push(
                        CarMediaListScreen(
                            carContext,
                            apiClient,
                            playerManager,
                            imageHelper,
                            MediaListType.LIVE_TV,
                            "Live-TV",
                        )
                    )
                }
                .build()
        )

        // 5. Schnellzugriff: Weiter ansehen
        val resumeIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_recently_played)).build()
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Weiter ansehen")
                .addText("Angefangene Filme und Episoden fortsetzen")
                .setImage(resumeIcon)
                .setOnClickListener {
                    screenManager.push(
                        CarMediaListScreen(
                            carContext,
                            apiClient,
                            playerManager,
                            imageHelper,
                            MediaListType.RESUME,
                            "Weiter ansehen",
                        )
                    )
                }
                .build()
        )

        // 5. Schnellzugriff: Neueste Medien
        val latestIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_fast_forward_black_32dp)).build()
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Neueste Videos")
                .addText("Kürzlich zur Mediathek hinzugefügt")
                .setImage(latestIcon)
                .setOnClickListener {
                    screenManager.push(
                        CarMediaListScreen(
                            carContext,
                            apiClient,
                            playerManager,
                            imageHelper,
                            MediaListType.LATEST,
                            "Neueste Videos",
                        )
                    )
                }
                .build()
        )

        // 6. Individuelle Video-Bibliotheken (z. B. "Heimvideos", "Dokumentationen")
        videoLibraries.forEach { lib ->
            val libTitle = lib.name ?: "Bibliothek"
            val libType = when (lib.collectionType) {
                CollectionType.MOVIES -> MediaListType.MOVIES
                CollectionType.TVSHOWS -> MediaListType.SERIES
                else -> MediaListType.FOLDER
            }

            listBuilder.addItem(
                Row.Builder()
                    .setTitle(libTitle)
                    .addText("Mediathek öffnen")
                    .setOnClickListener {
                        screenManager.push(
                            CarMediaListScreen(
                                carContext,
                                apiClient,
                                playerManager,
                                imageHelper,
                                libType,
                                libTitle,
                                parentId = lib.id,
                            )
                        )
                    }
                    .build()
            )
        }

        // Header mit Such- und Wiedergabe-Aktion
        val actionStripBuilder = ActionStrip.Builder()
        if (playerManager.currentItem != null) {
            actionStripBuilder.addAction(
                Action.Builder()
                    .setTitle("Wiedergabe")
                    .setOnClickListener {
                        playerManager.currentItem?.let {
                            screenManager.push(CarVideoPlayerScreen(carContext, playerManager, it))
                        }
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
            .setTitle("Jelly-Car")
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(actionStripBuilder.build())
            .setSingleList(listBuilder.build())
            .build()
    }
}
