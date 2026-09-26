package dev.moma.paper;

import dev.moma.core.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.*;
import java.util.*;
import java.util.concurrent.*;

final class GameService {
    private final MomaPlugin plugin;
    private final ArenaMaps maps;
    private final CampaignRules settings;
    private final Lobby lobby;
    final EntityAdapter entities;
    final SessionTools tools;
    BgmService bgm;
    RoundLeaderboard leaderboard;
    AchievementService achievements;
    final SpectatorAppearance appearance = new SpectatorAppearance();
    private final Map<UUID, GameSession> sessions = new LinkedHashMap<>();
    private record Watch(GameSession target, Location returnLocation, GameMode returnMode, boolean returnAllowFlight, boolean returnFlying) {}
    private final Map<UUID, Watch> spectators = new LinkedHashMap<>();
    record SessionInfo(UUID sessionId, UUID owner, String playerName, String arena, int round, int enemies) {}
    private final CombatEngine combat = new CombatEngine();
    private ThreadPoolExecutor placementWorkers;
    private long tick;
    SessionState.Game saveState() {
        return new SessionState.Game(tick,sessions.values().stream().map(GameSession::save).toList(),
                spectators.entrySet().stream().map(e->new SessionState.Watch(e.getKey(),e.getValue().target.sessionId,
                        SessionState.Position.of(e.getValue().returnLocation),e.getValue().returnMode.name(),e.getValue().returnAllowFlight,e.getValue().returnFlying)).toList(),
                bgm==null?null:bgm.saveState());
    }
    void restoreState(SessionState.Game state) {
        if(!sessions.isEmpty() || !spectators.isEmpty())throw new IllegalStateException("Runtime already populated");
        tick=state.tick();var bySession=new HashMap<UUID,GameSession>();var entitiesSeen=new HashSet<UUID>();var mapsSeen=new HashSet<String>();
        for(var saved:state.sessions()) {
            var session=new GameSession(saved,maps.get(saved.map()));
            if(Bukkit.getPlayer(session.arena.owner())==null || !mapsSeen.add(saved.map()) || sessions.put(session.arena.owner(),session)!=null
                    || bySession.put(session.sessionId,session)!=null)throw new IllegalArgumentException("중복되거나 접속자가 없는 세션입니다.");
            var ids=new ArrayList<UUID>();session.arena.units().forEach(d->ids.add(d.entityId()));session.arena.activeEnemies().forEach(e->ids.add(e.entityId()));
            for(UUID id:ids) {
                Entity entity=Bukkit.getEntity(id);
                if(!entitiesSeen.add(id) || entity==null || !entity.isValid() || !entity.getWorld().equals(session.map.world()) || !entities.managed(entity))
                    throw new IllegalArgumentException("복원할 전장 엔티티가 없습니다.");
            }
        }
        for(var saved:state.spectators()) {
            var target=bySession.get(saved.session());
            if(target==null || Bukkit.getPlayer(saved.player())==null || sessions.containsKey(saved.player()) || spectators.containsKey(saved.player()))
                throw new IllegalArgumentException("관전 세션을 복원할 수 없습니다.");
            spectators.put(saved.player(),new Watch(target,saved.returnLocation().location(),GameMode.valueOf(saved.returnMode()),saved.returnFlight(),saved.returnFlying()));
        }
    }
    boolean hasSessions(){return !sessions.isEmpty() || !spectators.isEmpty();}
    void rebindPresentation() {
        for(var session:sessions.values()) {
            Player owner=Objects.requireNonNull(Bukkit.getPlayer(session.arena.owner()));
            for(Chunk chunk:session.tickets)chunk.addPluginChunkTicket(plugin);
            appearance.enter(owner,false);entities.restore(session.arena);
            showSelection(owner,session);
        }
        spectators.forEach((id,watch)->appearance.enter(Objects.requireNonNull(Bukkit.getPlayer(id)),true));
    }
    void suspendPresentation(){stopPlacementWorkers();appearance.close();entities.close();}
    private void stopPlacementWorkers() {
        for(GameSession session:sessions.values())cancelPlacement(session);
        if(placementWorkers!=null){placementWorkers.shutdownNow();placementWorkers=null;}
    }
    private void cancelPlacement(GameSession session) {
        if(session.layoutTask!=null){session.layoutTask.result().cancel(true);session.layoutTask=null;}
    }

