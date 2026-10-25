package dev.moma.paper;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DefenseRewardsTest {
    @TempDir Path directory;
    final UUID player=UUID.randomUUID(),session=UUID.randomUUID();
    final Map<String,Long> received=new LinkedHashMap<>();
    NoquiHooks wallet(){return new NoquiHooks(){public boolean rewardStarlight(UUID id,String transaction,long amount){assertEquals(player,id);received.putIfAbsent(transaction,amount);return true;}};}
    long total(){return received.values().stream().mapToLong(Long::longValue).sum();}
    @Test void milestonesPayProgressivelyAt99_100_199_200_300AndRepeatDoesNotPay()throws Exception {
        var rewards=new DefenseRewards(directory.resolve("rewards.json"),this::wallet,s->{});
        rewards.reached(session,player,99);assertEquals(0,total());rewards.reached(session,player,100);assertEquals(30,total());
        rewards.reached(session,player,199);assertEquals(30,total());rewards.reached(session,player,200);assertEquals(90,total());
        rewards.reached(session,player,300);assertEquals(180,total());rewards.reached(session,player,300);rewards.reached(session,player,299);assertEquals(3,received.size());assertEquals(180,total());
        assertEquals(List.of(30L,60L,90L),List.copyOf(received.values()));
    }
    @Test void skippedCheckpointsCatchUpTo1000Total1650AndReloadDoesNotRepeat()throws Exception {
        Path file=directory.resolve("rewards.json");var rewards=new DefenseRewards(file,this::wallet,s->{});rewards.reached(session,player,1000);assertEquals(1650,total());assertEquals(10,received.size());
        new DefenseRewards(file,this::wallet,s->{}).reached(session,player,1000);assertEquals(1650,total());
        new DefenseRewards(file,this::wallet,s->{}).reached(session,player,1100);assertEquals(1980,total());
    }
    @Test void offlineWalletPersistsAllCheckpointsAndRestartRetriesWithoutEndDuplication()throws Exception {
        Path file=directory.resolve("rewards.json");var rewards=new DefenseRewards(file,()->NoquiHooks.NONE,s->{});rewards.reached(session,player,300);assertTrue(Files.readString(file).contains("moma-milestone:"));
        var resumed=new DefenseRewards(file,this::wallet,s->{});resumed.flush();assertEquals(180,total());resumed.reached(session,player,300);resumed.flush();assertEquals(180,total());
    }
    @Test void deliveryFailureRetriesSameCheckpointIdAndCorruptFileFailsClosed()throws Exception {
        Path file=directory.resolve("rewards.json");List<String> attempts=new ArrayList<>();boolean[] available={false};
        var hooks=new NoquiHooks(){public boolean rewardStarlight(UUID p,String id,long amount){attempts.add(id);if(!available[0])throw new IllegalStateException("wallet offline");received.putIfAbsent(id,amount);return true;}};
        var rewards=new DefenseRewards(file,()->hooks,s->{});rewards.reached(session,player,100);available[0]=true;rewards.flush();assertEquals(2,attempts.size());assertEquals(attempts.getFirst(),attempts.getLast());assertEquals(30,total());
        Files.writeString(file,"corrupt");assertThrows(java.io.IOException.class,()->new DefenseRewards(file,this::wallet,s->{}));
    }
    @Test void runtimeReloadAndSuspendBlockDirtyFirstSaveThenDurableHandoffPreservesAllCheckpoints()throws Exception {
        Path file=directory.resolve("rewards.json"),blocked=file.resolveSibling("rewards.json.tmp");Files.createDirectory(blocked);
        var rewards=new DefenseRewards(file,()->NoquiHooks.NONE,s->{});rewards.reached(session,player,300);var games=org.mockito.Mockito.mock(GameService.class);games.rewards=rewards;
        var runtime=new GameRuntime();var field=GameRuntime.class.getDeclaredField("games");field.setAccessible(true);field.set(runtime,games);
        assertThrows(IllegalStateException.class,runtime::checkReloadReady);assertThrows(IllegalStateException.class,runtime::suspend);assertFalse(Files.exists(file));
        Files.delete(blocked);assertDoesNotThrow(runtime::checkReloadReady);new DefenseRewards(file,this::wallet,s->{}).flush();assertEquals(180,total());assertEquals(3,received.size());
    }
    @Test void legacyPendingTransactionsRetainReceiptIdsAndDoNotRewriteExistingBalances()throws Exception {
        Path file=directory.resolve("rewards.json");String old="moma:"+session+":"+player;
        Files.writeString(file,"{\"version\":1,\"pending\":{\""+old+"\":{\"player\":\""+player+"\",\"amount\":12}}}");
        var rewards=new DefenseRewards(file,this::wallet,s->{});rewards.flush();assertEquals(Map.of(old,12L),received);assertTrue(Files.readString(file).contains("\"version\":2"));
        new DefenseRewards(file,this::wallet,s->{}).flush();assertEquals(12,total());
    }
    @Test void existingMilestonesKeepTheirReceiptsAndOnlyNewMilestonesUseTheTripledRate()throws Exception {
        Path file=directory.resolve("mixed-rates.json");String key=session+":"+player,id="moma-milestone:"+key+":1";
        Files.writeString(file,"{\"version\":2,\"pending\":{\""+id+"\":{\"player\":\""+player+"\",\"amount\":10}},\"checkpoints\":{\""+key+"\":1}}");
        var rewards=new DefenseRewards(file,this::wallet,s->{});rewards.flush();assertEquals(10,total());
        rewards.reached(session,player,100);assertEquals(10,total());
        rewards.reached(session,player,300);assertEquals(List.of(10L,60L,90L),List.copyOf(received.values()));assertEquals(160,total());
        new DefenseRewards(file,this::wallet,s->{}).reached(session,player,300);assertEquals(160,total());
    }
}
