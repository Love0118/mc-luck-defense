package dev.moma.paper;

import dev.moma.core.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PresentationTest {
    private Defender unit(UnitType type) { return new Defender(UUID.randomUUID(),UUID.randomUUID(),"a",type,Rarity.MYTHIC,new Cell(0,0)); }
    @Test void linesRingsAndChainsUseActualAttackTargets() {
        Point a=new Point(5,0),b=new Point(5,5),c=new Point(1,5);
        var single=AttackEffects.trace(unit(UnitType.WOLF),List.of(a));
        assertEquals(new Point(0,0),single.getFirst()); assertEquals(a,single.getLast());
        assertTrue(single.stream().allMatch(p->p.z()==0 && p.x()>=0 && p.x()<=5));
        Defender area=unit(UnitType.BLAZE); var ring=AttackEffects.footprint(area,a);
        double radius=area.profile().areaRadius()+.5*area.rarity().abilityLevel();
        for(Point p:ring.subList(ring.size()-33,ring.size())) assertEquals(radius*radius,p.distanceSquared(a),1e-8);
        var chain=AttackEffects.trace(unit(UnitType.EVOKER),List.of(a,b,c));
        assertTrue(chain.contains(a)); assertTrue(chain.contains(b)); assertEquals(c,chain.getLast());
        assertTrue(chain.stream().allMatch(p->p.z()==0 || p.x()==5 || p.z()==5));
        assertTrue(AttackEffects.trace(area,List.of()).isEmpty());
        assertTrue(AttackEffects.footprint(unit(UnitType.WOLF),a).isEmpty());
        assertTrue(AttackEffects.footprint(unit(UnitType.EVOKER),a).isEmpty());
    }
    @Test void cleaveFootprintIsRotated120DegreeSectorWithBothRadialEdges() {
        for (Rarity rarity:Rarity.values()) for (double direction:new double[]{0,.4,Math.PI/2,Math.PI,-2.4}) {
            Defender defender=new Defender(UUID.randomUUID(),UUID.randomUUID(),"a",UnitType.IRON_GOLEM,rarity,new Cell(2,1));
            Point origin=defender.position(); double range=defender.profile().range();
            Point primary=new Point(origin.x()+Math.cos(direction)*range*.8,origin.z()+Math.sin(direction)*range*.8);
            var points=AttackEffects.footprint(defender,primary);
            assertEquals(origin,points.getFirst());assertEquals(origin,points.getLast());
            int arc=0;boolean left=false,right=false;
            for(Point p:points) {
                double distance=Math.sqrt(p.distanceSquared(origin));
                assertTrue(distance<=range+1e-9);
                if(distance<1e-9)continue;
                double angle=Math.atan2(p.z()-origin.z(),p.x()-origin.x())-direction;
                angle=Math.atan2(Math.sin(angle),Math.cos(angle));
                assertTrue(Math.abs(angle)<=Math.PI/3+1e-9,"No rear/full-circle points");
                assertTrue(AttackGeometry.inCleave(origin,primary,new Point(origin.x()+(p.x()-origin.x())*.999999,origin.z()+(p.z()-origin.z())*.999999),range)
                        || Math.abs(Math.abs(angle)-Math.PI/3)<1e-9);
                if(Math.abs(distance-range)<1e-9)arc++;
                if(Math.abs(angle+Math.PI/3)<1e-9)left=true;
                if(Math.abs(angle-Math.PI/3)<1e-9)right=true;
            }
            assertTrue(arc>=33);assertTrue(left&&right);
        }
    }
    @Test void coincidentPrimaryMatchesFullRadiusCombatFallbackAndCirclesUseEveryGradeRadius() {
        Defender cleave=unit(UnitType.IRON_GOLEM);
        var degenerate=AttackEffects.footprint(cleave,cleave.position());
        assertEquals(33,degenerate.size());
        assertTrue(degenerate.stream().anyMatch(p->p.x()<0));
        for(UnitType type:List.of(UnitType.WITCH,UnitType.BLAZE))for(Rarity rarity:Rarity.values()) {
            Defender defender=new Defender(UUID.randomUUID(),UUID.randomUUID(),"a",type,rarity,new Cell(1,1));
            Point center=new Point(-3,12); double radius=AttackGeometry.areaRadius(defender,defender.profile());
            for(Point p:AttackEffects.footprint(defender,center))assertEquals(radius*radius,p.distanceSquared(center),1e-8);
        }
    }
    @Test void onlyFirstActualHitSetsFacingAndFootprintStaysAtFloorHeight() {
        var effects=new AttackEffects();Defender defender=unit(UnitType.IRON_GOLEM);
        assertTrue(effects.hit(defender,new Point(3,0)));assertFalse(effects.hit(defender,new Point(3,1)));
        Player viewer=mock(Player.class);
        effects.render(new ArenaMap("a",mock(World.class),0,64,0,new Grid(6)),List.of(viewer));
        verify(viewer,atLeastOnce()).spawnParticle(eq(Particle.DUST),anyDouble(),eq(65.06),anyDouble(),eq(1),eq(0d),eq(0d),eq(0d),eq(0d),any(Particle.DustOptions.class));
        verify(viewer,atLeastOnce()).spawnParticle(eq(Particle.DUST),anyDouble(),eq(65.65),anyDouble(),eq(1),eq(0d),eq(0d),eq(0d),eq(0d),any(Particle.DustOptions.class));
        effects.clear();assertTrue(effects.hit(defender,new Point(-3,0)));
    }
    @Test void dustColorAndDeliveryFollowAttackerGradeAndOnlyExplicitViewers() {
        var effects=new AttackEffects(); Defender defender=unit(UnitType.WOLF); effects.hit(defender,new Point(3,0));
        Player owner=mock(Player.class),spectator=mock(Player.class),other=mock(Player.class);
        effects.render(new ArenaMap("a",mock(World.class),128,64,0,new Grid(6)),List.of(owner,spectator));
        var dust=org.mockito.ArgumentCaptor.forClass(Particle.DustOptions.class);
        verify(owner,atLeastOnce()).spawnParticle(eq(Particle.DUST),anyDouble(),eq(65.65),anyDouble(),eq(1),eq(0d),eq(0d),eq(0d),eq(0d),dust.capture());
        assertTrue(dust.getAllValues().stream().allMatch(d->d.getColor().asRGB()==EntityAdapter.rarityColor(Rarity.MYTHIC).value()));
        verify(spectator,atLeastOnce()).spawnParticle(eq(Particle.DUST),anyDouble(),anyDouble(),anyDouble(),eq(1),eq(0d),eq(0d),eq(0d),eq(0d),any(Particle.DustOptions.class));
        verifyNoInteractions(other);
    }
    @Test void guiTextParsesLegacyAndHexColorsAndDisablesItalicRecursively() {
        Component text=Ui.text("&a초록 &l굵게 &o이탤릭 금지 &#ff1234HEX §b파랑");
        var nodes=new ArrayList<Component>(); collect(text,nodes);
        assertTrue(nodes.stream().allMatch(c->c.decoration(TextDecoration.ITALIC)==TextDecoration.State.FALSE));
        assertTrue(nodes.stream().anyMatch(c->NamedTextColor.GREEN.equals(c.color())));
        assertTrue(nodes.stream().anyMatch(c->TextColor.color(0xff1234).equals(c.color())));
        assertTrue(nodes.stream().anyMatch(c->NamedTextColor.AQUA.equals(c.color())));
    }
    private void collect(Component component,List<Component> result) {result.add(component);component.children().forEach(c->collect(c,result));}
    @Test void tabShowsUnclampedAcceleratedTpsAndActualTarget() {
        Player player=mock(Player.class); var manager=mock(org.bukkit.ServerTickManager.class);
        when(manager.getTickRate()).thenReturn(320f);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getTPS).thenReturn(new double[]{312.5,300,290});
            bukkit.when(Bukkit::getServerTickManager).thenReturn(manager);
            bukkit.when(Bukkit::getAverageTickTime).thenReturn(2.4);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            TabStatus.update();
            verify(player).sendPlayerListHeaderAndFooter(Ui.text("&6&lMC Luck Defense"),Ui.text("&7TPS (1분) &a312.5 &7/ 320  &8| &7MSPT &f2.40"));
        }
    }
    @Test void attackSoundsAreDistinctAndPlayOncePerAttackPerViewer() {
        assertEquals(6,Arrays.stream(AttackRole.values()).map(AttackEffects::attackSound).distinct().count());
        var effects=new AttackEffects();Defender defender=unit(UnitType.EVOKER);Player viewer=mock(Player.class);
        effects.hit(defender,new Point(2,0));effects.hit(defender,new Point(3,0));effects.hit(defender,new Point(4,0));
        effects.render(new ArenaMap("a",mock(World.class),0,64,0,new Grid(6)),List.of(viewer));
        verify(viewer,times(1)).playSound(any(Location.class),eq("minecraft:entity.evoker.cast_spell"),eq(SoundCategory.PLAYERS),eq(.35f),eq(1f));
    }
}
