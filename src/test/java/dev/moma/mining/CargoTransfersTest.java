package dev.moma.mining;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CargoTransfersTest {
    @TempDir Path directory;
    @Test void dropsDebitImmediatelyAndPartialPickupSurvivesReopeningWithoutDuplication()throws Exception{
        Path file=directory.resolve("mining.db");UUID owner=UUID.randomUUID(),recipient=UUID.randomUUID(),claim=UUID.randomUUID();
        try(var store=new MiningStore(file);var transfers=new CargoTransfers(file,store)){
            store.save(owner,MiningStore.Profile.fresh().credit(MiningRules.Ore.DIAMOND,6));
            transfers.drop(owner,claim,MiningRules.Ore.DIAMOND,3);transfers.drop(owner,claim,MiningRules.Ore.DIAMOND,3);
            assertEquals(3,store.profile(owner).cargo().get(MiningRules.Ore.DIAMOND));assertEquals(3,transfers.claim(claim).amount());
        }
        try(var store=new MiningStore(file);var transfers=new CargoTransfers(file,store)){
            assertEquals(2,transfers.pickup(claim,recipient,2));assertEquals(1,transfers.claim(claim).amount());
            assertEquals(1,transfers.pickup(claim,recipient,64));assertNull(transfers.claim(claim));
            assertEquals(0,transfers.pickup(claim,recipient,64));
            assertEquals(3,store.profile(owner).cargo().get(MiningRules.Ore.DIAMOND));assertEquals(3,store.profile(recipient).cargo().get(MiningRules.Ore.DIAMOND));
        }
    }
    @Test void failedClaimCommitRollsBackTheOreDebitAndDespawnNeverRefunds()throws Exception{
        Path file=directory.resolve("mining.db");UUID owner=UUID.randomUUID(),claim=UUID.randomUUID();
        try(var store=new MiningStore(file);var transfers=new CargoTransfers(file,store);var db=DriverManager.getConnection("jdbc:sqlite:"+file)){
            store.save(owner,MiningStore.Profile.fresh().credit(MiningRules.Ore.IRON,4));
            try(var sql=db.createStatement()){sql.execute("CREATE TRIGGER fail_drop BEFORE INSERT ON mining_cargo_drops BEGIN SELECT RAISE(ABORT,'injected write failure'); END");}
            assertThrows(java.io.IOException.class,()->transfers.drop(owner,claim,MiningRules.Ore.IRON,2));
            assertEquals(4,store.profile(owner).cargo().get(MiningRules.Ore.IRON));assertNull(transfers.claim(claim));
            try(var sql=db.createStatement()){sql.execute("DROP TRIGGER fail_drop");}
            transfers.drop(owner,claim,MiningRules.Ore.IRON,2);transfers.retire(claim);
            assertEquals(2,store.profile(owner).cargo().get(MiningRules.Ore.IRON));assertEquals(0,transfers.pickup(claim,owner,2));
        }
    }
}
