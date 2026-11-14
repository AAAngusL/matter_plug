package app.matterplug.cellular.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class CellularCodecTest {
    private val run = mapOf(
        Layer.Sim to LayerResult.Pass("ready"),
        Layer.Registration to LayerResult.Fail("airplane mode, with, commas\nand a newline"),
        Layer.Signal to LayerResult.Skipped(Layer.Registration),
        Layer.Sms to LayerResult.NotDue,
    )

    @Test fun everyKindOfResultSurvivesARoundTrip() = assertEquals(run, CellularCodec.decode(CellularCodec.encode(run)))

    @Test fun nothingStoredIsNoResults() {
        assertEquals(emptyMap<Layer, LayerResult>(), CellularCodec.decode(null))
        assertEquals(emptyMap<Layer, LayerResult>(), CellularCodec.decode(""))
    }

    @Test fun aDamagedLineIsDroppedAndTheRestKept() {
        val text = CellularCodec.encode(mapOf(Layer.Sim to LayerResult.Pass("ready"))) + "\nL,nonsense\nL,signal,x,y"
        assertEquals(mapOf(Layer.Sim to LayerResult.Pass("ready")), CellularCodec.decode(text))
    }
}
