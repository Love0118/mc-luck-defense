package dev.moma.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AttackGeometryTest {
    @Test void sectorIncludesInteriorButExcludesOutsideAngleAndRangeInEveryDirection() {
        Point origin=new Point(7,-2);double range=4.5;
        assertEquals(Math.PI/3,AttackGeometry.CLEAVE_HALF_ANGLE_RADIANS,1e-15);
        for(double direction:new double[]{0,.3,Math.PI/2,Math.PI,-2.1}) {
            Point primary=point(origin,2,direction);
            assertTrue(AttackGeometry.inCleave(origin,primary,origin,range));
            for(double side:new double[]{-1,1}) {
                assertTrue(AttackGeometry.inCleave(origin,primary,point(origin,range*.999999,direction+side*(Math.PI/3-1e-7)),range));
                assertFalse(AttackGeometry.inCleave(origin,primary,point(origin,range*.99,direction+side*(Math.PI/3+1e-7)),range));
            }
            assertFalse(AttackGeometry.inCleave(origin,primary,point(origin,range+1e-7,direction),range));
            assertFalse(AttackGeometry.inCleave(origin,primary,point(origin,1,direction+Math.PI),range));
            assertTrue(AttackGeometry.inCleave(origin,origin,point(origin,1,direction+Math.PI),range));
        }
    }
    private Point point(Point origin,double r,double angle){return new Point(origin.x()+r*Math.cos(angle),origin.z()+r*Math.sin(angle));}
}
