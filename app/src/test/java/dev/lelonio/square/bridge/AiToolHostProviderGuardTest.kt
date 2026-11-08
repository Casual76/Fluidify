package dev.lelonio.square.bridge

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Bundle
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ai.bridge.AiToolHostProvider
import dev.antigravity.fluidengine.ai.bridge.BridgeProtocol
import dev.antigravity.fluidengine.ai.bridge.RemoteCall
import dev.antigravity.fluidengine.ai.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowBinder

/**
 * Il cancello dell'ospite (3.0.0): `AiToolHostProvider.call` risponde solo a chi ha la firma
 * dell'app, e lo dice con una `SecurityException` — che il cliente legge come `NO_PERMISSION` — prima
 * di toccare qualunque metodo. Sta nel sample e non in engine-ai-bridge, come tutti i test che hanno
 * bisogno di Android: un'app che include il modulo non deve scaricare Robolectric per eseguire i
 * suoi test.
 *
 * Il `PackageManager` di Robolectric risponde "firme uguali" a qualunque coppia di uid, quindi il
 * confronto vero lo fa [Host.comparison]: qui si prova la regola (solo MATCH fa fidare, e il
 * confronto e' fra il chiamante e noi), non il `PackageManager`.
 */
@org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [35])
@RunWith(RobolectricTestRunner::class)
class AiToolHostProviderGuardTest {

  /** Un ospite vuoto: nessun tool, pronto; la fiducia e' quella di serie, o quella imposta dal test. */
  class Host : AiToolHostProvider<Unit>() {
    var effects = 0
    private val group = object : dev.antigravity.fluidengine.ai.tools.AiToolGroup {
      override val id = "test"
      override val statusKey = "test"
      override val hint = "test"
    }
    override fun registry() = ToolRegistry<Unit>(listOf(object : dev.antigravity.fluidengine.ai.tools.AiTool<Unit> {
      override val name = "write"
      override val group = this@Host.group
      override val description = "write"
      override val needsConfirmation = true
      override val parameters = dev.antigravity.fluidengine.ai.tools.Schema.obj(mapOf("value" to dev.antigravity.fluidengine.ai.tools.Schema.str("value")), listOf("value"))
      override suspend fun run(args: kotlinx.serialization.json.JsonObject, ctx: Unit): dev.antigravity.fluidengine.ai.tools.ToolOutput {
        effects++
        return dev.antigravity.fluidengine.ai.tools.ToolOutput("done")
      }
    }), listOf(group))
    override suspend fun context(call: RemoteCall) = Unit
    override fun domain() = "prova"
    override fun appLabel() = "Prova"

    /** Se presente, decide chi e' fidato al posto della regola di serie. */
    var trust: ((Int) -> Boolean)? = null

    /** Se presente, risponde al posto del `PackageManager` nel confronto delle firme. */
    var comparison: ((Int, Int) -> Int)? = null

    val trustAsked = mutableListOf<Int>()
    val compared = mutableListOf<Pair<Int, Int>>()

    override fun isCallerTrusted(callingUid: Int): Boolean {
      trustAsked += callingUid
      return trust?.invoke(callingUid) ?: super.isCallerTrusted(callingUid)
    }

    override fun compareSignatures(callingUid: Int, ownUid: Int): Int {
      compared += callingUid to ownUid
      return comparison?.invoke(callingUid, ownUid) ?: super.compareSignatures(callingUid, ownUid)
    }
  }

  private fun host(): Host {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val info = ProviderInfo().apply {
      authority = "${context.packageName}.ai.tools"
      name = Host::class.java.name
      packageName = context.packageName
      exported = true
    }
    return Robolectric.buildContentProvider(Host::class.java).create(info).get()
  }

  private fun ready(provider: Host): String =
    provider.call(BridgeProtocol.METHOD_READY, null, Bundle()).getString(BridgeProtocol.KEY_JSON)!!

  private fun denied(provider: Host, method: String = BridgeProtocol.METHOD_READY): Throwable? =
    runCatching { provider.call(method, null, Bundle()) }.exceptionOrNull()

