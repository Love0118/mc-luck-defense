package dev.moma.paper;

import dev.moma.core.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.*;
import java.util.*;

final class GameService {
    private final MomaPlugin plugin;
    private final ArenaMaps maps;
    private final CampaignRules settings;
    final EntityAdapter entities;
    private final Map<UUID, GameSession> sessions = new LinkedHashMap<>();
    private final CombatEngine combat = new CombatEngine();
    private long tick;

    GameService(MomaPlugin plugin, ArenaMaps maps, CampaignRules settings) {
        this.plugin = plugin; this.maps = maps; this.settings = settings; entities = new EntityAdapter(plugin);
    }
    GameSession session(Player player) { return sessions.get(player.getUniqueId()); }
    boolean playing(Player player) { return session(player) != null; }
    boolean tracks(UUID entity) { return sessions.values().stream().anyMatch(s -> s.arena.hasEntity(entity)); }
    void join(Player player, String id) {
        if (playing(player)) throw new IllegalArgumentException("이미 참가 중입니다. /mud leave로 나갈 수 있습니다.");
        ArenaMap map = maps.get(id);
        if (map == null) throw new IllegalArgumentException("없는 전장입니다. /mud list로 확인하세요.");
        if (map.grid().size() != settings.gridSize()) throw new IllegalArgumentException("100라운드는 " + settings.gridSize() + "×" + settings.gridSize() + " 전장을 사용합니다. /mud create로 새 전장을 생성하세요.");
        if (sessions.values().stream().anyMatch(s -> s.map.id().equals(id))) throw new IllegalArgumentException("사용 중인 개인 전장입니다.");
        GameSession session = new GameSession(player, map, settings);
        for (int x = (map.originX() - 6) >> 4; x <= (map.originX() + map.maxOffset()) >> 4; x++) {
            for (int z = (map.originZ() - 6) >> 4; z <= (map.originZ() + map.maxOffset()) >> 4; z++) {
                Chunk chunk = map.world().getChunkAt(x, z);
                chunk.addPluginChunkTicket(plugin); session.tickets.add(chunk);
            }
        }
        sessions.put(player.getUniqueId(), session);
        if (!player.teleport(map.entrance())) { leave(player); throw new IllegalArgumentException("전장으로 이동할 수 없습니다."); }
        player.setGameMode(GameMode.ADVENTURE);
        player.sendMessage(Component.text("100라운드 도전! 15초 후 적이 출현합니다. F: 소환·판매 / 좌클릭: 선택·이동", NamedTextColor.GREEN));
    }
    void leave(Player player) {
        GameSession session = sessions.remove(player.getUniqueId());
        if (session == null) return;
        player.closeInventory();
        session.arena.defenders().forEach(d -> entities.remove(d.entityId()));
        session.arena.enemies().forEach(e -> entities.remove(e.entityId()));
        session.tickets.forEach(c -> c.removePluginChunkTicket(plugin));
        player.setGameMode(session.returnMode);
        player.teleport(session.returnLocation);
    }
    void shutdown() {
        for (UUID id : List.copyOf(sessions.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) leave(player);
        }
    }
    void summon(Player player) {
        GameSession session = session(player);
        if (session == null) return;
        SummonRoll roll = SummonRoll.draw(session.random);
        Arena.Result result;
        try {
            result = session.arena.summon(player.getUniqueId(), roll,
                    (type, rarity, cell) -> entities.spawnDefender(session.map, player.getUniqueId(), type, rarity, cell));
        } catch (RuntimeException exception) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Defender spawn failed", exception);
            player.sendMessage(Component.text("소환에 실패했습니다. 재화는 차감하지 않았습니다.", NamedTextColor.RED));
            return;
        }
        tell(player, result);
        if (result == Arena.Result.OK && roll.rarity().abilityLevel() > 0)
            Bukkit.broadcast(Component.text(player.getName() + " 님이 [" + roll.rarity().label() + "] " + roll.type().label() + " 획득!", EntityAdapter.rarityColor(roll.rarity())));
    }
    void sell(Player player) {
        GameSession session = session(player);
        if (session == null) return;
        UUID selected = session.arena.selected().map(Defender::entityId).orElse(null);
        Arena.Result result = session.arena.sellSelected(player.getUniqueId());
        if (result == Arena.Result.OK) entities.remove(selected);
        tell(player, result);
    }
    void select(Player player, UUID entity) {
        GameSession session = session(player);
        if (session == null) return;
        Arena.Result result = session.arena.select(player.getUniqueId(), entity);
        tell(player, result);
        if (result == Arena.Result.OK) session.arena.selected().ifPresent(d -> player.sendActionBar(Component.text(d.rarity().label() + " " + d.type().label() + " · " + d.type().role().label())));
    }
    void move(Player player, org.bukkit.block.Block block) {
        GameSession session = session(player);
        if (session == null || session.arena.selected().isEmpty()) return;
        Defender selected = session.arena.selected().orElseThrow();
        Arena.Result result = session.arena.moveSelected(player.getUniqueId(), session.map.cellAt(block));
        if (result == Arena.Result.OK && !entities.move(selected.entityId(), session.map.location(selected.position()))) {
            player.sendMessage(Component.text("포탑 엔티티를 찾을 수 없어 게임을 종료합니다.", NamedTextColor.RED));
            leave(player);
            return;
        }
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
        for (GameSession session : List.copyOf(sessions.values())) {
            Player player = Bukkit.getPlayer(session.arena.owner());
            if (player == null) continue;
            if (!session.map.contains(player.getLocation()) || player.getY() < session.map.floorY()) player.teleport(session.map.entrance());
            if (session.arena.ended()) {
                if (!session.resultShown) {
                    session.resultShown = true;
                    player.closeInventory();
                    String result = switch (session.arena.outcome()) {
                        case VICTORY -> "100라운드 클리어!";
                        case ENEMY_LIMIT -> "적 마릿수 한도에 도달했습니다. 패배!";
                        case TIME_LIMIT -> "최종 정리 시간이 끝났습니다. 패배!";
                        case PLAYING -> throw new IllegalStateException();
                    };
                    player.sendMessage(Component.text((session.assisted ? "[관리자 개입] " : "") + result + " /mud leave로 나가세요.", NamedTextColor.GOLD));
                }
                continue;
            }
            try {
                session.campaign.beforeCombat(session.arena, spec -> entities.spawnEnemy(session.map, player.getUniqueId(), spec.type(), spec.boss()));
            } catch (RuntimeException exception) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Wave spawn failed in " + session.arena.id(), exception);
                player.sendMessage(Component.text("적 생성에 실패하여 전장을 종료합니다.", NamedTextColor.RED));
                leave(player); continue;
            }
            if (session.campaign.round() != session.announcedRound) {
                session.announcedRound = session.campaign.round();
                player.sendMessage(Component.text("라운드 " + session.announcedRound + "/100 · " + session.campaign.wave().name(), NamedTextColor.AQUA));
            }
            List<CombatEngine.Hit> hits = combat.tick(session.arena, tick);
            for (CombatEngine.Hit hit : hits) {
                Entity target = Bukkit.getEntity(hit.enemy());
                if (target != null) player.spawnParticle(Particle.CRIT, target.getLocation().add(0, 0.7, 0), 2, 0.1, 0.1, 0.1, 0);
            }
            session.arena.collectDeadEnemies().forEach(entities::remove);
            session.campaign.afterCombat(session.arena);
            boolean intact = true;
            for (dev.moma.core.Enemy enemy : session.arena.enemies())
                intact &= entities.move(enemy.entityId(), session.map.location(enemy.position(session.map.grid().route())));
            // Anchor unusual vanilla bodies such as shulkers as well as ordinary mobs.
            for (Defender defender : session.arena.defenders())
                intact &= entities.move(defender.entityId(), session.map.location(defender.position()));
            if (!intact) {
                player.sendMessage(Component.text("게임 엔티티가 사라져 전장을 종료했습니다.", NamedTextColor.RED));
                leave(player); continue;
            }
            if (tick % 10 == 0) {
                session.arena.selected().ifPresent(d -> player.spawnParticle(Particle.HAPPY_VILLAGER, session.map.location(d.position()).add(0, 1.5, 0), 6, 0.4, 0.2, 0.4, 0));
                player.sendActionBar(Component.text("R" + session.campaign.round() + "/100 · " + (session.campaign.cleanup() ? "정리 " : "") + session.campaign.secondsRemaining() + "초 · " + session.arena.coins() + "원 · 적 " + session.arena.enemyCount() + "/" + session.arena.enemyLimit(), NamedTextColor.GOLD));
            }
        }
    }
    static void tell(Player player, Arena.Result result) {
        if (result == Arena.Result.OK) return;
        String message = switch (result) {
            case NOT_OWNER -> "자신의 포탑만 선택할 수 있습니다.";
            case ENDED -> "이미 종료된 전장입니다.";
            case INSUFFICIENT_COINS -> "재화가 부족합니다. 소환에는 10원이 필요합니다.";
            case FULL -> "빈 배치 칸이 없습니다.";
            case INVALID_CELL -> "자기 전장의 파란 배치 칸을 선택하세요.";
            case OCCUPIED -> "이미 포탑이 있는 칸입니다.";
            case NO_SELECTION -> "먼저 자신의 포탑을 좌클릭하세요.";
            case NOT_SELLABLE -> "전설 이상은 판매할 수 없습니다.";
            case OK -> "";
        };
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }
}
