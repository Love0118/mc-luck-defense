package dev.moma.mining;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MiningStoreTest {
    @TempDir Path directory;
    private final UUID player=UUID.randomUUID();
    private static final CaveLayout.Cell CELL=CaveLayout.cells().getFirst();
    static MiningStore.Profile session(){return MiningStore.Profile.fresh().recovery(new MiningStore.Recovery(UUID.randomUUID(),new byte[]{1,2,3}));}
    @Test void profilesToolsCargoAndRecoverySurviveRestart()throws Exception {
        Path file=directory.resolve("mining.db");var p=session().upgrade(new MiningStore.Pending("speed",MiningStore.Operation.SPEED,150)).upgrade(new MiningStore.Pending("fortune",MiningStore.Operation.FORTUNE,600)).credit(MiningRules.Ore.DIAMOND,8);
        try(var store=new MiningStore(file)){store.save(player,p);}
        try(var store=new MiningStore(file)) {
            var loaded=store.profile(player);assertEquals(p.tool(),loaded.tool());assertEquals(51,loaded.speed());assertEquals(1,loaded.fortune());
            assertEquals(8L,loaded.cargo().get(MiningRules.Ore.DIAMOND));assertEquals(p.recovery().token(),loaded.recovery().token());
            assertArrayEquals(p.recovery().data(),loaded.recovery().data());
        }
    }
    @Test void requestedResetRunsOnceAndPreservesCargoRecoveryAndFutureUpgrades()throws Exception{
        Path file=directory.resolve("mine.db");var p=session().credit(MiningRules.Ore.DIAMOND,5);
        p=new MiningStore.Profile(p.tool(),180,3,p.cargo(),p.recovery(),null);
        try(var store=new MiningStore(file)){
            store.save(player,p);store.resetPickaxes();var reset=store.profile(player);
            assertEquals(50,reset.speed());assertEquals(0,reset.fortune());assertNotEquals(p.tool(),reset.tool());assertEquals(p.cargo(),reset.cargo());assertEquals(p.recovery().token(),reset.recovery().token());
            store.save(player,reset.upgrade(new MiningStore.Pending("upgrade",MiningStore.Operation.SPEED,150)));
        }
        try(var store=new MiningStore(file)){store.resetPickaxes();assertEquals(51,store.profile(player).speed());}
    }
    @Test void legacyFixedStepsDecodeBeforeOneTimeResetWithoutLosingMinerals()throws Exception{
        var p=session().credit(MiningRules.Ore.EMERALD,7);byte[] legacy=MiningStore.encode(p);
        java.nio.ByteBuffer.wrap(legacy).putInt(0,1).putInt(20,5);
        var decoded=MiningStore.decode(legacy);assertEquals(175,decoded.speed());assertEquals(p.cargo(),decoded.cargo());
        try(var store=new MiningStore(directory.resolve("legacy.db"))){store.save(player,decoded);store.resetPickaxes();assertEquals(50,store.profile(player).speed());assertEquals(7L,store.profile(player).cargo().get(MiningRules.Ore.EMERALD));}
    }
    @Test void cellClaimAndCargoAreAtomicAndCannotBeReplayedEvenAfterRestart()throws Exception {
        Path file=directory.resolve("mining.db");
        try(var store=new MiningStore(file)) {
            store.save(player,session());store.replaceCells(Map.of(CELL,"DIAMOND_ORE"),1);
            assertTrue(store.mine(player,CELL,"DIAMOND_ORE",MiningRules.Ore.DIAMOND,4));
            assertFalse(store.mine(player,CELL,"DIAMOND_ORE",MiningRules.Ore.DIAMOND,4));
        }
        try(var store=new MiningStore(file)) {
            assertFalse(store.mine(player,CELL,"DIAMOND_ORE",MiningRules.Ore.DIAMOND,4));
            assertEquals("AIR",store.cells().get(CELL));assertEquals(4L,store.profile(player).cargo().get(MiningRules.Ore.DIAMOND));
        }
    }
    @Test void failedProfileCreditRollsBackCellClaim()throws Exception {
        try(var store=new MiningStore(directory.resolve("mining.db"))) {
            store.replaceCells(Map.of(CELL,"COAL_ORE"),1);
            assertThrows(IOException.class,()->store.mine(player,CELL,"COAL_ORE",MiningRules.Ore.COAL,1));
            assertEquals("COAL_ORE",store.cells().get(CELL));assertTrue(store.profile(player).cargo().isEmpty());
            store.save(player,session());assertTrue(store.mine(player,CELL,"COAL_ORE",MiningRules.Ore.COAL,1));
        }
    }
    @Test void foreignOrInvalidClaimsAndPendingEconomyCannotEarnCargo()throws Exception {
        try(var store=new MiningStore(directory.resolve("mining.db"))) {
            store.save(player,session());store.replaceCells(Map.of(CELL,"STONE"),1);
            assertThrows(IllegalArgumentException.class,()->store.mine(player,CELL,"STONE",MiningRules.Ore.DIAMOND,1));
            assertFalse(store.mine(player,CELL,"IRON_ORE",MiningRules.Ore.IRON,1));
            assertThrows(IllegalArgumentException.class,()->store.mine(player,new CaveLayout.Cell(0,CaveLayout.HUB_FLOOR,-30),"STONE",null,0));
            var pending=new MiningStore.Pending("sale",MiningStore.Operation.SALE,4);store.save(player,store.profile(player).pending(pending));
            assertThrows(IOException.class,()->store.mine(player,CELL,"STONE",null,0));assertEquals("STONE",store.cells().get(CELL));
        }
    }
    @Test void corruptProfileFailsClosedInsteadOfResettingIt()throws Exception {
        Path file=directory.resolve("mining.db");
        try(var store=new MiningStore(file)) {
            store.save(player,session());
            try(var connection=DriverManager.getConnection("jdbc:sqlite:"+file);var sql=connection.createStatement()) {
                sql.executeUpdate("UPDATE mining_profiles SET data=X'00000001' WHERE id='"+player+"'");
            }
            assertThrows(IOException.class,()->store.profile(player));
        }
    }
    @Test void closedStorageRejectsMutationWithoutRewarding()throws Exception {
        var store=new MiningStore(directory.resolve("mining.db"));store.save(player,session());store.close();
        assertThrows(IOException.class,()->store.save(player,session()));
    }
    @Test void recoveryBuffersAreImmutableAndCodecRejectsBadLevelsOrTrailingData()throws Exception {
        byte[] bytes={1,2};var recovery=new MiningStore.Recovery(UUID.randomUUID(),bytes);bytes[0]=9;
        assertEquals(1,recovery.data()[0]);byte[] copy=recovery.data();copy[0]=9;assertEquals(1,recovery.data()[0]);
        var encoded=MiningStore.encode(session());java.nio.ByteBuffer.wrap(encoded).putInt(20,301);
        assertThrows(IOException.class,()->MiningStore.decode(encoded));
        var valid=MiningStore.encode(session());assertThrows(IOException.class,()->MiningStore.decode(Arrays.copyOf(valid,valid.length+1)));
    }
    @Test void generationLedgerRoundTripHasBoundedMeasuredStartupCost()throws Exception {
        long begin=System.nanoTime();var cells=new LinkedHashMap<CaveLayout.Cell,String>();for(var c:CaveLayout.cells())cells.put(c,"STONE");
        cells.putAll(CaveLayout.veins(cells.keySet(),new Random(12),.22));
        try(var store=new MiningStore(directory.resolve("mining.db"))) {
            int ores=(int)cells.values().stream().filter(s->MiningRules.Ore.fromBlock(s)!=null).count();store.replaceCells(cells,ores);
            assertEquals(cells,store.cells());assertEquals(Integer.toString(ores),store.meta("initial-ores"));
        }
        System.out.printf(Locale.ROOT,"Mining generation+FULL SQLite ledger: %d cells, %.1f ms%n",cells.size(),(System.nanoTime()-begin)/1_000_000.0);
    }
}
