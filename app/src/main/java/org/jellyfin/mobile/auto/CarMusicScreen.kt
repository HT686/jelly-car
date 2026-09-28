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
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.util.UnstableApi
import org.jellyfin.mobile.R
import org.jellyfin.sdk.api.client.ApiClient

/**
 * Musik-Hauptübersicht für Jelly-Car auf Android Auto.
 *
 * Bietet schnellen und intuitiven Zugriff auf:
 * - Interpreten (Artist-View)
 * - Alben (Album-View mit Cover-Artwork)
 * - Alle Titel (Alphabetische Gesamttitelliste)
 * - Wiedergabelisten (Playlists)
 * - Favoriten (Markierte Lieblingstitel)
 */
@UnstableApi
class CarMusicScreen(
    carContext: CarContext,
    private val apiClient: ApiClient,
    private val playerManager: CarVideoPlayerManager,
    private val imageHelper: CarImageHelper,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()

        // 1. Interpreten
        val artistIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_artist)).build()
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Interpreten")
                .addText("Künstler & Bands durchstöbern")
                .setImage(artistIcon)
                .setOnClickListener {
                    screenManager.push(
                        CarMusicArtistsScreen(carContext, apiClient, playerManager, imageHelper)
                    )
                }
                .build()
        )

        // 2. Alben
        val albumIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_album)).build()
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Alben")
                .addText("Alben mit Cover-Art durchstöbern")
                .setImage(albumIcon)
                .setOnClickListener {
                    screenManager.push(
                        CarMusicAlbumsScreen(carContext, apiClient, playerManager, imageHelper, null, "Alben")
                    )
                }
                .build()
        )

        // 3. Alle Titel
        val songIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_music_note_white_24dp)).build()
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Alle Titel")
                .addText("Alle Songs der Mediathek")
                .setImage(songIcon)
                .setOnClickListener {
                    screenManager.push(
                        CarMusicTracksScreen(
                            carContext,
                            apiClient,
                            playerManager,
                            imageHelper,
                            mode = TrackListMode.ALL_SONGS,
                            title = "Alle Titel",
                        )
                    )
                }
                .build()
        )

        // 4. Wiedergabelisten
        val playlistIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_playlist)).build()
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Wiedergabelisten")
                .addText("Eigene Playlists")
                .setImage(playlistIcon)
                .setOnClickListener {
                    screenManager.push(
                        CarMusicPlaylistsScreen(carContext, apiClient, playerManager, imageHelper)
                    )
                }
                .build()
        )

        // 5. Favoriten
        val favoriteIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_favorite)).build()
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Favoriten")
                .addText("Deine Lieblingstitel")
                .setImage(favoriteIcon)
                .setOnClickListener {
                    screenManager.push(
                        CarMusicTracksScreen(
                            carContext,
                            apiClient,
                            playerManager,
                            imageHelper,
                            mode = TrackListMode.FAVORITES,
                            title = "Favoriten",
                        )
                    )
                }
                .build()
        )

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
            .setTitle("Musik")
            .setHeaderAction(Action.BACK)
            .setActionStrip(actionStripBuilder.build())
            .setSingleList(listBuilder.build())
            .build()
    }
}
