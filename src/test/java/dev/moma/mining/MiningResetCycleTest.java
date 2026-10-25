package dev.moma.mining;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.*;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MiningResetCycleTest {
    @TempDir Path directory;
    @Test void twentyMinuteWallClockDeadlinePersistsAndDepletionIncludesSixtyPercent()throws Exception{
        var clock=new AtomicLong(1_000_000);var world=mock(MiningWorld.class);when(world.ready()).thenReturn(true);
        Path file=directory.resolve("mining.db");
        try(var store=new MiningStore(file)){
            var cycle=new MiningResetCycle(world,store,clock::get,.60,()->{});cycle.counts(100,100);
            assertEquals(1_200_000,cycle.remaining());assertFalse(cycle.shouldReset());
            clock.addAndGet(1_199_999);assertFalse(cycle.shouldReset());clock.incrementAndGet();assertTrue(cycle.shouldReset());
            cycle.scheduleNext();cycle.counts(61,100);assertFalse(cycle.shouldReset());cycle.counts(60,100);assertTrue(cycle.shouldReset());
            cycle.counts(100,100);clock.addAndGet(1234);
            var restored=new MiningResetCycle(world,store,clock::get,.60,()->{});restored.counts(100,100);assertEquals(1_198_766,restored.remaining());assertFalse(restored.shouldReset());
        }
        clock.addAndGet(1_200_000);
        try(var store=new MiningStore(file)){var overdue=new MiningResetCycle(world,store,clock::get,.60,()->{});assertTrue(overdue.due());}
    }
    @Test void manualResetUsesTheSameRegenerationCallbackAndRestartsTheClock()throws Exception{
        var clock=new AtomicLong(50_000);var world=mock(MiningWorld.class);when(world.ready()).thenReturn(true);
        try(var store=new MiningStore(directory.resolve("manual.db"))){
            var ref=new AtomicReference<MiningResetCycle>();var resets=new AtomicInteger();
            var cycle=new MiningResetCycle(world,store,clock::get,.60,()->{resets.incrementAndGet();ref.get().counts(100,100);ref.get().scheduleNext();});ref.set(cycle);
            clock.addAndGet(900_000);cycle.counts(73,100);assertEquals(300_000,cycle.snapshot().remainingMillis());
            cycle.requestReset();assertEquals(1,resets.get());assertEquals(1_200_000,cycle.remaining());assertEquals(100,cycle.snapshot().remainingOre());
            when(world.ready()).thenReturn(false);assertThrows(IllegalArgumentException.class,cycle::requestReset);assertEquals(1,resets.get());
        }
    }
    @Test void bossbarUsesTheAuthoritativeDeadlineAndOnlyTheMineOwnsItsViewers()throws Exception{
        var clock=new AtomicLong(1000);var world=mock(MiningWorld.class);var player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        try(var store=new MiningStore(directory.resolve("hud.db"))){
            var cycle=new MiningResetCycle(world,store,clock::get,.60,()->{});
            cycle.render(List.of(player),true,false);var bar=ArgumentCaptor.forClass(BossBar.class);verify(player).showBossBar(bar.capture());
            assertEquals("광산 리셋 20:00",PlainTextComponentSerializer.plainText().serialize(bar.getValue().name()));assertEquals(1,bar.getValue().progress());
            clock.addAndGet(600_000);cycle.render(List.of(player),true,false);assertEquals(.5f,bar.getValue().progress());assertEquals("광산 리셋 10:00",PlainTextComponentSerializer.plainText().serialize(bar.getValue().name()));
            cycle.render(List.of(player),false,false);assertEquals("광산 리셋 중",PlainTextComponentSerializer.plainText().serialize(bar.getValue().name()));
            cycle.render(List.of(),true,false);verify(player).hideBossBar(bar.getValue());cycle.close();verify(player,times(1)).hideBossBar(any());
        }
    }
}
