package dev.lelonio.square.ui.airpods

import org.junit.Assert.*
import org.junit.Test

class AirPodsNoiseLabelTest {
    @Test fun italianWrapsBetweenWordsOnly() {
        assertEquals("Cancellazione\ndel rumore", noiseControlLabelLines("Cancellazione del rumore", 13f) { it.length.toFloat() })
    }
    @Test fun germanCompoundIsNeverSplitEvenWhenTooWide() {
        assertEquals("Ger\u00e4uschunterdr\u00fcckung", noiseControlLabelLines("Ger\u00e4uschunterdr\u00fcckung", 5f) { it.length.toFloat() })
    }
    @Test fun englishWrapsAtSpaceAndShortLabelsStayWhole() {
        assertEquals("Noise\nCancellation", noiseControlLabelLines("Noise Cancellation", 12f) { it.length.toFloat() })
        assertEquals("Off", noiseControlLabelLines("Off", 12f) { it.length.toFloat() })
    }
}
