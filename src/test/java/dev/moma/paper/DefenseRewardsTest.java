package dev.moma.paper;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DefenseRewardsTest {
    @TempDir Path directory;
    @Test void offlineWalletRetriesFromDurableOutboxAndDuplicateSessionDeliveryIsIdempotent()throws Exception {
        Path file=directory.resolve("rewards.json");UUID player=UUID.randomUUID(),session=UUID.randomUUID();
        var unavailable=new DefenseRewards(file,2,()->NoquiHooks.NONE,s->{});unavailable.award(session,player,123);assertTrue(Files.readString(file).contains("246"));
        var received=new HashMap<String,Long>();int[] calls={0};var wallet=new NoquiHooks(){public boolean rewardStarlight(UUID id,String transaction,long amount){assertEquals(player,id);calls[0]++;received.putIfAbsent(transaction,amount);return true;}};
        var retry=new DefenseRewards(file,2,()->wallet,s->{});retry.flush();assertEquals(Map.of("moma:"+session+":"+player,246L),received);
        new DefenseRewards(file,2,()->wallet,s->{}).flush();assertEquals(1,calls[0]);retry.award(session,player,123);assertEquals(1,received.size());
    }
    @Test void preparedRoundZeroAndDisabledRewardsDoNotPay()throws Exception {
        int[] calls={0};var hooks=new NoquiHooks(){public boolean rewardStarlight(UUID p,String id,long amount){calls[0]++;return true;}};
        var rewards=new DefenseRewards(directory.resolve("rewards.json"),1,()->hooks,s->{});rewards.award(UUID.randomUUID(),UUID.randomUUID(),0);
        new DefenseRewards(directory.resolve("disabled.json"),0,()->hooks,s->{}).award(UUID.randomUUID(),UUID.randomUUID(),500);assertEquals(0,calls[0]);
    }
    @Test void deliveryFailureRetriesSameTransactionAndCorruptFileFailsClosed()throws Exception {
        Path file=directory.resolve("rewards.json");UUID player=UUID.randomUUID(),session=UUID.randomUUID();List<String> calls=new ArrayList<>();boolean[] available={false};
        var hooks=new NoquiHooks(){public boolean rewardStarlight(UUID p,String id,long amount){calls.add(id);if(!available[0])throw new IllegalStateException("wallet offline");return true;}};
        var rewards=new DefenseRewards(file,1,()->hooks,s->{});rewards.award(session,player,200);available[0]=true;rewards.flush();assertEquals(2,calls.size());assertEquals(calls.getFirst(),calls.getLast());
        Files.writeString(file,"corrupt");assertThrows(java.io.IOException.class,()->new DefenseRewards(file,1,()->hooks,s->{}));
    }
    @Test void runtimeReloadAndSuspendBlockUndurableRewardsThenTransferDurablePendingToReplacement()throws Exception {
        Path file=directory.resolve("rewards.json"),blocked=file.resolveSibling("rewards.json.tmp");Files.createDirectory(blocked);
        UUID session=UUID.randomUUID(),player=UUID.randomUUID();var rewards=new DefenseRewards(file,1,()->NoquiHooks.NONE,s->{});rewards.award(session,player,12);
        var games=org.mockito.Mockito.mock(GameService.class);games.rewards=rewards;
        var runtime=new GameRuntime();var field=GameRuntime.class.getDeclaredField("games");field.setAccessible(true);field.set(runtime,games);
        assertThrows(IllegalStateException.class,runtime::checkReloadReady);assertThrows(IllegalStateException.class,runtime::suspend);assertFalse(Files.exists(file));
        Files.delete(blocked);assertDoesNotThrow(runtime::checkReloadReady);assertTrue(Files.exists(file));
        var received=new HashMap<String,Long>();var hooks=new NoquiHooks(){public boolean rewardStarlight(UUID id,String transaction,long amount){assertEquals(player,id);received.putIfAbsent(transaction,amount);return true;}};
        new DefenseRewards(file,1,()->hooks,s->{}).flush();assertEquals(Map.of("moma:"+session+":"+player,12L),received);
        new DefenseRewards(file,1,()->hooks,s->{}).flush();assertEquals(1,received.size());
    }
}
