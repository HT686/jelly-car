package org.jellyfin.mobile.sessionbrowser.page

import org.jellyfin.mobile.sessionbrowser.LibraryItemAction
import org.jellyfin.mobile.sessionbrowser.LibraryPageElement
import org.jellyfin.mobile.sessionbrowser.LibraryRoute
import org.jellyfin.mobile.sessionbrowser.libraryPage
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi

/**
 * Zeigt Heimvideos, Musikvideos und sonstige Videos für die Android Auto Medienanzeige an.
 */
val VideosLibraryPage = { api: ApiClient ->
    libraryPage<LibraryRoute.Videos>(grid = true) { route, offset, limit ->
        val result by api.itemsApi.getItems(
            parentId = route.libraryId,
            recursive = true,
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
