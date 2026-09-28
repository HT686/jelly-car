package org.jellyfin.mobile.sessionbrowser.page

import org.jellyfin.mobile.sessionbrowser.LibraryItemAction
import org.jellyfin.mobile.sessionbrowser.LibraryPageElement
import org.jellyfin.mobile.sessionbrowser.LibraryRoute
import org.jellyfin.mobile.sessionbrowser.libraryPage
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.tvShowsApi

/**
 * Zeigt Episoden einer Staffel für die Android Auto Medienanzeige an.
 */
val EpisodesLibraryPage = { api: ApiClient ->
    libraryPage<LibraryRoute.Episodes>(grid = false) { route, offset, limit ->
        val result by api.tvShowsApi.getEpisodes(
            seriesId = route.seriesId,
            seasonId = route.seasonId,
            startIndex = offset,
            limit = limit,
        )

        result.items.map {
            LibraryPageElement.baseItem(
                api = api,
                item = it,
                action = LibraryItemAction.Play(it),
            )
        }
    }
}