    GameService(MomaPlugin plugin, ArenaMaps maps, CampaignRules settings) {
        this(plugin, maps, settings, null);
    }
    GameService(MomaPlugin plugin, ArenaMaps maps, CampaignRules settings, Lobby lobby) {
        this.plugin = plugin; this.maps = maps; this.settings = settings; this.lobby = lobby; entities = new EntityAdapter(plugin); tools = new SessionTools(plugin);
        if (lobby != null) lobby.tools = tools;
    }
    GameSession session(Player player) { return sessions.get(player.getUniqueId()); }
    boolean playing(Player player) { return session(player) != null; }
    boolean watching(Player player) { return spectators.containsKey(player.getUniqueId()); }
    GameSession listeningSession(Player player) {
        GameSession own=session(player);if(own!=null)return own;
        Watch watch=spectators.get(player.getUniqueId());return watch==null?null:watch.target;
    }
    boolean usingBgmTool(Player player) { return active(player) && tools.holding(player,"bgm"); }
    void useBgm(Player player) { if(bgm!=null)bgm.use(player); }
    boolean active(Player player) { return playing(player) || watching(player); }
    boolean usingLeaveTool(Player player) { return active(player) && tools.holding(player,"leave"); }
    boolean usingMoveTool(Player player) { return playing(player) && tools.holding(player,"move"); }
    boolean usingManageTool(Player player) { return playing(player) && tools.holding(player,"manage"); }
    boolean usingSellTool(Player player) { return playing(player) && tools.holding(player,"sell"); }
    boolean usingSoundTool(Player player) { return active(player) && tools.holding(player,"sound"); }
    boolean usingSummonAlertsTool(Player player) { return active(player) && tools.holding(player,"summon_alerts"); }
    private void showSelection(Player player,GameSession session) {
        UUID selected=session.arena.selected().map(Defender::entityId).orElse(null);
        if(selected!=null && tools.holding(player,"sell"))entities.selectGlow(player,selected,true);
        else entities.selectGlow(player,selected);
    }
    void clearSelection(Player player) {
        GameSession session=session(player);
        if(session!=null && session.arena.selected().isPresent()) {
            session.arena.clearSelection(player.getUniqueId());entities.selectGlow(player,null);
        }
    }
    void speed(Player player, int value) {
        GameSession session = session(player);
        if (session == null || session.arena.ended()) throw new IllegalArgumentException("자신의 진행 중인 게임에서만 배속을 변경할 수 있습니다.");
        session.speed(value);
        Ui.sound(player,Ui.Cue.SPEED);
        player.sendMessage(Ui.text("&a게임 속도 &e" + value + "배"));
    }
    long spectatorCount(UUID sessionId) {
        return spectators.values().stream().filter(watch -> watch.target.sessionId.equals(sessionId)
                && !watch.target.arena.ended()).count();
    }
    List<SessionInfo> activeSessions() {
        return sessions.values().stream().filter(s -> !s.arena.ended()).map(s -> {
            Player owner = Bukkit.getPlayer(s.arena.owner());
            return new SessionInfo(s.sessionId, s.arena.owner(), owner == null ? s.arena.id() : owner.getName(),
                    s.arena.id(), s.campaign.round(), s.arena.enemyCount());
        }).toList();
    }
    void spectate(Player player, UUID sessionId) {
        if (playing(player)) throw new IllegalArgumentException("진행 중인 게임을 먼저 종료하세요. /mud lobby");
        GameSession target = sessions.values().stream().filter(s -> s.sessionId.equals(sessionId) && !s.arena.ended()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("이미 종료된 세션입니다. 관전 목록을 다시 열어주세요."));
        Watch previous = spectators.get(player.getUniqueId());
        Watch watch = new Watch(target, previous == null ? player.getLocation().clone() : previous.returnLocation,
                previous == null ? player.getGameMode() : previous.returnMode,
                previous == null ? player.getAllowFlight() : previous.returnAllowFlight,
                previous == null ? player.isFlying() : previous.returnFlying);
        if (player.getGameMode() == GameMode.SPECTATOR) player.setSpectatorTarget(null);
        player.closeInventory(); player.setGameMode(GameMode.ADVENTURE);
        spectators.put(player.getUniqueId(), watch);
        if (!player.teleport(viewpoint(target))) { stopWatching(player, true); throw new IllegalArgumentException("관전 위치로 이동하지 못했습니다."); }
        player.setAllowFlight(true); player.setFlying(true);
        if (previous == null) tools.giveViewer(player);
        appearance.enter(player, true);
        player.sendMessage(Ui.text("&b관전 시작 &7· F: 관전 메뉴 / 9번 침대 우클릭: 로비 복귀"));
    }
    void spectate(Player player, String playerName) {
        SessionInfo target = activeSessions().stream().filter(s -> s.playerName.equalsIgnoreCase(playerName) || s.arena.equals(playerName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("관전할 활성 세션이 없습니다."));
        spectate(player, target.sessionId);
    }
    private Location viewpoint(GameSession target) { return target.map.entrance().add(0, 8, 0); }
    boolean spectatorDestination(Player player, Location to) {
        Watch watch = spectators.get(player.getUniqueId());
        return watch == null || watch.target.map.contains(to) && to.getY() >= watch.target.map.floorY()
                && to.getY() <= watch.target.map.floorY() + 40;
    }
    private void stopWatching(Player player, boolean returnToLobby) {
        if(bgm!=null)bgm.stop(player);
        Watch watch = spectators.remove(player.getUniqueId());
        if (watch == null) return;
        tools.restore(player); appearance.leave(player);
        if (player.getGameMode() == GameMode.SPECTATOR) player.setSpectatorTarget(null);
        if (returnToLobby && lobby != null) lobby.send(player);
        else {
            player.setGameMode(watch.returnMode);
            player.setAllowFlight(watch.returnAllowFlight); player.setFlying(watch.returnFlying);
            if (returnToLobby) player.teleport(watch.returnLocation);
        }
    }
    private List<Player> viewers(Player owner, GameSession session) {
        var result = new ArrayList<Player>(); result.add(owner);
        spectators.forEach((id, watch) -> {
            if (watch.target == session) { Player viewer = Bukkit.getPlayer(id); if (viewer != null) result.add(viewer); }
        });
        return result;
    }
    boolean tracks(UUID entity) { return sessions.values().stream().anyMatch(s -> s.arena.hasEntity(entity)); }
    boolean available(String id) {
        ArenaMap map = maps.get(id);
        return map != null && map.grid().size() == settings.gridSize() && sessions.values().stream().noneMatch(s -> s.map.id().equals(id));
    }
    void start(Player player) {
        start(player,false);
    }
    void start(Player player,boolean smallForce) {
        if (playing(player)) throw new IllegalArgumentException("이미 참가 중입니다. /mud leave로 나갈 수 있습니다.");
        String id = maps.all().stream().map(ArenaMap::id).filter(this::available).findFirst().orElse(null);
        if (id == null) {
            try { id = maps.createNext(settings.gridSize()).id(); }
            catch (java.io.IOException error) { throw new IllegalStateException("Failed to save a new arena", error); }
        }
        join(player,id,smallForce);
    }
    void join(Player player, String id) {
        join(player,id,false);
    }
    private void join(Player player,String id,boolean smallForce) {
        if (playing(player)) throw new IllegalArgumentException("이미 참가 중입니다. /mud leave로 나갈 수 있습니다.");
        ArenaMap map = maps.get(id);
        if (map == null) throw new IllegalArgumentException("없는 전장입니다. /mud list로 확인하세요.");
        if (map.grid().size() != settings.gridSize()) throw new IllegalArgumentException("100라운드는 " + settings.gridSize() + "×" + settings.gridSize() + " 전장을 사용합니다. /mud create로 새 전장을 생성하세요.");
        if (sessions.values().stream().anyMatch(s -> s.map.id().equals(id))) throw new IllegalArgumentException("사용 중인 개인 전장입니다.");
        if (watching(player)) stopWatching(player, true);
        GameSession session = new GameSession(player,map,settings,smallForce);
        for (int x = (map.originX() - 6) >> 4; x <= (map.originX() + map.maxOffset()) >> 4; x++) {
            for (int z = (map.originZ() + map.minZOffset()) >> 4; z <= (map.originZ() + map.maxOffset()) >> 4; z++) {
                Chunk chunk = map.world().getChunkAt(x, z);
                chunk.addPluginChunkTicket(plugin); session.tickets.add(chunk);
            }
        }
        sessions.put(player.getUniqueId(), session);
        if (!player.teleport(map.entrance())) { leave(player); throw new IllegalArgumentException("전장으로 이동할 수 없습니다."); }
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(true); player.setFlying(true);
        tools.give(player);
        appearance.enter(player, false);
        if(achievements!=null)achievements.sessionStarted(player);
        player.sendMessage(Component.text("무한 라운드 도전! 15초 후 시작. F·1번: 관리 / 2번 좌클릭: 선택·이동 / 3번 좌클릭: 판매 대상 선택 · 우클릭: 판매", NamedTextColor.GREEN));
        if(smallForce)player.sendMessage(Ui.text("&e소수 정예 도전 &f· 배치 최대 10마리 · 초과 소환은 대기석으로 이동합니다."));
        if(!session.arena.traits().allEntries().isEmpty())
            player.sendMessage(Ui.text("&d적용 특성·패시브 &f"+String.join(" · ",session.arena.traits().allEntries().stream().map(TraitCatalog.Entry::name).toList())));
    }
    void leave(Player player) {
        if(bgm!=null)bgm.stop(player);
        entities.selectGlow(player, null);
        if (watching(player)) { stopWatching(player, true); return; }
        GameSession session = sessions.remove(player.getUniqueId());
        if (session == null) { if (lobby != null) lobby.send(player); return; }
        player.closeInventory();
        tools.restore(player);
        appearance.leave(player);
        release(session);
        if (lobby != null) lobby.send(player);
        else {
            player.setGameMode(session.returnMode);
            player.setAllowFlight(session.returnAllowFlight); player.setFlying(session.returnFlying);
            player.teleport(session.returnLocation);
        }
    }
    void disconnect(Player player) {
        if(bgm!=null)bgm.stop(player);
        entities.selectGlow(player, null);
        tools.restore(player);
        appearance.leave(player);
        stopWatching(player, false);
        GameSession session = sessions.remove(player.getUniqueId());
        if (session != null) {
            release(session);
            player.setGameMode(session.returnMode);
            player.setAllowFlight(session.returnAllowFlight); player.setFlying(session.returnFlying);
        }
    }
    private void release(GameSession session) {
        cancelPlacement(session);
        for (UUID id : List.copyOf(spectators.keySet())) {
            if (spectators.get(id).target != session) continue;
            Player viewer = Bukkit.getPlayer(id);
            if (viewer == null) spectators.remove(id);
            else {
                viewer.sendMessage(Ui.text("&e관전 중인 세션이 종료되어 로비로 돌아갑니다."));
                stopWatching(viewer, true);
            }
        }
        session.arena.units().forEach(d -> entities.remove(d.entityId()));
        session.arena.enemies().forEach(e -> entities.remove(e.entityId()));
        session.tickets.forEach(c -> c.removePluginChunkTicket(plugin));
    }
    void shutdown() {
        stopPlacementWorkers();
        for (UUID id : List.copyOf(sessions.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) leave(player);
            else release(sessions.remove(id));
        }
        for (UUID id : List.copyOf(spectators.keySet())) {
            Player player = Bukkit.getPlayer(id); if (player != null) stopWatching(player, true); else spectators.remove(id);
        }
        appearance.close(); entities.close();
    }
    void summon(Player player) {
        GameSession session = session(player);
        if (session == null) return;
        purchase(player, session, true);
    }
    private boolean purchase(Player player, GameSession session, boolean feedback) {
        SummonRoll roll = SummonRoll.draw(session.random,session.arena);
        Rarity awardedGrade=session.arena.summonRarity(roll.rarity());
        boolean autoSell = session.autoSell.contains(awardedGrade);
        Arena.Result result;
        try {
            result = session.arena.summon(player.getUniqueId(), roll,
                    (type, rarity, cell) -> autoSell ? UUID.randomUUID()
                            : cell==null?entities.spawnReserve(session.map,player.getUniqueId(),type,rarity,session.arena.reserveCount())
                            : entities.spawnDefender(session.map, player.getUniqueId(), type, rarity, cell));
        } catch (RuntimeException exception) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Defender spawn failed", exception);
            player.sendMessage(Component.text("소환에 실패했습니다. 재화는 차감하지 않았습니다.", NamedTextColor.RED));
            Ui.sound(player,Ui.Cue.ERROR);
            return false;
        }
        if (feedback) tell(player, result);
        if (result == Arena.Result.OK) {
            if(achievements!=null && !session.assisted)achievements.spent(player,session.arena.summonCost());
            if(achievements!=null && !session.assisted && session.arena.lastPurchaseMerged() && awardedGrade.ordinal()>=Rarity.LEGENDARY.ordinal())achievements.duplicate(player);
            if(achievements!=null && !session.assisted)achievements.summoned(player,roll.rarity());
            if(achievements!=null && !session.assisted && session.arena.lastPurchaseMerged())achievements.enhanced(player);
            session.arena.collectMergedEntities().forEach(entities::remove);
            Defender d=session.arena.lastSummoned();
            if(achievements!=null && !session.assisted)for(Rarity promoted:session.arena.lastPromotions()) {
                if(promoted==Rarity.TRUE_PRIMORDIAL)achievements.truePrimordialPromoted(player);
                if(promoted==Rarity.MIRACLE)achievements.miracleObtained(player);
            }
            if(awardedGrade!=roll.rarity())player.sendActionBar(Ui.text("&d특성 승급 &f"+roll.rarity().label()+" → "+awardedGrade.label()));
            if (autoSell) {
                session.arena.sellRarity(player.getUniqueId(),awardedGrade).entities().forEach(entities::remove);
                // A duplicate can promote before auto-sale; keep its real entity or remove it at the final grade.
                if(d.rarity()!=awardedGrade) {
                    entities.updateDefenderName(d);
                    if(session.autoSell.contains(d.rarity()))session.arena.sellRarity(player.getUniqueId(),d.rarity()).entities().forEach(entities::remove);
                }
            }
            else {
                entities.updateDefenderName(d);
                if(d.enhancement()>0 || d.rarity()!=awardedGrade) {
                    fusionEffect(player,session,d);
                    player.sendActionBar(Ui.text("&a"+(d.rarity()!=roll.rarity()?"승급":"합성")+" &f["+d.rarity().label()+"] "+d.label()));
                }
                if(session.autoSell.contains(d.rarity()))session.arena.sellRarity(player.getUniqueId(),d.rarity()).entities().forEach(entities::remove);
            }
            showSelection(player,session);
            session.layoutDirty = true;
            Defender summoned=session.arena.lastSummoned();
            SummonRoll announcement=roll;
            boolean traitUpgrade=false;
            if(summoned.rarity().ordinal()>=Rarity.TRUE_PRIMORDIAL.ordinal() && !session.arena.lastPromotions().isEmpty())
                announcement=new SummonRoll(summoned.type(),summoned.rarity());
            else if(awardedGrade!=roll.rarity()) {
                announcement=new SummonRoll(roll.type(),awardedGrade);traitUpgrade=true;
            }
            SummonTier tier=session.arena.summonTier();
            boolean playSound=announcement.rarity()!=Rarity.PRIMORDIAL || primordialSoundAllowed(session);
            if(SummonAnnouncement.global(announcement.rarity(),tier)) {
                if(traitUpgrade)SummonAnnouncement.traitBroadcast(player,announcement,tier,viewer->listeningSession(viewer)==session,playSound);
                else SummonAnnouncement.broadcast(player,announcement,tier,viewer->listeningSession(viewer)==session,playSound);
            } else if(feedback || roll.rarity().ordinal()<Rarity.MYTHIC.ordinal() && roll.rarity().abilityLevel()>0)
                Ui.sound(player,roll.rarity().abilityLevel()>0?Ui.Cue.RARE_SUMMON:autoSell?Ui.Cue.SELL:Ui.Cue.SUMMON);
        }
        return result == Arena.Result.OK;
    }
    private boolean primordialSoundAllowed(GameSession session) {
        if(!session.bulkBuying)return true;
        long now=System.nanoTime();
        if(session.lastPrimordialSoundNanos!=0 && now-session.lastPrimordialSoundNanos<5_000_000_000L)return false;
        session.lastPrimordialSoundNanos=now;
        return true;
    }
    private void fusionEffect(Player player,GameSession session,Defender defender) {
        if(!defender.deployed())return;
        Location at=session.map.location(defender.position()).add(0,1.8,0);
        for(Player viewer:viewers(player,session)) {
            viewer.spawnParticle(Particle.FIREWORK,at,45,.4,.6,.4,.12);
            viewer.spawnParticle(Particle.FLASH,at,1,Color.fromRGB(EntityAdapter.rarityColor(defender.rarity()).value()));
            viewer.playSound(at,"minecraft:entity.firework_rocket.blast",SoundCategory.PLAYERS,.6f,1.2f);
        }
    }
    void sell(Player player) {
        GameSession session = session(player);
        if (session == null) return;
        UUID selected = session.arena.selected().map(Defender::entityId).orElse(null);
        Arena.Result result = session.arena.sellSelected(player.getUniqueId());
        if (result == Arena.Result.OK) {
            entities.remove(selected); entities.selectGlow(player, null);
            session.layoutDirty = true; Ui.sound(player,Ui.Cue.SELL);
        }
        tell(player, result);
    }
    void sell(Player player, UUID unit) {
        GameSession session=session(player);if(session==null)return;
        Arena.Result result=session.arena.select(player.getUniqueId(),unit);
        if(result==Arena.Result.OK)sell(player);else tell(player,result);
    }
    void sellRarity(Player player, Rarity rarity) {
        GameSession session = session(player); if (session == null) return;
        Arena.BulkSale sale = session.arena.sellRarity(player.getUniqueId(), rarity);
        sale.entities().forEach(entities::remove); tell(player, sale.result());
        if (sale.result() == Arena.Result.OK) {
            session.layoutDirty |= !sale.entities().isEmpty();
            if (session.arena.selected().isEmpty()) entities.selectGlow(player, null);
            player.sendMessage(Ui.text("&a" + rarity.label() + " " + sale.entities().size() + "마리 판매 &6+" + sale.income() + "골드"));
            Ui.sound(player,Ui.Cue.SELL);
        }
    }
    void toggleAutoSell(Player player, Rarity rarity) {
        GameSession session = session(player);
        if (session == null || session.arena.ended()) return;
        if (!rarity.autoSellable()) { tell(player, Arena.Result.NOT_SELLABLE); return; }
        if (!session.autoSell.remove(rarity)) {
            session.autoSell.add(rarity);
            sellRarity(player, rarity);
        }
        Ui.sound(player, Ui.Cue.CLICK);
    }
    void toggleAutoPlacement(Player player) {
        GameSession session = session(player);
        if (session == null || session.arena.ended()) return;
        session.autoPlacement = !session.autoPlacement;
        session.layoutDirty = session.autoPlacement;
        Ui.sound(player, Ui.Cue.CLICK);
    }
    void toggleMerging(Player player) {
        GameSession session=session(player);
        if(session!=null && session.arena.toggleMerging(player.getUniqueId())==Arena.Result.OK)Ui.sound(player,Ui.Cue.CLICK);
    }
    void toggleBulkBuy(Player player) {
        GameSession session = session(player);
        if (session == null || session.arena.ended()) return;
        if (session.bulkBuying) { stopBulkBuy(player, session); return; }
        if (!canBuy(session)) {
            tell(player, session.arena.coins() < session.arena.summonCost() ? Arena.Result.INSUFFICIENT_COINS : Arena.Result.FULL);
            return;
        }
        session.bulkBuying = true; session.bulkPurchases = 0;session.lastPrimordialSoundNanos=0;
        Ui.sound(player, Ui.Cue.CLICK);
    }
    private boolean canBuy(GameSession session) {
        return !session.arena.ended() && session.arena.coins() >= session.arena.summonCost()
                && session.arena.hasSummonSpace();
    }
    private void stopBulkBuy(Player player, GameSession session) {
        session.bulkBuying = false;
        session.lastPrimordialSoundNanos=0;
        player.sendMessage(Ui.text("&a일괄구매 종료 &7· &e" + session.bulkPurchases + "회 소환"));
        Ui.sound(player, Ui.Cue.CLICK);
    }
    /** Purchase counts follow simulation time, including accelerated games. */
    void processAutomation(Player player, GameSession session) {
        if (session(player) != session || session.arena.ended()) return;
        if (session.bulkBuying) {
            for (int i = 0; i < 4 && session.bulkBuying; i++) {
                if (!canBuy(session) || !purchase(player, session, false)) {
                    stopBulkBuy(player, session); break;
                }
                session.bulkPurchases++;
            }
            if (session.bulkBuying && !canBuy(session)) stopBulkBuy(player, session);
            else if (session.bulkBuying && session.simulationTick % 4 == 0) Ui.sound(player, Ui.Cue.SUMMON);
        }
    }
    void preparePlacement(GameSession session) {
        if(!session.autoPlacement || !session.layoutDirty || session.layoutTask!=null || session.arena.ended())return;
        if(placementWorkers==null)placementWorkers=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64),Thread.ofPlatform().daemon().name("mud-placement-",0).factory());
        var units=AutoPlacement.snapshot(session.arena.units());Grid grid=session.map.grid();int limit=session.arena.deploymentLimit();
        try {
            session.layoutTask=new GameSession.LayoutTask(units,
                    placementWorkers.submit(()->new AutoPlacement(grid).arrangeSnapshot(units,limit)));
        } catch(RejectedExecutionException busy) { /* Keep dirty and retry next tick without blocking purchases. */ }
    }
    void applyPreparedPlacement(Player player,GameSession session) {
        var task=session.layoutTask;
        if(task==null || !task.result().isDone())return;
        session.layoutTask=null;
        if(session(player)!=session || session.arena.ended() || !session.autoPlacement)return;
        // A sale, summon, promotion, or manual move invalidates the worker's immutable input.
        if(!task.units().equals(AutoPlacement.snapshot(session.arena.units())))return;
        try {
            var layout=task.result().get();
            session.layoutDirty=false;
            applyLayout(player,session,layout);
        } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch(CancellationException cancelled) { /* The next tick can prepare a fresh layout. */ }
        catch(ExecutionException failed) {
            session.autoPlacement=false;
            plugin.getLogger().log(java.util.logging.Level.SEVERE,"Automatic placement failed",failed.getCause());
            player.sendMessage(Ui.text("&c자동 배치 계산에 실패해 자동 배치를 껐습니다."));
        }
    }
    private boolean applyLayout(Player player,GameSession session,Map<UUID,Cell> layout) {
        Arena arena=session.arena;
        Arena.Result result=arena.validateLayout(player.getUniqueId(),layout);
        if(result!=Arena.Result.OK){tell(player,result);return false;}
        int slot=0;
        for(Defender d:arena.units()) {
            Cell cell=layout.get(d.entityId());
            Location destination=cell==null?session.map.reserveLocation(slot++):session.map.location(cell.point());
            if(!entities.moveDefender(d.entityId(),destination)) {
                positionUnits(session);Ui.sound(player,Ui.Cue.ERROR);return false;
            }
        }
        arena.rearrange(player.getUniqueId(),layout);
        arena.units().forEach(entities::updateDefenderName);
        showSelection(player,session);
        return true;
    }
    private Location unitLocation(GameSession session,Defender d) {
        return d.deployed()?session.map.location(d.position()):session.map.reserveLocation(session.arena.reserveUnits().indexOf(d));
    }
    private boolean positionUnits(GameSession session) {
        boolean intact=true;
        for(Defender d:session.arena.activeDefenders())intact&=entities.moveDefender(d.entityId(),session.map.location(d.position()));
        int slot=0;
        for(Defender d:session.arena.reserveUnits())intact&=entities.moveDefender(d.entityId(),session.map.reserveLocation(slot++));
        return intact;
    }
    void benchSelected(Player player) {
        GameSession session=session(player);if(session==null || session.arena.ended())return;
        if(session.autoPlacement){player.sendMessage(Ui.text("&e직접 배치하려면 자동 배치를 꺼주세요."));return;}
        Defender selected=session.arena.selected().orElse(null);if(selected==null || !selected.deployed())return;
        if(session.arena.reserveCount()>=Arena.RESERVE_CAPACITY){tell(player,Arena.Result.FULL);return;}
        Map<UUID,Cell> layout=new LinkedHashMap<>();
        for(Defender d:session.arena.defenders())if(d!=selected)layout.put(d.entityId(),d.cell());
        if(applyLayout(player,session,layout)) {
            session.arena.clearSelection(player.getUniqueId());entities.selectGlow(player,null);Ui.sound(player,Ui.Cue.CLICK);
        }
    }
    void select(Player player, UUID entity) {
        GameSession session = session(player);
        if (session == null) return;
        Defender previous=session.arena.selected().orElse(null);
        if(previous!=null) {
            Defender target=session.arena.units().stream().filter(d->d.entityId().equals(entity)).findFirst().orElse(null);
            if(target==null){tell(player,Arena.Result.NOT_OWNER);return;}
            if(previous==target) {
                session.arena.clearSelection(player.getUniqueId());entities.selectGlow(player,null);Ui.sound(player,Ui.Cue.CLICK);return;
            }
            if(session.autoPlacement) {
                player.sendMessage(Ui.text("&e직접 배치하려면 자동 배치를 꺼주세요."));Ui.sound(player,Ui.Cue.ERROR);return;
            }
            Location from=unitLocation(session,previous),to=unitLocation(session,target);
            if(!entities.moveDefender(previous.entityId(),to) || !entities.moveDefender(target.entityId(),from)) {
                positionUnits(session);Ui.sound(player,Ui.Cue.ERROR);return;
            }
            Arena.Result swapped=session.arena.swapSelected(player.getUniqueId(),entity);
            if(swapped!=Arena.Result.OK){positionUnits(session);tell(player,swapped);return;}
            entities.updateDefenderName(previous);entities.updateDefenderName(target);
            entities.selectGlow(player,null);Ui.sound(player,Ui.Cue.CLICK);return;
        }
        Arena.Result result = session.arena.select(player.getUniqueId(), entity);
        tell(player, result);
        if (result == Arena.Result.OK) {
            entities.selectGlow(player,session.arena.selected().map(Defender::entityId).orElse(null));
            Ui.sound(player,Ui.Cue.CLICK);
            session.arena.selected().ifPresent(d -> player.sendActionBar(Component.text(d.rarity().label() + " " + d.label() + " · " + d.type().role().label())));
        }
    }
    void selectForSale(Player player,UUID entity) {
        GameSession session=session(player);if(session==null)return;
        if(session.arena.selected().map(d->d.entityId().equals(entity)).orElse(false)) {
            clearSelection(player);Ui.sound(player,Ui.Cue.CLICK);return;
        }
        Arena.Result result=session.arena.select(player.getUniqueId(),entity);
        tell(player,result);
        if(result==Arena.Result.OK) {
            entities.selectGlow(player,entity,true);Ui.sound(player,Ui.Cue.CLICK);
            session.arena.selected().ifPresent(d->player.sendActionBar(Component.text(d.rarity().label()+" "+d.label()+" · 우클릭으로 판매")));
        }
    }
    void move(Player player, org.bukkit.block.Block block) {
        GameSession session = session(player);
        if (session == null || session.arena.selected().isEmpty()) return;
        if (session.autoPlacement) {
            player.sendMessage(Ui.text("&e직접 이동하려면 자동 배치를 꺼주세요."));
            Ui.sound(player, Ui.Cue.ERROR); return;
        }
        Defender selected = session.arena.selected().orElseThrow();
        Cell destination=session.map.cellAt(block);
        if(!session.map.grid().contains(destination)){tell(player,Arena.Result.INVALID_CELL);return;}
        if(!selected.deployed()) {
            Map<UUID,Cell> layout=new LinkedHashMap<>();
            for(Defender d:session.arena.defenders())if(!d.cell().equals(destination))layout.put(d.entityId(),d.cell());
            layout.put(selected.entityId(),destination);
            if(applyLayout(player,session,layout)) {
                session.arena.clearSelection(player.getUniqueId());entities.selectGlow(player,null);Ui.sound(player,Ui.Cue.CLICK);
            }
            return;
        }
        Arena.Result result = session.arena.moveSelected(player.getUniqueId(), destination);
        if (result == Arena.Result.OK && !entities.moveDefender(selected.entityId(), session.map.location(selected.position()))) {
            player.sendMessage(Component.text("포탑 엔티티를 찾을 수 없어 게임을 종료합니다.", NamedTextColor.RED));
            leave(player);
            return;
        }
        if (result == Arena.Result.OK) entities.selectGlow(player, null);
        tell(player, result);
    }
    void spawnEnemies(Player player, EnemyType type, int count, boolean boss) {
        GameSession session = session(player);
        if (session == null || session.arena.ended()) throw new IllegalArgumentException("진행 중인 전장에 먼저 참가하세요.");
        session.assisted = true;
        for (int i = 0; i < count && !session.arena.ended(); i++) {
            UUID id = entities.spawnEnemy(session.map, player.getUniqueId(), type, boss);
            session.arena.addEnemy(new dev.moma.core.Enemy(id, session.arena.id(), type, 80, 2, 0, boss));
        }
    }
    void tick() {
        tick++;
        for (var entry : List.copyOf(spectators.entrySet())) {
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer == null) { spectators.remove(entry.getKey()); continue; }
            GameSession target = entry.getValue().target;
            if (sessions.get(target.arena.owner()) != target || target.arena.ended()) { stopWatching(viewer, true); continue; }
            recoverPosition(viewer, target.map, true);
            if (tick % 20 == 0) viewer.sendActionBar(Ui.text("&b관전 &7· &eR" + target.campaign.round() + " &7· &b" + target.speed() + "배 &7· /mud: 메뉴"));
        }
        for (GameSession session : List.copyOf(sessions.values())) {
            Player player = Bukkit.getPlayer(session.arena.owner());
            if (player == null) { sessions.remove(session.arena.owner()); release(session); continue; }
            recoverPosition(player, session.map, false);
            if (session.arena.ended()) {
                finish(player, session);
                continue;
            }
            applyPreparedPlacement(player,session);
            session.attackEffects.clear();
            for (int step = 0; step < session.speed(); step++) if (!step(player, session)) break;
            if (session(player) != session) continue;
            preparePlacement(session);
            session.attackEffects.retainActive(session.arena.activeDefenders());
            session.attackEffects.forEachPrimary(entities::face);
            session.attackEffects.render(session.map, viewers(player, session));
            if (session.arena.ended()) { finish(player, session); continue; }
            boolean intact = entities.advanceAll(session.arena, session.map);
            intact &= positionUnits(session);
            if (!intact) {
                player.sendMessage(Component.text("게임 엔티티가 사라져 전장을 종료했습니다.", NamedTextColor.RED));
                leave(player); continue;
            }
            if (tick % 10 == 0) {
                session.arena.selected().ifPresent(d -> player.spawnParticle(Particle.HAPPY_VILLAGER, unitLocation(session,d).add(0, 1.5, 0), 6, 0.4, 0.2, 0.4, 0));
                player.sendActionBar(Component.text("R" + session.campaign.round() + " · " + session.speed() + "배 · " + session.campaign.secondsRemaining() + "초 · " + Gold.format(session.arena.coins()) + "골드 · 적 " + session.arena.enemyCount() + "/" + session.arena.enemyLimit(), NamedTextColor.GOLD));
            }
        }
    }
    private void recoverPosition(Player player, ArenaMap map, boolean spectator) {
        Location destination = map.recovery(player.getLocation(), spectator);
        if (destination == null) return;
        boolean flying = player.isFlying();
        if (player.teleport(destination)) {
            player.setFallDistance(0);
            if (flying && player.getAllowFlight()) player.setFlying(true);
        }
    }
    private boolean step(Player player, GameSession session) {
        session.simulationTick++;
        try {
            session.campaign.beforeCombat(session.arena, spec -> entities.spawnEnemy(session.map, player.getUniqueId(), spec.type(), spec.boss()));
        } catch (RuntimeException exception) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Wave spawn failed in " + session.arena.id(), exception);
            player.sendMessage(Component.text("적 생성에 실패하여 전장을 종료합니다.", NamedTextColor.RED));
            leave(player); return false;
        }
        if (session.arena.ended()) return false;
        if (session.campaign.round() != session.announcedRound) {
            session.announcedRound = session.campaign.round();
            player.sendMessage(Component.text("라운드 " + session.announcedRound + " · " + session.campaign.wave().name(), NamedTextColor.AQUA));
            if(leaderboard!=null && !session.assisted)leaderboard.record(player,session.announcedRound);
            if(achievements!=null && !session.assisted)achievements.reached(player,session.announcedRound);
            if(achievements!=null && !session.assisted)achievements.challengesReached(player,session.arena,session.announcedRound);
        }
        processAutomation(player,session);
        session.attackEffects.beginStep();
        combat.tick(session.arena, session.simulationTick, (defender, enemy, damage) ->
                session.attackEffects.hit(defender, enemy.position(session.map.grid().route())));
        session.arena.collectDeadEnemies().forEach(entities::remove);
        int streak=session.campaign.quickClearStreak();
        session.campaign.afterCombat(session.arena);
        if(achievements!=null && !session.assisted && session.campaign.quickClearStreak()>streak)
            achievements.quickCleared(player,session.campaign.quickClearStreak());
        return !session.arena.ended();
    }
    private void finish(Player player, GameSession session) {
        String result = switch (session.arena.outcome()) {
            case VICTORY -> "100라운드 클리어!";
            case ENEMY_LIMIT -> "적 마릿수 한도에 도달했습니다. 패배!";
            case TIME_LIMIT -> "최종 정리 시간이 끝났습니다. 패배!";
            case PLAYING -> throw new IllegalStateException();
        };
        player.sendMessage(Component.text((session.assisted ? "[관리자 개입] " : "") + result
                + " · R" + session.campaign.round() + (lobby != null ? " · 로비로 돌아갑니다." : " · 전장을 종료합니다."), NamedTextColor.GOLD));
        leave(player);
    }
    static void tell(Player player, Arena.Result result) {
        if (result == Arena.Result.OK) return;
        Ui.sound(player,Ui.Cue.ERROR);
        String message = switch (result) {
            case NOT_OWNER -> "자신의 포탑만 선택할 수 있습니다.";
            case ENDED -> "이미 종료된 전장입니다.";
            case INSUFFICIENT_COINS -> "골드가 부족합니다. 소환 비용을 확인하세요.";
            case FULL -> "전장과 대기열에 빈 칸이 없습니다.";
            case INVALID_CELL -> "자기 전장의 파란 배치 칸을 선택하세요.";
            case OCCUPIED -> "이미 포탑이 있는 칸입니다.";
            case NO_SELECTION -> "먼저 자신의 포탑을 좌클릭하세요.";
            case NOT_SELLABLE -> "이 등급은 직접 선택해서 판매하세요.";
            case OK -> "";
        };
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }
}
