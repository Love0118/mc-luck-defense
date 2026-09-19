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
        var single=AttackEffects.shape(unit(UnitType.WOLF),List.of(a));
        assertEquals(new Point(0,0),single.getFirst()); assertEquals(a,single.getLast());
        assertTrue(single.stream().allMatch(p->p.z()==0 && p.x()>=0 && p.x()<=5));
        Defender area=unit(UnitType.BLAZE); var ring=AttackEffects.shape(area,List.of(a,b,c));
        double radius=area.profile().areaRadius()+.5*area.rarity().abilityLevel();
        for(Point p:ring.subList(ring.size()-33,ring.size())) assertEquals(radius*radius,p.distanceSquared(a),1e-8);
        var chain=AttackEffects.shape(unit(UnitType.EVOKER),List.of(a,b,c));
        assertTrue(chain.contains(a)); assertTrue(chain.contains(b)); assertEquals(c,chain.getLast());
        assertTrue(chain.stream().allMatch(p->p.z()==0 || p.x()==5 || p.z()==5));
        assertTrue(AttackEffects.shape(area,List.of()).isEmpty());
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
}
