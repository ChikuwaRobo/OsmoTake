package jp.hiroyuki.osmonanogc

import org.junit.Assert.*
import org.junit.Test

class GcMessageParserTest {
    @Test fun runningStarts() = assertEquals(true,
        GcMessageParser.runningOrNull("""{"matchState":{"gameState":{"type":"RUNNING"}}}"""))
    @Test fun anyOtherValidStateStops() = assertEquals(false,
        GcMessageParser.runningOrNull("""{"matchState":{"gameState":{"type":"READY"}}}"""))
    @Test fun nullMatchStatePreserves() = assertNull(GcMessageParser.runningOrNull("""{"matchState":null}"""))
    @Test fun missingMatchStatePreserves() = assertNull(GcMessageParser.runningOrNull("""{"gcState":null}"""))
    @Test fun malformedPreserves() = assertNull(GcMessageParser.runningOrNull("not-json"))
}
