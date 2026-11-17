package dev.lelonio.square.ui.airpods

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.*
import androidx.test.core.app.ApplicationProvider
import dev.lelonio.square.R
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*
import dev.lelonio.square.ui.theme.SquareTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35], application=Application::class)
class AirPodsSelectorAccessibilityTest(private val language:String, private val font:Float, private val quick:Boolean) {
 companion object {
  @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name="{0}-font{1}-quick{2}")
  fun cases() = listOf("it", "de").flatMap { language -> listOf(1f,1.5f,2f).flatMap { font ->
   listOf(false,true).map { quick -> arrayOf<Any>(language,font,quick) }
  }}
 }
 @get:Rule val compose=createComposeRule()
 private val writes=mutableListOf<AirPodsUiAction>()
 private val prefix get()=if(quick) "airpods-quick-MODE" else "page"
 private fun mount(ready:Boolean=true) {
  RuntimeEnvironment.setQualifiers("$language-r${if(language=="it") "IT" else "DE"}-w412dp-h1200dp-mdpi")
  compose.setContent { SquareTheme(darkTheme=false,amoled=false,systemBars=false) {
   CompositionLocalProvider(LocalDensity provides Density(1f,font)) {
    Column(Modifier.width(360.dp)) {
     val state=uiFixture().copy(profileConnected=ready)
     if(quick) AirPodsQuickContent(state,writes::add) else ModeControl(state,writes::add,prefix)
    }
   }
  }}
 }
 private fun radios()=compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.RadioButton))
 @Test fun oneNamedRadioPerModeWithSelectionAndAction() {
  mount()
  radios().assertCountEquals(4)
  compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.RadioButton) and isSelected()).assertCountEquals(1)
  val context=ApplicationProvider.getApplicationContext<Context>()
  val names=mapOf(1 to R.string.airpods_feature_mode_off,2 to R.string.airpods_feature_mode_anc,
   3 to R.string.airpods_feature_mode_transparency,4 to R.string.airpods_feature_mode_adaptive)
  names.forEach { (wire,res) ->
   val node=compose.onNodeWithTag("$prefix-$wire")
   node.assertTextEquals(context.getString(res)).assertIsEnabled()
   if(wire==2) node.assertIsSelected() else { node.assertIsNotSelected().assertHasClickAction().performClick() }
  }
  assertEquals(3,writes.size)
  if(font>=1.5f) {
   writes.clear()
   compose.onNodeWithTag("$prefix-legend-1").performTouchInput { click() }
   assertEquals(1,writes.size)
   assertEquals(CommandProperty.MODE,(writes.single() as AirPodsUiAction.Write).property)
   compose.onNodeWithTag("$prefix-1").performTouchInput { click() }
   assertEquals(writes.first(),writes.last())
  }
 }
 @Test fun unavailableChoicesRemainNamedRadiosWithoutActions() {
  mount(false)
  radios().assertCountEquals(4)
  compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.RadioButton) and isSelected()).assertCountEquals(0)
  listOf(1,2,3,4).forEach { wire ->
   compose.onNodeWithTag("$prefix-$wire").assertIsNotEnabled().assertIsNotSelected()
    .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
  }

  assertTrue(writes.isEmpty())
 }
}

