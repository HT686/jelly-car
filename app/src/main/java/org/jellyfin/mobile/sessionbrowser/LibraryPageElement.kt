package org.jellyfin.mobile.sessionbrowser

import android.net.Uri
import androidx.annotation.DrawableRes
import org.jellyfin.mobile.R
import org.jellyfin.mobile.ui.content.ImageProvider
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType

sealed interface LibraryPageElement {
    /**
     * A group of items displayed as a section within Android Auto.
     */
    data class Group(
        val title: String,
        val items: List<Item>,
    ) : LibraryPageElement

    /**
     * An item with custom metadata.
     */
    data class Item(
        val title: String,
        val artist: String? = null,
        val album: String? = null,
        val image: Uri? = null,
        @DrawableRes val iconRes: Int? = null,
        val action: LibraryItemAction,
    ) : LibraryPageElement

    companion object {
        fun baseItem(
            api: ApiClient,
            item: BaseItemDto,
            title: String = item.name.orEmpty(),
            artist: String? = item.artists?.joinToString(),
            album: String? = item.album,
            image: Uri? = item.getImage(api),
            @DrawableRes iconRes: Int? = R.drawable.ic_notification,
            action: LibraryItemAction = LibraryItemAction.Play(item),
        ): Item = Item(
            title = title,
            artist = artist,
            album = album,
            image = image,
            iconRes = iconRes,
            action = action,
        )

        private fun BaseItemDto.getImage(api: ApiClient? = null): Uri? {
            val primaryImageTag = imageTags?.get(ImageType.PRIMARY)

            return when {
                primaryImageTag != null -> ImageProvider.buildItemUri(id, ImageType.PRIMARY, primaryImageTag)

                albumId != null && albumPrimaryImageTag != null -> ImageProvider.buildItemUri(
                    requireNotNull(albumId),
                    ImageType.PRIMARY,
                    albumPrimaryImageTag,
                )

                parentId != null && parentPrimaryImageTag != null -> ImageProvider.buildItemUri(
                    requireNotNull(parentId),
                    ImageType.PRIMARY,
                    parentPrimaryImageTag,
                )

                else -> null
            }
        }
    }
}
