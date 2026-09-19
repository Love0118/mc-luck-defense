package dev.moma.paper;

import dev.moma.core.*;
import java.util.UUID;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UiFeedbackTest {
    private void heard(Player player,Ui.Cue cue,int count) {
        verify(player,times(count)).playSound(any(Location.class),eq(cue.sound),eq(SoundCategory.MASTER),eq(cue.volume),eq(cue.pitch));
    }
    @Test void transactionSoundsFollowActualResultsAndOnlyReachTheActingPlayer() {
        MomaPlugin plugin=mock(MomaPlugin.class);when(plugin.namespace()).thenReturn("momadefense");
        Player player=mock(Player.class),other=mock(Player.class);World world=mock(World.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.getLocation()).thenReturn(new Location(world,0,65,0));
        when(player.getGameMode()).thenReturn(GameMode.ADVENTURE);
        try(var adapters=mockConstruction(EntityAdapter.class,(adapter,context)->
                    when(adapter.spawnDefender(any(),any(),any(),any(),any())).thenAnswer(call->UUID.randomUUID()));
            var rolls=mockStatic(SummonRoll.class);var bukkit=mockStatic(Bukkit.class)) {
            GameService games=spy(new GameService(plugin,mock(ArenaMaps.class),CampaignRules.standard()));
            GameSession session=new GameSession(player,new ArenaMap("a",world,0,64,0,new Grid(6)),CampaignRules.standard());
            doReturn(session).when(games).session(player);
            rolls.when(()->SummonRoll.draw(any())).thenReturn(new SummonRoll(UnitType.WOLF,Rarity.COMMON));
            games.summon(player);heard(player,Ui.Cue.SUMMON,1);heard(player,Ui.Cue.ERROR,0);
            session.arena.select(player.getUniqueId(),session.arena.defenders().getFirst().entityId());
            long before=session.arena.coins();games.sell(player);assertEquals(before+3,session.arena.coins());heard(player,Ui.Cue.SELL,1);
            games.sell(player);heard(player,Ui.Cue.ERROR,1);heard(player,Ui.Cue.SELL,1);
            rolls.when(()->SummonRoll.draw(any())).thenReturn(new SummonRoll(UnitType.WOLF,Rarity.PRIMORDIAL));
            games.summon(player);heard(player,Ui.Cue.RARE_SUMMON,1);heard(player,Ui.Cue.SUMMON,1);
            session.arena.select(player.getUniqueId(),session.arena.defenders().getFirst().entityId());
            before=session.arena.coins();games.sell(player);assertEquals(before,session.arena.coins());heard(player,Ui.Cue.ERROR,2);heard(player,Ui.Cue.SELL,1);
            games.speed(player,8);heard(player,Ui.Cue.SPEED,1);
            verifyNoInteractions(other);
        }
    }
}
