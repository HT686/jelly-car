package org.jellyfin.mobile.sessionbrowser.page

import org.jellyfin.mobile.R
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
            .map { season ->
                val seasonName = season.name ?: "Staffel ${season.indexNumber ?: ""}".trim()
                LibraryPageElement.baseItem(
                    api = api,
                    item = season,
                    title = seasonName,
                    iconRes = R.drawable.ic_tv_series,
                    action = LibraryItemAction.Navigate(LibraryRoute.Episodes(route.seriesId, season.id)),
                )
            }
    }
}
