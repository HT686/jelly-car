package org.jellyfin.mobile.sessionbrowser.page

import org.jellyfin.mobile.sessionbrowser.LibraryItemAction
import org.jellyfin.mobile.sessionbrowser.LibraryPageElement
import org.jellyfin.mobile.sessionbrowser.LibraryRoute
import org.jellyfin.mobile.sessionbrowser.libraryPage
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder

/**
 * Zeigt alle Spielfilme für Android Auto an.
 * Klick auf einen Film startet die Wiedergabe.
 */
val AllMoviesLibraryPage = { api: ApiClient ->
    libraryPage<LibraryRoute.MoviesRoot>(grid = true) { _, offset, limit ->
        val result by api.itemsApi.getItems(
            includeItemTypes = listOf(BaseItemKind.MOVIE),
            sortBy = listOf(ItemSortBy.SORT_NAME),
            sortOrder = listOf(SortOrder.ASCENDING),
            recursive = true,
            imageTypeLimit = 1,
            enableImageTypes = listOf(ImageType.PRIMARY),
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
