package dev.moma.paper;

import dev.moma.core.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DamageDisplayTest {
    @Test void everyGradeDisplaysPositiveDamageWithoutRoundingTinyValuesToZero() {
        for(UnitType type:UnitType.values())for(Rarity rarity:Rarity.values()) {
            double damage=type.profile().at(rarity).damage();
            String text=Ui.damage(damage);
            assertTrue(Double.parseDouble(text)>0,type+" "+rarity);
            assertEquals(damage,Double.parseDouble(text),(damage<.1?damage*.0005:.05)+Math.ulp(damage)*2);
        }
        assertEquals("1.0",Ui.damage(1));assertEquals("0.0",Ui.damage(0));
        assertEquals("0.0000001467",Ui.damage(1.467e-7));
    }
}
