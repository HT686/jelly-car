package org.jellyfin.mobile.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.extensions.ticks

/**
 * Detailansicht für einen Film oder eine Episode auf dem Auto-Display.
 * Zeigt Metadaten, Laufzeit, Inhaltsangabe sowie "Fortsetzen"- und "Neustart"-Aktionen.
 */
@UnstableApi
class CarMediaDetailScreen(
    carContext: CarContext,
    private val apiClient: ApiClient,
    private val playerManager: CarVideoPlayerManager,
    private val imageHelper: CarImageHelper,
    private val item: BaseItemDto,
) : Screen(carContext) {

    private var posterIcon: CarIcon? = null

    init {
        lifecycleScope.launch {
            posterIcon = imageHelper.loadCarIcon(item.id, maxWidth = 300, maxHeight = 300)
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val paneBuilder = Pane.Builder()

        // 1. Laufzeit und Erscheinungsjahr
        val metaInfo = buildString {
            if (item.productionYear != null) append("${item.productionYear}  •  ")
            val runTimeMs = item.runTimeTicks?.ticks?.inWholeMilliseconds ?: 0L
            if (runTimeMs > 0L) append(formatRuntime(runTimeMs))
            if (item.officialRating != null) append("  •  ${item.officialRating}")
        }
        if (metaInfo.isNotEmpty()) {
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("Informationen")
                    .addText(metaInfo)
                    .build()
            )
        }

        // 2. Inhaltsübersicht
        val overview = item.overview?.trim()
        if (!overview.isNullOrEmpty()) {
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("Handlung")
                    .addText(overview.take(280) + if (overview.length > 280) "..." else "")
                    .build()
            )
        }

        // 3. Wiedergabe-Aktionen (Fortsetzen oder Neu starten)
        val resumeTicks = item.userData?.playbackPositionTicks ?: 0L
        val resumeMs = resumeTicks.ticks.inWholeMilliseconds

        if (resumeMs > 10_000L) {
            paneBuilder.addAction(
                Action.Builder()
                    .setTitle("Fortsetzen (${formatRuntime(resumeMs)})")
                    .setOnClickListener {
                        screenManager.push(CarVideoPlayerScreen(carContext, playerManager, item, resumeMs))
                    }
                    .build()
            )
            paneBuilder.addAction(
                Action.Builder()
                    .setTitle("Von Beginn ansehen")
                    .setOnClickListener {
                        screenManager.push(CarVideoPlayerScreen(carContext, playerManager, item, 0L))
                    }
                    .build()
            )
        } else {
            paneBuilder.addAction(
                Action.Builder()
                    .setTitle("Video abspielen")
                    .setOnClickListener {
                        screenManager.push(CarVideoPlayerScreen(carContext, playerManager, item, 0L))
                    }
                    .build()
            )
        }

        posterIcon?.let { paneBuilder.setImage(it) }

        return PaneTemplate.Builder(paneBuilder.build())
            .setTitle(item.name.orEmpty())
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun formatRuntime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }
}
