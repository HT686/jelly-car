package org.jellyfin.mobile.sessionbrowser.page

import org.jellyfin.mobile.sessionbrowser.LibraryItemAction
import org.jellyfin.mobile.sessionbrowser.LibraryPageElement
import org.jellyfin.mobile.sessionbrowser.LibraryRoute
import org.jellyfin.mobile.sessionbrowser.libraryPage
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import timber.log.Timber

/**
 * Zeigt Live-TV-Sender für Android Auto an.
 * Klick auf einen Sender startet den Live-Stream.
 */
val LiveTvLibraryPage = { api: ApiClient ->
    libraryPage<LibraryRoute.LiveTvRoot>(grid = false) { _, offset, limit ->
        try {
            val result by api.liveTvApi.getLiveTvChannels(
                startIndex = offset,
                limit = limit,
            )

            result.items.map {
                LibraryPageElement.baseItem(
                    api = api,
                    item = it,
                    title = it.name ?: "TV-Sender",
                    artist = it.currentProgram?.name ?: "Live-Programm",
                    action = LibraryItemAction.Play(it),
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Fehler beim Laden von Live-TV-Sendern")
            emptyList()
        }
    }
}
