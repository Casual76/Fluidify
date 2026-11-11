package dev.lelonio.square.ui.airpods
import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.ui.fluid.*
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Prepared local synthetic UI captures. No runtime, radio, media or hardware assertions. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[36],application=Application::class,qualifiers="w1400dp-h1000dp-mdpi")
class AirPodsUiScreenshots {
    @get:Rule val compose=createComposeRule()
    private fun capture(w:Int,h:Int,font:Float,dark:Boolean,amoled:Boolean,quick:Boolean, player:Boolean=false) {
        compose.setContent {
            val backdrop=rememberGlassBackdrop()
            val host=rememberFluidGlassModalHostState()
            val density=LocalDensity.current
            SquareTheme(darkTheme=dark,amoled=amoled,systemBars=false) {
                CompositionLocalProvider(LocalDensity provides Density(density.density,font),
                    LocalGlassBackdrop provides backdrop,LocalFluidGlassModalHostState provides host,
                    LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                    Box(Modifier.requiredSize(w.dp,h.dp).testTag("airpods-fixture")) {
                        Row(Modifier.fillMaxSize().glassBackdropSource(backdrop)) {
                            Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.primaryContainer))
                            Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surface))
                            Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.tertiaryContainer))
                        }
                        val state=uiFixture()
                        if(player) dev.lelonio.square.ui.player.PlayerScreen(
                            state=dev.lelonio.square.ui.player.PlaybackState(hasItem=true,title="Fixture song",artist="Fixture artist",durationMs=180000),
                            positionMs=remember{mutableLongStateOf(1000)},onCollapse={},onTogglePlay={},onNext={},onPrevious={},onSeek={},
                            onToggleShuffle={},onCycleRepeat={},queue=emptyList(),lyrics=null,lyricsLoading=false,credits=null,creditsLoading=false,
                            onWantCredits={},onWantArtist={_,_->},onPlayQueueItem={},onRemoveQueueItem={},onMoveQueueItem={_,_->},canvas=null,
                            devices=dev.lelonio.square.ui.MainViewModel.DevicesState(),onOpenDevices={},onCloseDevices={},onMorphDrag={},onMorphRelease={},
                            playerOpen={true},ground=backdrop,artist=null,artistLoading=false,onRefreshDevices={},onSelectDevice={},onSetDeviceVolume={_,_->},
                            onAddToPlaylist={},addToPlaylist=dev.lelonio.square.ui.MainViewModel.AddToPlaylistState(),onPickPlaylist={},
                            airPods=AirPodsUiBinding(state,{},{}))
                        else if(quick) {
                            AirPodsCapsule(AirPodsUiBinding(state,{},{}),Modifier.width(120.dp))
                            AirPodsQuickOverlay(state,true,{}, {}, {})
                        } else AirPodsSettingsContent(state,{})
                        FluidGlassModalHost(state=host,backdrop=backdrop)
                    }
                }
            }
        }
        java.io.File("build/airpods-full-integration/implementation/ui/screenshots").mkdirs()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/airpods-full-integration/implementation/ui/screenshots/${w}x${h}-f$font-$dark-$amoled-$quick-player$player.png")
    }
    private fun home(w:Int,h:Int,font:Float,friends:Boolean,inset:Int) {
        compose.setContent {
            val ground=rememberGlassBackdrop()
            val density=LocalDensity.current
            SquareTheme(darkTheme=true,amoled=false,systemBars=false) {
                CompositionLocalProvider(LocalGlassBackdrop provides ground,
                    LocalDensity provides Density(density.density,font),
                    dev.lelonio.square.ui.theme.LocalPageEndInset provides inset.dp,
                    LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
                    Box(Modifier.requiredSize(w.dp,h.dp).testTag("home-fixture")) {
                        dev.lelonio.square.ui.home.HomeScreen(
                            state=dev.lelonio.square.ui.MainViewModel.UiState.Ready("Fixture",emptyList()),
                            contentPadding=PaddingValues(0.dp),ground=ground,
                            onLogIn={},onRetry={},onLogOut={},onOpenPlaylist={},playlistOrder=emptyList(),
                            recent=emptyList(),onPlayRecent={_,_->},onPlayFeed={_,_,_->},
                            feed=dev.lelonio.square.ui.MainViewModel.FeedState(newReleases=List(8) { dev.lelonio.square.data.SearchItem("fixture:album:$it","Album $it","Fixture",null) }),onOpenItem={},onOpenSettings={},
                            friends=if(friends) listOf(dev.lelonio.square.data.FriendListen("fixture","Friend",null,"track","Song","Artist",null,"Album",0)) else emptyList(),
                            airPods=AirPodsUiBinding(uiFixture(),{},{}))
                    }
                }
            }
        }
        java.io.File("build/airpods-full-integration/implementation/ui/screenshots").mkdirs()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/airpods-full-integration/implementation/ui/screenshots/home-${w}x${h}-f$font-friends$friends-inset$inset.png")
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToIndex(5)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/airpods-full-integration/implementation/ui/screenshots/home-collapsed-${w}x${h}-f$font-friends$friends-inset$inset.png")
        val context=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithContentDescription(context.getString(dev.lelonio.square.R.string.airpods_feature_settings_accessibility)).assertDoesNotExist()
    }
    @Test fun player360Font2()=capture(360,640,2f,true,true,false,true)
    @Test fun player600()=capture(600,960,1f,false,false,false,true)
    @Test fun player840()=capture(840,600,1.5f,true,false,false,true)
    @Test fun player1280()=capture(1280,800,1f,true,false,false,true)
    @Test fun player720()=capture(720,360,1f,false,false,false,true)
    @Test fun home360Font2Friends()=home(360,640,2f,true,0)
    @Test fun home600Font15Friends()=home(600,960,1.5f,true,0)
    @Test fun home840WithPlayerInset()=home(840,600,1f,true,378)
    @Test fun home1280()=home(1280,800,1f,false,0)
    @Test fun home720Landscape()=home(720,360,1f,false,0)
    @Test fun viewport360x640Font1Theme0Full() = capture(360,640,1f,false,false,false)
    @Test fun viewport360x640Font1Theme0Quick() = capture(360,640,1f,false,false,true)
    @Test fun viewport360x640Font1Theme1Full() = capture(360,640,1f,true,false,false)
    @Test fun viewport360x640Font1Theme1Quick() = capture(360,640,1f,true,false,true)
    @Test fun viewport360x640Font1Theme2Full() = capture(360,640,1f,true,true,false)
    @Test fun viewport360x640Font1Theme2Quick() = capture(360,640,1f,true,true,true)
    @Test fun viewport360x640Font1_5Theme0Full() = capture(360,640,1.5f,false,false,false)
    @Test fun viewport360x640Font1_5Theme0Quick() = capture(360,640,1.5f,false,false,true)
    @Test fun viewport360x640Font1_5Theme1Full() = capture(360,640,1.5f,true,false,false)
    @Test fun viewport360x640Font1_5Theme1Quick() = capture(360,640,1.5f,true,false,true)
    @Test fun viewport360x640Font1_5Theme2Full() = capture(360,640,1.5f,true,true,false)
    @Test fun viewport360x640Font1_5Theme2Quick() = capture(360,640,1.5f,true,true,true)
    @Test fun viewport360x640Font2Theme0Full() = capture(360,640,2f,false,false,false)
    @Test fun viewport360x640Font2Theme0Quick() = capture(360,640,2f,false,false,true)
    @Test fun viewport360x640Font2Theme1Full() = capture(360,640,2f,true,false,false)
    @Test fun viewport360x640Font2Theme1Quick() = capture(360,640,2f,true,false,true)
    @Test fun viewport360x640Font2Theme2Full() = capture(360,640,2f,true,true,false)
    @Test fun viewport360x640Font2Theme2Quick() = capture(360,640,2f,true,true,true)
    @Test fun viewport600x960Font1Theme0Full() = capture(600,960,1f,false,false,false)
    @Test fun viewport600x960Font1Theme0Quick() = capture(600,960,1f,false,false,true)
    @Test fun viewport600x960Font1Theme1Full() = capture(600,960,1f,true,false,false)
    @Test fun viewport600x960Font1Theme1Quick() = capture(600,960,1f,true,false,true)
    @Test fun viewport600x960Font1Theme2Full() = capture(600,960,1f,true,true,false)
    @Test fun viewport600x960Font1Theme2Quick() = capture(600,960,1f,true,true,true)
    @Test fun viewport600x960Font1_5Theme0Full() = capture(600,960,1.5f,false,false,false)
    @Test fun viewport600x960Font1_5Theme0Quick() = capture(600,960,1.5f,false,false,true)
    @Test fun viewport600x960Font1_5Theme1Full() = capture(600,960,1.5f,true,false,false)
    @Test fun viewport600x960Font1_5Theme1Quick() = capture(600,960,1.5f,true,false,true)
    @Test fun viewport600x960Font1_5Theme2Full() = capture(600,960,1.5f,true,true,false)
    @Test fun viewport600x960Font1_5Theme2Quick() = capture(600,960,1.5f,true,true,true)
    @Test fun viewport600x960Font2Theme0Full() = capture(600,960,2f,false,false,false)
    @Test fun viewport600x960Font2Theme0Quick() = capture(600,960,2f,false,false,true)
    @Test fun viewport600x960Font2Theme1Full() = capture(600,960,2f,true,false,false)
    @Test fun viewport600x960Font2Theme1Quick() = capture(600,960,2f,true,false,true)
    @Test fun viewport600x960Font2Theme2Full() = capture(600,960,2f,true,true,false)
    @Test fun viewport600x960Font2Theme2Quick() = capture(600,960,2f,true,true,true)
    @Test fun viewport840x600Font1Theme0Full() = capture(840,600,1f,false,false,false)
    @Test fun viewport840x600Font1Theme0Quick() = capture(840,600,1f,false,false,true)
    @Test fun viewport840x600Font1Theme1Full() = capture(840,600,1f,true,false,false)
    @Test fun viewport840x600Font1Theme1Quick() = capture(840,600,1f,true,false,true)
    @Test fun viewport840x600Font1Theme2Full() = capture(840,600,1f,true,true,false)
    @Test fun viewport840x600Font1Theme2Quick() = capture(840,600,1f,true,true,true)
    @Test fun viewport840x600Font1_5Theme0Full() = capture(840,600,1.5f,false,false,false)
    @Test fun viewport840x600Font1_5Theme0Quick() = capture(840,600,1.5f,false,false,true)
    @Test fun viewport840x600Font1_5Theme1Full() = capture(840,600,1.5f,true,false,false)
    @Test fun viewport840x600Font1_5Theme1Quick() = capture(840,600,1.5f,true,false,true)
    @Test fun viewport840x600Font1_5Theme2Full() = capture(840,600,1.5f,true,true,false)
    @Test fun viewport840x600Font1_5Theme2Quick() = capture(840,600,1.5f,true,true,true)
    @Test fun viewport840x600Font2Theme0Full() = capture(840,600,2f,false,false,false)
    @Test fun viewport840x600Font2Theme0Quick() = capture(840,600,2f,false,false,true)
    @Test fun viewport840x600Font2Theme1Full() = capture(840,600,2f,true,false,false)
    @Test fun viewport840x600Font2Theme1Quick() = capture(840,600,2f,true,false,true)
    @Test fun viewport840x600Font2Theme2Full() = capture(840,600,2f,true,true,false)
    @Test fun viewport840x600Font2Theme2Quick() = capture(840,600,2f,true,true,true)
    @Test fun viewport1280x800Font1Theme0Full() = capture(1280,800,1f,false,false,false)
    @Test fun viewport1280x800Font1Theme0Quick() = capture(1280,800,1f,false,false,true)
    @Test fun viewport1280x800Font1Theme1Full() = capture(1280,800,1f,true,false,false)
    @Test fun viewport1280x800Font1Theme1Quick() = capture(1280,800,1f,true,false,true)
    @Test fun viewport1280x800Font1Theme2Full() = capture(1280,800,1f,true,true,false)
    @Test fun viewport1280x800Font1Theme2Quick() = capture(1280,800,1f,true,true,true)
    @Test fun viewport1280x800Font1_5Theme0Full() = capture(1280,800,1.5f,false,false,false)
    @Test fun viewport1280x800Font1_5Theme0Quick() = capture(1280,800,1.5f,false,false,true)
    @Test fun viewport1280x800Font1_5Theme1Full() = capture(1280,800,1.5f,true,false,false)
    @Test fun viewport1280x800Font1_5Theme1Quick() = capture(1280,800,1.5f,true,false,true)
    @Test fun viewport1280x800Font1_5Theme2Full() = capture(1280,800,1.5f,true,true,false)
    @Test fun viewport1280x800Font1_5Theme2Quick() = capture(1280,800,1.5f,true,true,true)
    @Test fun viewport1280x800Font2Theme0Full() = capture(1280,800,2f,false,false,false)
    @Test fun viewport1280x800Font2Theme0Quick() = capture(1280,800,2f,false,false,true)
    @Test fun viewport1280x800Font2Theme1Full() = capture(1280,800,2f,true,false,false)
    @Test fun viewport1280x800Font2Theme1Quick() = capture(1280,800,2f,true,false,true)
    @Test fun viewport1280x800Font2Theme2Full() = capture(1280,800,2f,true,true,false)
    @Test fun viewport1280x800Font2Theme2Quick() = capture(1280,800,2f,true,true,true)
    @Test fun viewport720x360Font1Theme0Full() = capture(720,360,1f,false,false,false)
    @Test fun viewport720x360Font1Theme0Quick() = capture(720,360,1f,false,false,true)
    @Test fun viewport720x360Font1Theme1Full() = capture(720,360,1f,true,false,false)
    @Test fun viewport720x360Font1Theme1Quick() = capture(720,360,1f,true,false,true)
    @Test fun viewport720x360Font1Theme2Full() = capture(720,360,1f,true,true,false)
    @Test fun viewport720x360Font1Theme2Quick() = capture(720,360,1f,true,true,true)
    @Test fun viewport720x360Font1_5Theme0Full() = capture(720,360,1.5f,false,false,false)
    @Test fun viewport720x360Font1_5Theme0Quick() = capture(720,360,1.5f,false,false,true)
    @Test fun viewport720x360Font1_5Theme1Full() = capture(720,360,1.5f,true,false,false)
    @Test fun viewport720x360Font1_5Theme1Quick() = capture(720,360,1.5f,true,false,true)
    @Test fun viewport720x360Font1_5Theme2Full() = capture(720,360,1.5f,true,true,false)
    @Test fun viewport720x360Font1_5Theme2Quick() = capture(720,360,1.5f,true,true,true)
    @Test fun viewport720x360Font2Theme0Full() = capture(720,360,2f,false,false,false)
    @Test fun viewport720x360Font2Theme0Quick() = capture(720,360,2f,false,false,true)
    @Test fun viewport720x360Font2Theme1Full() = capture(720,360,2f,true,false,false)
    @Test fun viewport720x360Font2Theme1Quick() = capture(720,360,2f,true,false,true)
    @Test fun viewport720x360Font2Theme2Full() = capture(720,360,2f,true,true,false)
    @Test fun viewport720x360Font2Theme2Quick() = capture(720,360,2f,true,true,true)
}
