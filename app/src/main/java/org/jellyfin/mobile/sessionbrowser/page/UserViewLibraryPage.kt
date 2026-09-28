package org.jellyfin.mobile.sessionbrowser.page

import android.content.Context
import org.jellyfin.mobile.R
import org.jellyfin.mobile.sessionbrowser.LibraryItemAction
import org.jellyfin.mobile.sessionbrowser.LibraryPageElement
import org.jellyfin.mobile.sessionbrowser.LibraryRoute
import org.jellyfin.mobile.sessionbrowser.libraryPage
import org.jellyfin.sdk.model.api.CollectionType

val UserViewLibraryPage = { context: Context ->
    libraryPage<LibraryRoute.Library> { route, offset, limit ->
        buildList {
            val isMusic = route.collectionType == CollectionType.MUSIC
            val isBooks = route.collectionType == CollectionType.BOOKS
            val isMovies = route.collectionType == CollectionType.MOVIES
            val isShows = route.collectionType == CollectionType.TVSHOWS
            val isVideos = route.collectionType == CollectionType.HOMEVIDEOS || route.collectionType == CollectionType.MUSICVIDEOS

            if (isMovies) {
                add(
                    LibraryPageElement.Item(
                        title = "Filme",
                        iconRes = R.drawable.ic_local_movies_white_64,
                        action = LibraryItemAction.Navigate(LibraryRoute.Movies(route.libraryId)),
                    ),
                )
            }

            if (isShows) {
                add(
                    LibraryPageElement.Item(
                        title = "Serien",
                        iconRes = R.drawable.app_logo,
                        action = LibraryItemAction.Navigate(LibraryRoute.TvShows(route.libraryId)),
                    ),
                )
            }

            if (isVideos) {
                add(
                    LibraryPageElement.Item(
                        title = "Videos",
                        iconRes = R.drawable.ic_local_movies_white_64,
                        action = LibraryItemAction.Navigate(LibraryRoute.Videos(route.libraryId)),
                    ),
                )
            }

            if (isMusic) {
                add(
                    LibraryPageElement.Item(
                        title = "Interpreten",
                        iconRes = R.drawable.ic_artist,
                        action = LibraryItemAction.Navigate(LibraryRoute.AllArtists(route.libraryId)),
                    ),
                )

                add(
                    LibraryPageElement.Item(
                        title = "Alben",
                        iconRes = R.drawable.ic_album,
                        action = LibraryItemAction.Navigate(LibraryRoute.AllAlbums(route.libraryId)),
                    ),
                )

                add(
                    LibraryPageElement.Item(
                        title = "Alle Titel",
                        iconRes = R.drawable.ic_music_note_white_24dp,
                        action = LibraryItemAction.Navigate(LibraryRoute.AllSongs(route.libraryId)),
                    ),
                )
            }

            if (isBooks) {
                add(
                    LibraryPageElement.Item(
                        title = context.getString(R.string.media_service_car_section_audiobooks),
                        iconRes = R.drawable.ic_audiobooks,
                        action = LibraryItemAction.Navigate(LibraryRoute.AudioBooksAlpha(route.libraryId)),
                    ),
                )
            }

            add(
                LibraryPageElement.Item(
                    title = context.getString(R.string.media_service_car_section_favorites),
                    iconRes = R.drawable.ic_favorite,
                    action = LibraryItemAction.Navigate(LibraryRoute.Favorites(route.libraryId)),
                ),
            )

            add(
                LibraryPageElement.Item(
                    title = context.getString(R.string.media_service_car_section_genres),
                    iconRes = R.drawable.ic_genres,
                    action = LibraryItemAction.Navigate(LibraryRoute.Genres(route.libraryId)),
                ),
            )

            add(
                LibraryPageElement.Item(
                    title = context.getString(R.string.media_service_car_section_recents),
                    iconRes = R.drawable.ic_recently_played,
                    action = LibraryItemAction.Navigate(LibraryRoute.Recent(route.libraryId)),
                ),
            )
        }.drop(offset).take(limit)
    }
}
