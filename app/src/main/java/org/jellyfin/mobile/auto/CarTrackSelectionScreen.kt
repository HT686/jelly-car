package org.jellyfin.mobile.auto

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.media3.common.util.UnstableApi

/**
 * Bildschirm zur Auswahl der Audiospur während der Videowiedergabe im Auto.
 */
@UnstableApi
class CarTrackSelectionScreen(
    carContext: CarContext,
    private val playerManager: CarVideoPlayerManager,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val tracks = playerManager.getAvailableAudioTracks()
        val listBuilder = ItemList.Builder()

        if (tracks.isEmpty()) {
            listBuilder.setNoItemsMessage("Keine alternativen Audiospuren verfügbar")
        } else {
            tracks.forEachIndexed { index, trackName ->
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle(trackName)
                        .setOnClickListener {
                            playerManager.selectAudioTrack(index)
                            CarToast.makeText(carContext, "Spur ausgewählt: $trackName", CarToast.LENGTH_SHORT).show()
                            screenManager.pop()
                        }
                        .build()
                )
            }
        }

        return ListTemplate.Builder()
            .setTitle("Audiospuren")
            .setHeaderAction(Action.BACK)
            .setSingleList(listBuilder.build())
            .build()
    }
}
