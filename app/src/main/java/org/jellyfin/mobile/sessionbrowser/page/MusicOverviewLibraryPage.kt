package org.jellyfin.mobile.sessionbrowser.page

import org.jellyfin.mobile.R
import org.jellyfin.mobile.sessionbrowser.LibraryItemAction
import org.jellyfin.mobile.sessionbrowser.LibraryPageElement
import org.jellyfin.mobile.sessionbrowser.LibraryRoute
import org.jellyfin.mobile.sessionbrowser.libraryPage

/**
 * Übersicht für das Musik-Interface in Android Auto.
 *
 * Bietet schnellen, direkten Zugriff auf:
 * - Interpreten (Artist-View)
 * - Alben (Album-View)
 * - Alle Titel (Song-Liste)
 * - Wiedergabelisten (Playlists)
 * - Favoriten
 */
val MusicOverviewLibraryPage = libraryPage<LibraryRoute.MusicOverview>(grid = true) { _, offset, limit ->
    listOf(
        LibraryPageElement.Item(
            title = "Interpreten",
            artist = "Alle Künstler durchsuchen",
            iconRes = R.drawable.ic_artist,
            action = LibraryItemAction.Navigate(LibraryRoute.AllArtists()),
        ),
        LibraryPageElement.Item(
            title = "Alben",
            artist = "Komplette Alben-Sammlung",
            iconRes = R.drawable.ic_album,
            action = LibraryItemAction.Navigate(LibraryRoute.AllAlbums()),
        ),
        LibraryPageElement.Item(
            title = "Alle Titel",
            artist = "Alle Songs durchstöbern",
            iconRes = R.drawable.ic_music_note_white_24dp,
            action = LibraryItemAction.Navigate(LibraryRoute.AllSongs()),
        ),
        LibraryPageElement.Item(
            title = "Wiedergabelisten",
            artist = "Eigene Playlists",
            iconRes = R.drawable.ic_playlist,
            action = LibraryItemAction.Navigate(LibraryRoute.Playlists),
        ),
        LibraryPageElement.Item(
            title = "Favoriten",
            artist = "Lieblingstitel",
            iconRes = R.drawable.ic_favorite,
            action = LibraryItemAction.Navigate(LibraryRoute.Favorites()),
        ),
    ).drop(offset).take(limit)
}
