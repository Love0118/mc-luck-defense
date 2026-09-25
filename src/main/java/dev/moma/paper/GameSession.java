package dev.moma.paper;

import dev.moma.core.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import java.util.*;

final class GameSession {
    final UUID sessionId;
    final Arena arena;
    final ArenaMap map;
    final Location returnLocation;
    final GameMode returnMode;
    final boolean returnAllowFlight, returnFlying;
    final List<Chunk> tickets = new ArrayList<>();
    final Campaign campaign;
    final HashRandom random;
    final AttackEffects attackEffects = new AttackEffects();
    boolean assisted;
    int announcedRound;
    long simulationTick;
    private int speed = 1;
    final EnumSet<Rarity> autoSell = EnumSet.noneOf(Rarity.class);
    final AutoPlacement placement;
    boolean autoPlacement, layoutDirty, bulkBuying;
    int bulkPurchases;
    long lastPrimordialSoundNanos;
    String bgmTrack = "default";

    int speed() { return speed; }
    void speed(int value) {
        if (value != 1 && value != 2 && value != 4 && value != 8 && value != 16 && value != 32)
            throw new IllegalArgumentException("배속은 1, 2, 4, 8, 16, 32 중에서 선택하세요.");
        speed = value;
    }

    GameSession(Player player, ArenaMap map, CampaignRules settings) {
        sessionId=UUID.randomUUID();random=HashRandom.secure();
        this.map = map;
        placement = new AutoPlacement(map.grid());
        arena = new Arena(map.id(), player.getUniqueId(), map.grid(), settings.startingCoins(), settings.enemyLimit(),
                TraitSelections.load(player.getPersistentDataContainer()),HashRandom.secure());
        campaign = new Campaign(settings,true);
        returnLocation = player.getLocation().clone(); returnMode = player.getGameMode();
        returnAllowFlight = player.getAllowFlight(); returnFlying = player.isFlying();
    }
    SessionState.Session save() {
        return new SessionState.Session(sessionId,map.id(),map.world().getUID(),map.originX(),map.floorY(),map.originZ(),arena,campaign,random,
                SessionState.Position.of(returnLocation),returnMode.name(),returnAllowFlight,returnFlying,assisted,announcedRound,simulationTick,speed,
                autoSell.clone(),autoPlacement,layoutDirty,bulkBuying,bulkPurchases,bgmTrack);
    }
    GameSession(SessionState.Session saved,ArenaMap map) {
        if(map==null || !map.world().getUID().equals(saved.world()) || map.originX()!=saved.x() || map.originZ()!=saved.z()
                || map.floorY()!=saved.y() || map.grid().size()!=saved.arena().grid().size())throw new IllegalArgumentException("진행 중인 전장 설정이 변경되었습니다.");
        this.map=map;sessionId=saved.id();arena=saved.arena();campaign=saved.campaign();random=saved.random();
        placement=new AutoPlacement(map.grid());returnLocation=saved.returnLocation().location();returnMode=GameMode.valueOf(saved.returnMode());
        returnAllowFlight=saved.returnFlight();returnFlying=saved.returnFlying();assisted=saved.assisted();announcedRound=saved.announcedRound();
        simulationTick=saved.simulationTick();speed(saved.speed());autoSell.addAll(saved.autoSell());autoPlacement=saved.autoPlacement();
        layoutDirty=saved.layoutDirty();bulkBuying=saved.bulkBuying();bulkPurchases=saved.bulkPurchases();bgmTrack=saved.bgmTrack();
        for(int x=(map.originX()-6)>>4;x<=(map.originX()+map.maxOffset())>>4;x++)for(int z=(map.originZ()+map.minZOffset())>>4;z<=(map.originZ()+map.maxOffset())>>4;z++) {
            if(!map.world().isChunkLoaded(x,z))throw new IllegalArgumentException("진행 중인 전장 청크가 없습니다.");
            tickets.add(map.world().getChunkAt(x,z));
        }
    }
}
