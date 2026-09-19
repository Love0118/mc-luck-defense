package dev.moma.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HealthCurveTest {
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
