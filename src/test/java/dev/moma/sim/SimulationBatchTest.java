package dev.moma.sim;

import dev.moma.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SimulationBatchTest {
    @Test void oneThirtyTwoAndFiveHundredTickBatchesHaveIdenticalFullResults() {
        for(TraitLoadout traits:List.of(TraitLoadout.EMPTY,new TraitLoadout(List.of("round_10000","miracle_100","mythic_1000","round_350","session_1000")))) {
            String expected=EndlessSimulatorMain.run(28000000,300,CampaignRules.standard(),traits,1);
            for(int speed:new int[]{32,500})assertEquals(expected,EndlessSimulatorMain.run(28000000,300,CampaignRules.standard(),traits,speed));
        }
    }
}
