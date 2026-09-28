package org.jellyfin.mobile.sessionbrowser.page

import org.jellyfin.mobile.sessionbrowser.LibraryItemAction
import org.jellyfin.mobile.sessionbrowser.LibraryPageElement
import org.jellyfin.mobile.sessionbrowser.LibraryRoute
import org.jellyfin.mobile.sessionbrowser.libraryPage
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.artistsApi
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder

/**
 * Artist-View: Zeigt alle Interpreten für Android Auto ohne Umweg über Buchstaben-Filter an.
 * Klick auf einen Interpreten öffnet dessen Alben-Übersicht.
 */
val AllArtistsLibraryPage = { api: ApiClient ->
    libraryPage<LibraryRoute.AllArtists>(grid = true) { route, offset, limit ->
        val result by api.artistsApi.getArtists(
            parentId = route.libraryId,
            sortBy = listOf(ItemSortBy.SORT_NAME),
            sortOrder = listOf(SortOrder.ASCENDING),
            imageTypeLimit = 1,
            enableImageTypes = listOf(ImageType.PRIMARY),
            startIndex = offset,
            limit = limit,
        )

        result.items.map {
            LibraryPageElement.baseItem(
                api = api,
                item = it,
                action = LibraryItemAction.Navigate(LibraryRoute.Artist(it.id)),
            )
        }
    }
}
