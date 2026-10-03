package dev.pampa.fluidify.wear.screenshots

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import coil.Coil
import coil.ImageLoader
import dev.antigravity.fluidengine.wear.ambient.FluidAmbientState
import dev.antigravity.fluidengine.wear.ambient.LocalFluidWearAmbient
import dev.antigravity.fluidengine.wear.theme.FluidWearTheme
import dev.pampa.fluidify.wear.link.ArtStore
import dev.pampa.fluidify.wear.link.LinkStatus
import dev.pampa.fluidify.wear.link.ReceivedSnapshot
import dev.pampa.fluidify.wear.playback.NowPlaying
import dev.pampa.fluidify.wear.playback.PlaybackControls
import dev.pampa.fluidify.wear.protocol.DeviceInfo
import dev.pampa.fluidify.wear.protocol.DeviceKind
import dev.pampa.fluidify.wear.protocol.PlaybackSnapshot
import dev.pampa.fluidify.wear.protocol.PlaybackSource
import dev.pampa.fluidify.wear.protocol.RepeatMode
import dev.pampa.fluidify.wear.protocol.TrackInfo
import dev.pampa.fluidify.wear.ui.theme.FluidifyWearBrand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream

/** The Galaxy Watch 7 44 mm and 40 mm screens: 480 and 432 px at xhdpi. */
const val Watch44 = "w240dp-h240dp-small-notlong-round-watch-xhdpi-keyshidden-nonav"
const val Watch40 = "w216dp-h216dp-small-notlong-round-watch-xhdpi-keyshidden-nonav"

/** Draws [content] the way the watch shows it: themed, on black, cut to a circle. */
@Composable
fun WatchFrame(ambient: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFluidWearAmbient provides FluidAmbientState.preview(isAmbient = ambient)) {
        FluidWearTheme(brand = FluidifyWearBrand) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.Black)) { content() }
        }
    }
}

/** Images decode on the calling thread, so a capture right after composition has them. */
fun installSynchronousImageLoader(context: Context) {
    Coil.setImageLoader(
        ImageLoader.Builder(context)
            .dispatcher(Dispatchers.Unconfined)
            .build(),
    )
}

/** A synthetic album cover: warm gradient, a sun, a horizon. No real artwork in the repo. */
fun sampleCover(): ByteArray {
    val size = 480
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = LinearGradient(0f, 0f, 0f, size.toFloat(), intArrayOf(0xFF2B1055.toInt(), 0xFFD53A9D.toInt(), 0xFFFFB86B.toInt()), null, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
    paint.shader = RadialGradient(size * 0.5f, size * 0.58f, size * 0.22f, 0xFFFFF1C1.toInt(), 0xFFFF7A59.toInt(), Shader.TileMode.CLAMP)
    canvas.drawCircle(size * 0.5f, size * 0.58f, size * 0.2f, paint)
    paint.shader = null
    paint.color = 0xFF1A0B2E.toInt()
    canvas.drawRect(0f, size * 0.7f, size.toFloat(), size.toFloat(), paint)
    paint.color = 0x55FFFFFF
    for (i in 0 until 6) canvas.drawRect(0f, size * (0.73f + i * 0.04f), size.toFloat(), size * (0.735f + i * 0.04f), paint)
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
}

fun storeSampleCover(art: ArtStore): String {
    art.store(SampleArtKey, sampleCover())
    return SampleArtKey
}

const val SampleArtKey = "samplecover"

/** One instant for every sample, so the ring is drawn where the snapshot says. */
val Now: Long = System.currentTimeMillis()

fun sampleSnapshot(
    playing: Boolean = true,
    liked: Boolean? = false,
    artKey: String? = SampleArtKey,
    title: String = "Notturno sul lago",
    artist: String = "Aurora Viola",
    device: DeviceInfo = DeviceInfo("phone", "Galaxy S25", DeviceKind.PHONE, isThisPhone = true, volume = 0.5f, canSetVolume = true),
) = PlaybackSnapshot(
    seq = 1,
    sentAtEpochMs = Now,
    source = PlaybackSource.PHONE,
    track = TrackInfo(
        uri = "spotify:track:0000000000000000000001",
        title = title,
        artist = artist,
        durationMs = 214_000,
        artKey = artKey,
    ),
    positionMs = 82_000,
    sampledAtEpochMs = Now,
    isPlaying = playing,
    playWhenReady = playing,
    shuffle = true,
    repeat = RepeatMode.ALL,
    liked = liked,
    hasNext = true,
    hasPrevious = true,
    device = device,
)

/** Controls that do nothing and show a fixed state. */
class FakeControls(snapshot: PlaybackSnapshot?, link: LinkStatus = LinkStatus.CONNECTED) : PlaybackControls {
    // receivedAt far in the future relative to sampledAt is avoided: positions are computed from 0.
    override val nowPlaying: StateFlow<NowPlaying> =
        MutableStateFlow(NowPlaying(snapshot?.let { ReceivedSnapshot(it, Now) }, link))
    override val errors: SharedFlow<String> = MutableSharedFlow()
    override fun togglePlay() = Unit
    override fun next() = Unit
    override fun previous() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun setShuffle(enabled: Boolean) = Unit
    override fun setRepeat(mode: RepeatMode) = Unit
    override fun setLiked(liked: Boolean) = Unit
}
