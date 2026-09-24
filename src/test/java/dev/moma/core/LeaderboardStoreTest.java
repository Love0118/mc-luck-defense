package dev.moma.core;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LeaderboardStoreTest {
    @TempDir Path directory;
    private final UUID player=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private RoundRecords.Entry entry(UUID id,String name,int round){return new RoundRecords.Entry(id,name,round);}
    private Connection open()throws SQLException{return DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("leaderboards.db"));}

    @Test void legacyIsArchivedOnceAndNewSeasonHasIndependentPersonalBests()throws Exception {
        Path legacy=directory.resolve("round-records.properties");
        RoundRecords.save(legacy,List.of(entry(player,"옛 이름'",2484),entry(new UUID(0,2),"Other",100)));
        byte[] original=Files.readAllBytes(legacy);
        var store=new LeaderboardStore(directory);
        assertEquals(2484,store.load(LeaderboardStore.Season.PRESEASON).top(1).getFirst().round());
        assertTrue(store.load(LeaderboardStore.Season.SEASON_ONE).snapshot().isEmpty());
        store.saveCurrent(List.of(entry(player,"NewName",30)));
        store.saveCurrent(List.of(entry(player,"NewName",10)));
        assertArrayEquals(original,Files.readAllBytes(legacy));
        RoundRecords.save(legacy,List.of(entry(player,"ChangedLegacy",9999)));
        var reopened=new LeaderboardStore(directory);
        var preseason=reopened.load(LeaderboardStore.Season.PRESEASON).top(10);
        assertEquals(2,preseason.size());assertEquals("옛 이름'",preseason.getFirst().name());assertEquals(2484,preseason.getFirst().round());
        assertEquals(30,reopened.load(LeaderboardStore.Season.SEASON_ONE).top(1).getFirst().round());
    }

    @Test void freshInstallInitializesBothSeasonsAndNeverImportsALateLegacyFile()throws Exception {
        var store=new LeaderboardStore(directory);
        for(var season:LeaderboardStore.Season.values())assertTrue(store.load(season).snapshot().isEmpty());
        RoundRecords.save(directory.resolve("round-records.properties"),List.of(entry(player,"Late",2000)));
        store=new LeaderboardStore(directory);
        assertTrue(store.load(LeaderboardStore.Season.PRESEASON).snapshot().isEmpty());
    }

    @Test void malformedSourceRollsBackSchemaAndCanBeRetriedWithoutLosingSource()throws Exception {
        Path legacy=directory.resolve("round-records.properties");
        Files.writeString(legacy,player+".round=not-a-round\n");
        byte[] original=Files.readAllBytes(legacy);
        assertThrows(IOException.class,()->new LeaderboardStore(directory));
        assertArrayEquals(original,Files.readAllBytes(legacy));
        try(var connection=open();var statement=connection.createStatement()) {
            try(var rows=statement.executeQuery("PRAGMA user_version")){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
            try(var rows=statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='round_records'")){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
        }
        RoundRecords.save(legacy,List.of(entry(player,"Recovered",123)));
        assertEquals(123,new LeaderboardStore(directory).load(LeaderboardStore.Season.PRESEASON).top(1).getFirst().round());
    }

    @Test void failedBatchDoesNotPartiallyWriteOrDamageEitherSeason()throws Exception {
        RoundRecords.save(directory.resolve("round-records.properties"),List.of(entry(player,"Preseason",1000)));
        var store=new LeaderboardStore(directory);store.saveCurrent(List.of(entry(player,"Current",100)));
        assertThrows(IOException.class,()->store.saveCurrent(List.of(entry(player,"Partial",200),entry(new UUID(0,2),"Invalid",0))));
        assertEquals(List.of(entry(player,"Current",100)),store.load(LeaderboardStore.Season.SEASON_ONE).snapshot());
        assertEquals(List.of(entry(player,"Preseason",1000)),store.load(LeaderboardStore.Season.PRESEASON).snapshot());
    }

    @Test void simultaneousInitializationImportsOnlyOnce()throws Exception {
        RoundRecords.save(directory.resolve("round-records.properties"),List.of(entry(player,"Preseason",777)));
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<LeaderboardStore> task=()->{start.await();return new LeaderboardStore(directory);};
            var first=executor.submit(task);var second=executor.submit(task);start.countDown();
            assertEquals(1,first.get().load(LeaderboardStore.Season.PRESEASON).snapshot().size());
            assertEquals(1,second.get().load(LeaderboardStore.Season.PRESEASON).snapshot().size());
        }
    }

    @Test void newerDatabaseIsRejectedWithoutOverwritingItsSchema()throws Exception {
        new LeaderboardStore(directory);
        try(var connection=open();var statement=connection.createStatement()){statement.execute("PRAGMA user_version=2");}
        assertThrows(IOException.class,()->new LeaderboardStore(directory));
        try(var connection=open();var statement=connection.createStatement();var rows=statement.executeQuery("PRAGMA user_version")) {
            assertTrue(rows.next());assertEquals(2,rows.getInt(1));
        }
    }
}
