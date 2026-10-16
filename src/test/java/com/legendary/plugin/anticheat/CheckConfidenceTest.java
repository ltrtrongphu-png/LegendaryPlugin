package com.legendary.plugin.anticheat;

import com.legendary.plugin.modules.anticheat.Check;
import com.legendary.plugin.modules.anticheat.PerfProfiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CheckConfidenceTest {

    @Test
    void classifiesReliableAndStatisticalChecksConservatively() {
        assertEquals(Check.Confidence.HIGH, Check.confidenceFor("invalidpacket"));
        assertEquals(Check.Confidence.HIGH, Check.confidenceFor("impossiblehit"));
        assertEquals(Check.Confidence.LOW, Check.confidenceFor("mccanalyze"));
        assertEquals(Check.Confidence.LOW, Check.confidenceFor("xray"));
        assertEquals(Check.Confidence.MEDIUM, Check.confidenceFor("movement"));
    }

    @Test
    void profilerAccumulatesPerCheckMeasurements() {
        PerfProfiler profiler = new PerfProfiler();
        profiler.record("movement", 100);
        profiler.record("movement", 300);

        var movement = profiler.snapshots().get(0);
        assertEquals("movement", movement.checkId());
        assertEquals(2, movement.calls());
        assertEquals(200, movement.averageNanos());
        assertEquals(300, movement.maxNanos());
    }
}
