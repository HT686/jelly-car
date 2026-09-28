package org.jellyfin.mobile.sessionbrowser.page

import org.jellyfin.mobile.sessionbrowser.LibraryItemAction
import org.jellyfin.mobile.sessionbrowser.LibraryPageElement
import org.jellyfin.mobile.sessionbrowser.LibraryRoute
import org.jellyfin.mobile.sessionbrowser.libraryPage
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.tvShowsApi

/**
 * Zeigt Staffeln einer TV-Serie für die Android Auto Medienanzeige an.
 */
val SeasonsLibraryPage = { api: ApiClient ->
    libraryPage<LibraryRoute.Seasons>(grid = false) { route, offset, limit ->
        val result by api.tvShowsApi.getSeasons(
            seriesId = route.seriesId,
        )

        result.items
            .drop(offset)
            .take(limit)
            .map {
                LibraryPageElement.baseItem(
                    api = api,
                    item = it,
                    action = LibraryItemAction.Navigate(LibraryRoute.Episodes(route.seriesId, it.id)),
                )
            }
    }
}
