package org.jellyfin.mobile.sessionbrowser.page

import org.jellyfin.mobile.R
import org.jellyfin.mobile.sessionbrowser.LibraryItemAction
import org.jellyfin.mobile.sessionbrowser.LibraryPageElement
import org.jellyfin.mobile.sessionbrowser.LibraryRoute
import org.jellyfin.mobile.sessionbrowser.libraryPage
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.api.CollectionType
import timber.log.Timber

/**
 * Root library page für Android Auto:
 *
 * Stellt die 4 primären Medientypen als direkte Hauptkategorien bereit:
 * 1. Musik (Interpreten, Alben, Songs, Playlists)
 * 2. Serien (Serien -> Staffeln -> Episoden)
 * 3. Filme (Komplette Filmbibliothek)
 * 4. Live TV (TV-Sender)
 */
val RootLibraryPage = { api: ApiClient ->
    libraryPage<LibraryRoute.Root>(grid = true) { _, offset, limit ->
        buildList {
            // 1. Musik
            add(
                LibraryPageElement.Item(
                    title = "Musik",
                    artist = "Interpreten, Alben & Titel",
                    iconRes = R.drawable.ic_music_note_white_24dp,
                    action = LibraryItemAction.Navigate(LibraryRoute.MusicOverview),
                )
            )

            // 2. Serien
            add(
                LibraryPageElement.Item(
                    title = "Serien",
                    artist = "Staffeln & Episoden",
                    iconRes = R.drawable.ic_tv_series,
                    action = LibraryItemAction.Navigate(LibraryRoute.TvShowsRoot),
                )
            )

            // 3. Filme
            add(
                LibraryPageElement.Item(
                    title = "Filme",
                    artist = "Spielfilme & Dokumentationen",
                    iconRes = R.drawable.ic_local_movies_white_64,
                    action = LibraryItemAction.Navigate(LibraryRoute.MoviesRoot),
                )
            )

            // 4. Live TV
            add(
                LibraryPageElement.Item(
                    title = "Live TV",
                    artist = "Fernsehen & Live-Streams",
                    iconRes = R.drawable.ic_live_tv,
                    action = LibraryItemAction.Navigate(LibraryRoute.LiveTvRoot),
                )
            )

            // Zusätzliche Bibliotheken (z.B. Hörbücher / Books), falls vorhanden
            try {
                val userViews by api.userViewsApi.getUserViews()
                userViews.items
                    .filter { it.collectionType == CollectionType.BOOKS }
                    .forEach {
                        add(
                            LibraryPageElement.Item(
                                title = it.name ?: "Hörbücher",
                                artist = "Audiobooks",
                                iconRes = R.drawable.ic_audiobooks,
                                action = LibraryItemAction.Navigate(LibraryRoute.Library(it.id, it.collectionType)),
                            )
                        )
                    }
            } catch (e: Exception) {
                Timber.w(e, "Konnte zusätzliche Mediatheken nicht abrufen")
            }
        }.drop(offset).take(limit)
    }
}
