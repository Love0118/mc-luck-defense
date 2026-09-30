package dev.moma.core;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SmoothHealthGrowthTest {
    private Properties settings()throws Exception {
        var p=new Properties();try(var in=getClass().getResourceAsStream("/campaign.properties")){p.load(in);}return p;
    }
    @Test void generatedCurveMatchesOneFormulaAtEveryLaterIntegerRound()throws Exception {
        var p=settings();var model=SmoothHealthGrowth.from(p);var prefix=HealthCurve.parse(p.getProperty("health-curve"));
        assertEquals(200,prefix.anchors().getLast().round());
        var rules=CampaignRules.fromProperties(p);var start=prefix.anchors().getLast();
        double previous=start.health(),previousGrowth=Double.NaN;
        for(int round=start.round()+1;round<=SmoothHealthGrowth.SAMPLED_ROUNDS;round++) {
            double h=rules.healthCurve().at(round);
            assertEquals(model.health(round,start,rules.endlessHealthPower(),rules.endlessPressureBend()),h,h*1e-12);
            assertTrue(h>previous,"Pressure fell at "+round);
            double growth=Math.log(h/previous);
            if(Double.isFinite(previousGrowth))assertTrue(Math.abs(growth-previousGrowth)<.0005,"Growth step at "+round);
            previous=h;previousGrowth=growth;
        }
    }
    @Test void measuredDamageLossFadesContinuouslyRatherThanEndingAtACutoff()throws Exception {
        var model=SmoothHealthGrowth.from(settings());double previous=0;
        for(int round=200;round<=4000;round++) {
            double retention=model.damageRetention(round);
            assertTrue(retention>=previous && retention<=1);previous=retention;
        }
        assertTrue(model.damageRetention(1000)<.99);
        assertTrue(model.damageRetention(1500)>model.damageRetention(1000));
        assertTrue(model.damageRetention(2000)>.99);
        assertTrue(model.damageRetention(3000)>.9999);
    }
    @Test void compiledCurveRetainsTheExistingSerializableRepresentation()throws Exception {
        var rules=CampaignRules.fromProperties(settings());
        var bytes=new java.io.ByteArrayOutputStream();
        try(var out=new java.io.ObjectOutputStream(bytes)){out.writeObject(rules);}
        CampaignRules copy;
        try(var in=new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))){copy=(CampaignRules)in.readObject();}
        assertEquals(rules,copy);
        assertEquals(1,HealthCurve.class.getRecordComponents().length);
        assertEquals("anchors",HealthCurve.class.getRecordComponents()[0].getName());
    }
    @Test void invalidModelsFailBeforeCreatingACampaign()throws Exception {
        for(String value:new String[]{"NaN","-0.1","1.01"}) {
            var p=settings();p.setProperty("smooth-growth.loss-amplitude",value);
            assertThrows(IllegalArgumentException.class,()->CampaignRules.fromProperties(p));
        }
        var missing=settings();missing.remove("smooth-growth.loss-scale");
        assertThrows(IllegalArgumentException.class,()->CampaignRules.fromProperties(missing));
        var slow=settings();slow.setProperty("smooth-growth.loss-scale","100000");
        assertThrows(IllegalArgumentException.class,()->CampaignRules.fromProperties(slow));
        var legacy=settings();legacy.remove("smooth-growth.enabled");
        assertEquals(HealthCurve.parse(legacy.getProperty("health-curve")),CampaignRules.fromProperties(legacy).healthCurve());
    }
}