  @Test
  fun `una chiamata dello stesso processo passa`() {
    val provider = host()
    assertTrue(ready(provider).contains("\"ready\":true"))
    assertEquals("il cancello guarda il chiamante di Binder", listOf(Process.myUid()), provider.trustAsked)
  }

  @Test
  fun `un chiamante con un'altra firma riceve una SecurityException, su ogni metodo`() {
    val provider = host()
    provider.trust = { false }
    ShadowBinder.setCallingUid(Process.myUid() + 1)
    listOf(
      BridgeProtocol.METHOD_NEGOTIATE,
      BridgeProtocol.METHOD_READY,
      BridgeProtocol.METHOD_CATALOG,
      BridgeProtocol.METHOD_RUN,
      BridgeProtocol.METHOD_DESCRIBE,
      BridgeProtocol.METHOD_STATUS,
      BridgeProtocol.METHOD_CANCEL,
      "metodo-che-non-esiste",
    ).forEach { method ->
      assertThrows("$method deve essere negato", SecurityException::class.java) { provider.call(method, null, Bundle()) }
    }
    assertTrue("il cancello ha visto l'uid del chiamante", provider.trustAsked.all { it == Process.myUid() + 1 })
  }

  @Test
  fun `un chiamante fidato passa, e il cancello sceglie per uid`() {
    val provider = host()
    provider.trust = { uid -> uid == 31337 }
    ShadowBinder.setCallingUid(31337)
    assertTrue(ready(provider).contains("\"ready\":true"))
    ShadowBinder.setCallingUid(31338)
    assertThrows(SecurityException::class.java) { provider.call(BridgeProtocol.METHOD_READY, null, Bundle()) }
  }

  @Test
  fun `la regola di serie confronta il chiamante con noi, e si fida solo di SIGNATURE_MATCH`() {
    val provider = host()
    val caller = 40_001
    ShadowBinder.setCallingUid(caller)

    provider.comparison = { _, _ -> PackageManager.SIGNATURE_MATCH }
    assertTrue(ready(provider).contains("\"ready\":true"))
    assertEquals("prima il chiamante, poi noi", listOf(caller to Process.myUid()), provider.compared)

    listOf(
      PackageManager.SIGNATURE_NO_MATCH,
      PackageManager.SIGNATURE_UNKNOWN_PACKAGE,
      PackageManager.SIGNATURE_FIRST_NOT_SIGNED,
      PackageManager.SIGNATURE_SECOND_NOT_SIGNED,
      PackageManager.SIGNATURE_NEITHER_SIGNED,
    ).forEach { answer ->
      provider.comparison = { _, _ -> answer }
      assertTrue("$answer non e' una firma uguale", denied(provider) is SecurityException)
    }
  }

  @Test
  fun `senza contesto non si fida di nessuno`() {
    // Un provider non ancora attaccato (nessun contesto) non ha un PackageManager con cui confrontare.
    assertFalse(Host().isCallerTrusted(Process.myUid()))
  }
  @Test fun `malformed args and missing confirmation have no effects`() {
    val provider = host()
    provider.trust = { true }
    fun run(json: String) = provider.call(BridgeProtocol.METHOD_RUN, null, Bundle().apply { putString(BridgeProtocol.KEY_JSON, json) })
    run("""{"name":"write","args":{"value":"ok"},"confirmed":false,"requestId":"parent"}""")
    assertEquals(0, provider.effects)
    run("""{"name":"write","args":{"value":[]},"confirmed":true,"requestId":"parent"}""")
    assertEquals(0, provider.effects)
    run("""{"name":"write","args":"broken","confirmed":true,"requestId":"parent"}""")
    assertEquals(0, provider.effects)
    run("""{"name":"write","args":{"value":"ok"},"confirmed":true}""")
    assertEquals(0, provider.effects)
    run("""{"name":"write","args":{"value":"ok"},"confirmed":true,"requestId":"parent"}""")
    assertEquals(1, provider.effects)
  }
}
