package dev.pampa.fluidify.wear.system

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import dev.pampa.fluidify.wear.R
import dev.pampa.fluidify.wear.WearApp
import dev.pampa.fluidify.wear.protocol.TrackInfo

/**
 * Fluidify on the watch face: the song, the cover, or just the mark.
 *
 * Every shape a face might ask for: a short text (the mark and the song's
 * title, which the face cuts to fit), a long one (title and artist), the cover
 * as a small image or a photo, and the bare mark. All of them open the player.
 * Pushed when the song changes, never polled.
 */
class NowPlayingComplicationService : SuspendingComplicationDataSourceService() {

    private val app get() = application as WearApp

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        // The phone's last word, read straight from the state file: a watch face asking for
        // data has no use for the remote's guesses, and this keeps the request off the link.
        val track = app.state.current.value?.snapshot?.track
        return build(request.complicationType, track)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(
            type,
            TrackInfo(uri = "spotify:track:preview", title = getString(R.string.complication_preview_title), artist = getString(R.string.app_name)),
            preview = true,
        )

    private fun build(type: ComplicationType, track: TrackInfo?, preview: Boolean = false): ComplicationData? {
        val tap = PlayerIntents.openPlayer(this)
        val mark = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_notification)).build()
        val name = getString(R.string.app_name)
        val describe = text(track?.let { "${it.title} · ${it.artist}" } ?: name)
        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(text(track?.title ?: name), describe)
                    .setMonochromaticImage(mark)
                    .setTapAction(tap)
                    .build()
            ComplicationType.LONG_TEXT ->
                LongTextComplicationData.Builder(text(track?.title ?: getString(R.string.nothing_playing)), describe)
                    .setTitle(text(track?.artist?.takeIf { it.isNotEmpty() } ?: name))
                    .setMonochromaticImage(mark)
                    .setTapAction(tap)
                    .build()
            ComplicationType.SMALL_IMAGE -> {
                val cover = if (preview) null else cover(track, SMALL_PX)
                val image = cover?.let { SmallImage.Builder(it, SmallImageType.PHOTO).build() }
                    ?: SmallImage.Builder(Icon.createWithResource(this, R.drawable.ic_notification), SmallImageType.ICON).build()
                SmallImageComplicationData.Builder(image, describe).setTapAction(tap).build()
            }
            ComplicationType.PHOTO_IMAGE -> {
                val cover = (if (preview) null else cover(track, PHOTO_PX))
                    ?: Icon.createWithResource(this, R.mipmap.ic_launcher)
                PhotoImageComplicationData.Builder(cover, describe).setTapAction(tap).build()
            }
            ComplicationType.MONOCHROMATIC_IMAGE ->
                MonochromaticImageComplicationData.Builder(mark, describe).setTapAction(tap).build()
            else -> null
        }
    }

    private fun cover(track: TrackInfo?, sizePx: Int): Icon? =
        CoverImages.bitmap(app.art, track?.artKey, sizePx)?.let(Icon::createWithBitmap)

    private fun text(value: String): ComplicationText = PlainComplicationText.Builder(value).build()

    companion object {
        private const val SMALL_PX = 96
        private const val PHOTO_PX = 240

        fun requestUpdate(context: Context) {
            runCatching {
                ComplicationDataSourceUpdateRequester
                    .create(context, ComponentName(context, NowPlayingComplicationService::class.java))
                    .requestUpdateAll()
            }
        }
    }
}
