package org.jellyfin.mobile.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.runBlocking
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.player.source.MediaSourceResolver
import org.jellyfin.sdk.api.client.ApiClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Verwaltet die Android Auto Sitzung für Jelly-Car.
 */
@UnstableApi
class CarVideoSession : Session(), KoinComponent {

    private val apiClientController: ApiClientController by inject()
    private val apiClient: ApiClient by inject()
    private val mediaSourceResolver: MediaSourceResolver by inject()

    private val playerManager: CarVideoPlayerManager by lazy {
        CarVideoPlayerManager.getInstance(carContext, apiClient, mediaSourceResolver)
    }

    private val imageHelper: CarImageHelper by lazy {
        CarImageHelper(carContext, apiClient)
    }

    override fun onCreateScreen(intent: Intent): Screen {
        // Sicherstellen, dass die Server-Verbindungsdaten und Zugangsdaten geladen sind
        runBlocking {
            try {
                apiClientController.loadSavedServerUser()
            } catch (e: Exception) {
                // Bei Offline-Verbindung oder Fehler abfangen
            }
        }

        return CarMainScreen(
            carContext = carContext,
            apiClient = apiClient,
            playerManager = playerManager,
            imageHelper = imageHelper,
        )
    }
}
