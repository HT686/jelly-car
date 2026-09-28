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
 * Album-View: Zeigt alle Musikalben für Android Auto mit Cover-Artwork an.
 * Klick auf ein Album öffnet die Trackliste zum direkten Abspielen.
 */
val AllAlbumsLibraryPage = { api: ApiClient ->
    libraryPage<LibraryRoute.AllAlbums>(grid = true) { route, offset, limit ->
        val result by api.itemsApi.getItems(
            parentId = route.libraryId,
            includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
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
                action = LibraryItemAction.Navigate(LibraryRoute.Album(it.id)),
            )
        }
    }
}
