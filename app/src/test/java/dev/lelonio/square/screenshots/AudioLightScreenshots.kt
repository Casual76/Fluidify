package dev.lelonio.square.screenshots

import android.app.Application
import android.graphics.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.lelonio.square.playback.AudioLightFrame
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.glass.*
import dev.lelonio.square.ui.glass.backdrop.backdrops.*
import dev.lelonio.square.ui.player.*
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.annotation.*
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[36],application=Application::class,qualifiers="w412dp-h892dp-xhdpi")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioLightScreenshots {
    @get:Rule val compose=createComposeRule()
    private fun capture(name:String,light:Boolean=false,mini:Boolean=false,compact:Boolean=false,bottom:Boolean=false,enabled:Boolean=true,flat:Boolean=false,canvasVisible:Boolean=false,canvasLoading:Boolean=false) {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val bitmap=Bitmap.createBitmap(480,480,Bitmap.Config.ARGB_8888)
        val paint=Paint(); paint.shader=LinearGradient(0f,0f,480f,480f,
            intArrayOf(if(light) 0xfffaf5dd.toInt() else 0xff26154e.toInt(),0xffe27892.toInt(),0xffffc870.toInt()),null,Shader.TileMode.CLAMP)
        Canvas(bitmap).drawRect(0f,0f,480f,480f,paint)
        val file=File(context.cacheDir,"audio-light-cover.png");file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(context).dispatcher(kotlinx.coroutines.Dispatchers.Unconfined).build())
        val state=PlaybackState(hasItem=true,title="Notturno sul lago",artist="Aurora Viola",artworkUrl=file.toURI().toString(),isPlaying=true,wantsPlay=true,durationMs=214000,hasNext=true,hasPrevious=true)
        val frame=mutableStateOf<AudioLightFrame?>(if(enabled) AudioLightFrame(energy=.7f,bass=.9f,mids=.5f,treble=.3f,positionMs=82000,bands=listOf(.8f,.9f,.7f,.4f,.6f,.5f,.3f,.2f)) else null)
        compose.mainClock.autoAdvance=false
        compose.setContent {
            val backdrop=rememberLayerBackdrop(); val ground=rememberGlassBackdrop()
            SquareTheme(darkTheme=!light,amoled=false,systemBars=false) {
                CompositionLocalProvider(LocalAppBackdrop provides backdrop,LocalAudioLightPreview provides frame) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(if(light) Color(0xffeaded4) else Color(0xff261f32)))
                        if(mini) FloatingMiniPlayer(state,remember{mutableLongStateOf(82000)},
                            modifier=Modifier.align(Alignment.Center).padding(horizontal=20.dp),inline=compact,lightGlassConfig=GlassEffectConfig(style=if(flat) GlassStyle.TRANSPARENT else GlassStyle.LIQUID),
                            onClick={},onTogglePlay={},onNext={},onPrevious={},onSeek={})
                        else PlayerScreen(state,remember{mutableLongStateOf(82000)},onCollapse={},onTogglePlay={},onNext={},onPrevious={},onSeek={},
                            onToggleShuffle={},onCycleRepeat={},queue=emptyList(),lyrics=null,lyricsLoading=false,credits=null,creditsLoading=false,
                            onWantCredits={},onWantArtist={_,_->},onPlayQueueItem={},onRemoveQueueItem={},onMoveQueueItem={_,_->},
                            backdrop=backdrop,canvas=if(canvasVisible || canvasLoading) dev.lelonio.square.data.CanvasClip(
                                if(canvasLoading) "file:///missing-canvas.png" else file.toURI().toString(),false) else null,
                            devices=MainViewModel.DevicesState(),onOpenDevices={},onCloseDevices={},onMorphDrag={},onMorphRelease={},
                            playerOpen={true},ground=ground,artist=null,artistLoading=false,onRefreshDevices={},onSelectDevice={},onSetDeviceVolume={_,_->},
                            onAddToPlaylist={},addToPlaylist=MainViewModel.AddToPlaylistState(),onPickPlaylist={},videoOn=bottom)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1500)
        // Let the real draw-phase smoothing settle, including the existing opening freeze.
        Thread.sleep(550)
        repeat(30) {
            compose.runOnIdle { frame.value=frame.value?.copy(dueNs=it.toLong()+1) }
            compose.mainClock.advanceTimeBy(34)
        }
        compose.onRoot().captureRoboImage("screenshots/audio_light_$name.png")
        if (mini && enabled && !flat) {
            val on = BitmapFactory.decodeFile("screenshots/audio_light_$name.png")
            val x = (on.width * .32f).toInt()
            val y = on.height / 2 + if (compact) 35 else 43
            val lit = on.getPixel(x, y)
            compose.runOnIdle { frame.value = null }
            compose.mainClock.advanceTimeBy(34)
            val offFile = "build/audio-light-mini-off-$name.png"
            compose.onRoot().captureRoboImage(offFile)
            val off = BitmapFactory.decodeFile(offFile).getPixel(x, y)
            Assert.assertTrue("The mini player must actually show and remove its light",
                kotlin.math.abs(android.graphics.Color.red(lit) - android.graphics.Color.red(off)) > 20)
        }
    }
    @Test fun phoneDark()=capture("phone_dark")
    @Test fun phoneLight()=capture("phone_light",light=true)
    @Test @Config(qualifiers="w800dp-h1280dp-xhdpi") fun tablet()=capture("tablet")
    @Test fun lowerVideoLight()=capture("lower_video",bottom=true)
    @Test fun miniPlayer()=capture("mini",mini=true)
    @Test fun compactMiniPlayer()=capture("mini_compact",mini=true,compact=true)
    @Test fun miniDisabled()=capture("mini_disabled",mini=true,enabled=false)
    @Test fun phoneDisabled()=capture("phone_disabled",enabled=false)
    @Test fun flatMini()=capture("mini_flat",mini=true,flat=true)
    @Test fun canvasReady()=capture("canvas_ready",canvasVisible=true)
    @Test fun canvasLoadingKeepsCoverLight()=capture("canvas_loading",canvasLoading=true)
}
