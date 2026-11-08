package dev.lelonio.square.ui.airpods
import dev.lelonio.square.airpods.*
import dev.lelonio.square.airpods.protocol.*
import dev.lelonio.square.airpods.runtime.storage.*
import org.junit.Assert.*
import org.junit.Test

internal fun uiFixture(): AirPodsUiState {
    val identity = AirPodsIdentity("A3063","Apple Inc.","fixture")
    return AirPodsUiState(domain = AirPodsState(generation=7,stage=AacpStage.SUBSCRIBED,
        identityReceived=Received(identity,1000,7),mode=Received(ListeningMode.ANC,1000,7),
        properties=mapOf(CommandProperty.EQ to Received(PropertyValue.Eq(false,40,50,60),1000,7)),
        batteries=mapOf(4 to Received(BatteryValue(80,2),1000,7))),
        pair=PairSettings(verification=PairVerification("A3063","fixture",0)),enabled=true,
        profileConnected=true,bluetoothGranted=true,productGatePassed=true,conformanceMatches=true,nowMs=1001)
}
class AirPodsUiModelTest {
    @Test fun gestureChoicesMatchEachBackendPath() {
        listOf(GestureSide.LEFT, GestureSide.RIGHT).forEach {
            assertEquals(listOf(GestureAction.NOISE_CONTROL, GestureAction.DIGITAL_ASSISTANT), gestureOptions(it))
        }
        listOf(GestureSide.NOD, GestureSide.SHAKE).forEach {
            assertEquals(listOf(GestureAction.NONE, GestureAction.PLAY_PAUSE, GestureAction.NEXT, GestureAction.PREVIOUS), gestureOptions(it))
        }
    }
    @Test fun uiFreshBatteryRequiresCurrentConnectionAndStrictAge() {
        val s = uiFixture()
        assertEquals(80, s.freshBattery(4)?.percent)
        assertNull(s.freshBattery(2)); assertNull(s.freshBattery(8))
        assertNull(s.copy(profileConnected=false).freshBattery(4))
        assertNull(s.copy(nowMs=301000).freshBattery(4))
        assertEquals(80, s.copy(nowMs=300999).freshBattery(4)?.percent)
        assertNull(s.copy(domain=s.domain.copy(generation=8)).freshBattery(4))
        assertNull(s.copy(nowMs=999).freshBattery(4))
    }

    @Test fun defaultsAreInertAndDoNotSelectCandidate() {
        val s=AirPodsUiState(candidates=listOf(PairCandidate("one","Same name","1"),PairCandidate("two","Same name","2")))
        assertFalse(s.homeVisible);assertFalse(s.playerVisible);assertNull(s.selectedCandidateId);assertNull(s.quickTarget)
    }
    @Test fun allVisibilityGatesAreRequired() {
        val s=uiFixture();assertTrue(s.homeVisible)
        listOf(s.copy(enabled=false),s.copy(suppressed=true),s.copy(profileConnected=false),s.copy(bluetoothGranted=false),s.copy(productGatePassed=false),s.copy(conformanceMatches=false),s.copy(pair=PairSettings())).forEach {assertFalse(it.homeVisible)}
        assertTrue(s.copy(profileConnected=false).playerVisible)
    }
    @Test fun socketErrorKeepsAudioCapsuleButDisablesCommands() {
        val s=uiFixture().let {it.copy(domain=it.domain.copy(stage=AacpStage.ERROR))}
        assertTrue(s.homeVisible);assertFalse(s.controlsReady);assertNull(s.quickTarget)
    }
    @Test fun generationsAndPendingCannotBecomeReceivedOrRepeatWrites() {
        val s=uiFixture();assertEquals(ListeningMode.ADAPTIVE,s.quickTarget)
        assertNull(s.copy(domain=s.domain.copy(mode=Received(ListeningMode.ANC,1000,6))).currentMode)
        val pending=s.copy(domain=s.domain.copy(pending=mapOf(CommandProperty.MODE to PendingCommand(4,1001,1,generation=7))))
        assertNull(pending.quickTarget);assertEquals(ListeningMode.ANC,pending.currentMode)
        assertFalse(pending.writeAllowed(CommandProperty.MODE,PropertyValue.Number(4)))
    }
    @Test fun quickPreferenceUsesModelCandidatesWithoutInventingRX() {
        val s=uiFixture()
        val pro1=AirPodsIdentity("A2084","Apple Inc.","fixture")
        val older=s.copy(domain=s.domain.copy(identityReceived=Received(pro1,1000,7),mode=null))
        assertEquals(ListeningMode.TRANSPARENCY,older.preferredMode)
        assertNull(older.currentMode)
        val unknown=s.copy(domain=s.domain.copy(identityReceived=Received(AirPodsIdentity("unknown","Apple Inc.","fixture"),1000,7)))
        assertNull(unknown.preferredMode);assertNull(unknown.quickTarget)
    }
    @Test fun batteryBoundaryAndDisconnectionDoNotInventLiveValues() {
        val d=uiFixture().domain
        assertEquals(80,d.liveBattery(4,300999)?.percent)
        assertNull(d.liveBattery(4,301000));assertNull(d.liveBattery(8,1001))
        assertNull(d.copy(stage=AacpStage.CLOSED).liveBattery(4,1001))
        assertNull(d.copy(generation=8).liveBattery(4,1001))
    }
    @Test fun conformanceAndPreferenceMappingIsOfflineAndDefaultOff() {
        val address=PairAddress("00:11:22:33:44:55")
        val data=AirPodsRuntimeData(selectedPair=address,pairs=mapOf(address to uiFixture().pair))
        val s=mapAirPodsUiState(uiFixture().domain,data,true,true,false,"different",1001)
        assertFalse(s.enabled);assertFalse(s.verified);assertFalse(s.pair.host.otherAppsEnabled)
        assertTrue(s.pair.headPacketAlternate)
    }
    @Test fun registryHasAll35TitlesAndMasksRequireTwoModes() {
        assertEquals(35,AirPodsFeatures.includedIds.size)
        assertEquals(AirPodsFeatures.includedIds,featureTitles.keys)
        val cycle=AirPodsProperties.spec(CommandProperty.LISTENING_MODE_CONFIGS)!!
        assertFalse(cycle.accepts(1));assertTrue(cycle.accepts(3))
        assertEquals(listOf(25),AirPodsProperties.spec(CommandProperty.AUTO_ANC_STRENGTH)!!.encode(75))
    }
    @Test fun headerFitReservesMarkAvatarFriendsAndHidesCollapsed() {
        assertTrue(airPodsHeaderFits(600f,180f,100f,120f,0f))
        assertFalse(airPodsHeaderFits(360f,180f,100f,120f,0f))
        assertFalse(airPodsHeaderFits(600f,180f,100f,120f,1f))
    }
}
