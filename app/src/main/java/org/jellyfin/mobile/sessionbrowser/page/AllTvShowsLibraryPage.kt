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
 * Zeigt alle Serien aus allen Mediatheken für Android Auto an.
 * Klick auf eine Serie führt zur Staffelauswahl (SeasonsLibraryPage).
 */
val AllTvShowsLibraryPage = { api: ApiClient ->
    libraryPage<LibraryRoute.TvShowsRoot>(grid = true) { _, offset, limit ->
        val result by api.itemsApi.getItems(
            includeItemTypes = listOf(BaseItemKind.SERIES),
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
                action = LibraryItemAction.Navigate(LibraryRoute.Seasons(it.id)),
            )
        }
    }
}
