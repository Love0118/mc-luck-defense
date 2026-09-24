package dev.moma.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HealthCurveTest {
    @Test void standardCurveSpansTheSimulationAndHealthDoesNotRepeatWithTheField() {
        var rules=CampaignRules.standard();
        assertTrue(rules.healthCurve().anchors().getLast().round()>=10000);
        for(int round=101;round<=10000;round+=100) {
            var previous=WaveSchedule.create(round-100,rules).entries();
            var current=WaveSchedule.create(round,rules).entries();
            assertEquals(previous.size(),current.size());
            for(int i=0;i<current.size();i++) {
                assertTrue(Double.isFinite(current.get(i).enemy().health()));
                assertTrue(current.get(i).enemy().health()>previous.get(i).enemy().health());
                assertEquals(previous.get(i).enemy().type(),current.get(i).enemy().type());
            }
        }
    }
    @org.junit.jupiter.api.Test void endlessAnchorsInterpolateAndRejectOutOfRange() {
        var curve=HealthCurve.parse("1:60,100:250000,300:1500000,500:10000000,1000:25000000,2000:80000000");
        assertEquals(80000000,curve.at(2000));
        assertEquals(Math.sqrt(1500000d*10000000),curve.at(400),1e-6);
        assertThrows(IllegalArgumentException.class,()->curve.at(2001));
        var rules=CampaignRules.standard().withHealthCurve(curve);
        assertTrue(WaveSchedule.create(2100,rules).entries().stream().allMatch(e->Double.isFinite(e.enemy().health())));
    }
    @Test void anchorsAreExactAndInterpolationIsMonotonic() {
        HealthCurve curve = HealthCurve.parse("1:100,30:1000,50:2000,70:10000,90:20000,100:20000");
        for (var anchor : curve.anchors()) assertEquals(anchor.health(), curve.at(anchor.round()), 1e-9);
        for (int round = 2; round <= 100; round++) assertTrue(curve.at(round) >= curve.at(round - 1));
        assertEquals(curve, HealthCurve.parse(curve.specification()));
        assertEquals(Math.sqrt(1000 * 2000), curve.at(40), 1e-9);
    }
    @Test void rejectsMissingEndpointsNonFiniteDecreasingOrDuplicateAnchors() {
        for (String invalid : new String[]{"30:100,100:200", "1:100,90:200", "1:100,100:NaN", "1:100,50:90,100:200", "1:100,1:110,100:200", "1:0,100:200"})
            assertThrows(IllegalArgumentException.class, () -> HealthCurve.parse(invalid));
        assertThrows(IllegalArgumentException.class, () -> HealthCurve.parse("1:10,100:100").at(101));
    }
}
