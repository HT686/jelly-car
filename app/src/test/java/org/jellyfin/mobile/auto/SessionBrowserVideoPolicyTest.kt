package org.jellyfin.mobile.auto

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.mobile.sessionbrowser.LibraryItemAction
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import org.junit.jupiter.api.Test
import java.util.UUID

class SessionBrowserVideoPolicyTest {

    private fun isPlayableInMediaBrowser(action: LibraryItemAction): Boolean {
        val isVideoItem = when (action) {
            is LibraryItemAction.Play -> {
                val kind = action.item.type
                val mediaType = action.item.mediaType
                kind == BaseItemKind.MOVIE || kind == BaseItemKind.EPISODE || kind == BaseItemKind.VIDEO || mediaType == MediaType.VIDEO
            }
            else -> false
        }
        return action is LibraryItemAction.Play && !isVideoItem
    }

    private fun shouldAcceptInAudioMediaBrowser(item: BaseItemDto, isLiveTvRoute: Boolean = false): Boolean {
        val isLiveTv = item.type == BaseItemKind.LIVE_TV_CHANNEL || isLiveTvRoute
        val isAudio = item.type == BaseItemKind.AUDIO || item.mediaType == MediaType.AUDIO
        return isLiveTv || isAudio
    }

    @Test
    fun `movie items are not playable in MediaBrowserService`() {
        val movie = mockk<BaseItemDto> {
            every { id } returns UUID.randomUUID()
            every { name } returns "Inception"
            every { type } returns BaseItemKind.MOVIE
            every { mediaType } returns MediaType.VIDEO
        }

        val action = LibraryItemAction.Play(movie)
        isPlayableInMediaBrowser(action) shouldBe false
        shouldAcceptInAudioMediaBrowser(movie) shouldBe false
    }

    @Test
    fun `episode items are not playable in MediaBrowserService`() {
        val episode = mockk<BaseItemDto> {
            every { id } returns UUID.randomUUID()
            every { name } returns "Episode 1"
            every { type } returns BaseItemKind.EPISODE
            every { mediaType } returns MediaType.VIDEO
        }

        val action = LibraryItemAction.Play(episode)
        isPlayableInMediaBrowser(action) shouldBe false
        shouldAcceptInAudioMediaBrowser(episode) shouldBe false
    }

    @Test
    fun `audio items remain playable in MediaBrowserService`() {
        val audioTrack = mockk<BaseItemDto> {
            every { id } returns UUID.randomUUID()
            every { name } returns "Song 1"
            every { type } returns BaseItemKind.AUDIO
            every { mediaType } returns MediaType.AUDIO
        }

        val action = LibraryItemAction.Play(audioTrack)
        isPlayableInMediaBrowser(action) shouldBe true
        shouldAcceptInAudioMediaBrowser(audioTrack) shouldBe true
    }

    @Test
    fun `live tv items remain playable in MediaBrowserService`() {
        val liveTvChannel = mockk<BaseItemDto> {
            every { id } returns UUID.randomUUID()
            every { name } returns "News HD"
            every { type } returns BaseItemKind.LIVE_TV_CHANNEL
            every { mediaType } returns MediaType.UNKNOWN
        }

        shouldAcceptInAudioMediaBrowser(liveTvChannel) shouldBe true
    }
}
