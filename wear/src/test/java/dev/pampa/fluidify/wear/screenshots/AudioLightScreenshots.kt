package dev.pampa.fluidify.wear.screenshots

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.lelonio.square.playback.AudioLightFrame
import dev.pampa.fluidify.wear.link.ArtStore
import dev.pampa.fluidify.wear.ui.player.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.annotation.*

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[36],qualifiers=Watch44)
class AudioLightScreenshots {
    @get:Rule val compose=createComposeRule()
    private fun capture(name:String,energy:Float=.65f,bass:Float=.8f,immersive:Boolean=false) {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        installSynchronousImageLoader(context)
        val art=ArtStore(context);storeSampleCover(art)
        compose.mainClock.autoAdvance=false
        compose.setContent {
            CompositionLocalProvider(LocalWatchAudioLightPreview provides AudioLightFrame(energy=energy,bass=bass)) {
                WatchFrame {
                    if(immersive) ImmersiveScreen(FakeControls(sampleSnapshot()),art)
                    else PlayerScreen(FakeControls(sampleSnapshot()),art,onQueue={},onOutput={},onEssentials={})
                }
            }
        }
        compose.mainClock.advanceTimeBy(1200)
        compose.onRoot().captureRoboImage("screenshots/audio_light_$name.png")
    }
    @Test fun watch240()=capture("watch240")
    @Test @Config(qualifiers=Watch40) fun watch216()=capture("watch216")
    @Test @Config(qualifiers=WatchSmall) fun watch192()=capture("watch192")
    @Test fun quiet()=capture("quiet",.1f,.05f)
    @Test fun bassImpulse()=capture("bass",1f,1f)
    @Test fun immersive()=capture("immersive",immersive=true)
}
