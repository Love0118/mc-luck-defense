package dev.moma.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaveThemeTest {
    @Test void routeUsesAll67BiomesAndStagesInOrder() {
        assertEquals(100,WaveTheme.all().size());
        assertEquals(67,WaveTheme.all().stream().map(WaveTheme::biome).distinct().count());
        assertEquals(55,WaveTheme.all().stream().filter(t->t.stage()==WaveTheme.Stage.OVERWORLD).count());
        int previous=0;
        for(WaveTheme theme:WaveTheme.all()) {
            assertTrue(theme.stage().ordinal()>=previous);previous=theme.stage().ordinal();
            assertEquals(theme,WaveTheme.at(theme.round()));
        }
        assertEquals(EnemyType.WARDEN,WaveTheme.at(60).boss());
        assertEquals(EnemyType.WITHER,WaveTheme.at(80).boss());
        assertEquals(EnemyType.ENDER_DRAGON,WaveTheme.at(90).boss());
        assertEquals(EnemyType.SHULKER,WaveTheme.at(100).boss());
        assertThrows(IllegalArgumentException.class,()->WaveTheme.at(0));
        assertThrows(IllegalArgumentException.class,()->WaveTheme.at(101));
    }
    @Test void wardensAreSparseDurableAndOnlyAppearInAncientCity() {
        var waves=WaveSchedule.create(CampaignRules.standard());
        for(Wave wave:waves) {
            var wardens=wave.entries().stream().filter(e->e.enemy().type()==EnemyType.WARDEN).toList();
            assertEquals(WaveTheme.at(wave.round()).wardens()+(wave.round()==60?1:0),wardens.size());
            if(wardens.isEmpty())continue;
            assertEquals(WaveTheme.Stage.ANCIENT_CITY,WaveTheme.at(wave.round()).stage());
            double average=wave.entries().stream().filter(e->e.enemy().type()!=EnemyType.WARDEN).mapToDouble(e->e.enemy().health()).average().orElseThrow();
            for(var warden:wardens) {
                assertTrue(warden.enemy().health()>average*3);assertEquals(1.2,warden.enemy().speed());
            }
            if(wardens.size()==2)assertTrue(wardens.get(1).offsetTick()-wardens.get(0).offsetTick()>100);
        }
    }
    @Test void appearanceComesOnlyFromTheDeclaredTheme() {
        for(Wave wave:WaveSchedule.create(CampaignRules.standard())) {
            WaveTheme theme=WaveTheme.at(wave.round());assertEquals(theme.displayName(),wave.name());
            for(var entry:wave.entries()) {
                var enemy=entry.enemy();
                if(enemy.boss())assertEquals(theme.boss(),enemy.type());
                else assertTrue(theme.roster().contains(enemy.type()) || enemy.type()==EnemyType.WARDEN && theme.wardens()>0);
            }
        }
    }
}
