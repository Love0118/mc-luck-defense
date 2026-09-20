package dev.moma.paper;

import dev.moma.core.EnemyType;
import dev.moma.core.CampaignRules;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.io.IOException;
import java.util.*;

final class MomaCommand implements TabExecutor {
    private final ArenaMaps maps;
    private final GameService games;
    private final CampaignRules settings;
    private final LobbyMenu menu;
    MomaCommand(ArenaMaps maps, GameService games, CampaignRules settings) { this(maps, games, settings, null); }
    MomaCommand(ArenaMaps maps, GameService games, CampaignRules settings, LobbyMenu menu) { this.maps = maps; this.games = games; this.settings = settings; this.menu = menu; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player && menu != null && !games.playing(player)) { menu.open(player); return true; }
            sender.sendMessage(Ui.text("&e/mud start | list | join [전장] | spectate <플레이어> | speed <1|2|4|8> | lobby | leave"));
            if (sender.hasPermission("moma.admin")) sender.sendMessage(Component.text("관리: /mud create <전장> | spawn <종> [수] [boss] | coins <금액>"));
            return true;
        }
        try {
            String action = args[0].toLowerCase(Locale.ROOT);
            if (Set.of("create", "spawn", "coins").contains(action) && !sender.hasPermission("moma.admin")) throw new IllegalArgumentException("관리자 권한이 필요합니다.");
            if (!sender.hasPermission("moma.play") && !Set.of("leave", "lobby").contains(action)) throw new IllegalArgumentException("참가 권한이 없습니다.");
            switch (action) {
                case "bgm" -> {
                    if(games.bgm==null)throw new IllegalArgumentException("BGM 초기화에 실패했습니다. 서버 로그를 확인하세요.");
                    if(args.length>1 && args[1].equalsIgnoreCase("auth"))games.bgm.auth(player(sender));
                    else games.bgm.use(player(sender));
                }
                case "list" -> sender.sendMessage(Component.text("전장: " + String.join(", ", maps.all().stream().map(ArenaMap::id).toList())));
                case "create" -> {
                    require(args, 2, "/mud create <전장>");
                    maps.create(args[1], settings.gridSize());
                    sender.sendMessage(Component.text("무한 라운드 전장 생성 완료: " + args[1] + " · /mud join " + args[1], NamedTextColor.GREEN));
                }
                case "start" -> games.start(player(sender));
                case "spectate" -> { require(args, 2, "/mud spectate <플레이어>"); games.spectate(player(sender), args[1]); }
                case "speed" -> { require(args, 2, "/mud speed <1|2|4|8>"); games.speed(player(sender), Integer.parseInt(args[1])); }
                case "join" -> { if (args.length == 1) games.start(player(sender)); else games.join(player(sender), args[1]); }
                case "leave", "lobby" -> games.leave(player(sender));
                case "spawn" -> {
                    require(args, 2, "/mud spawn <ZOMBIE|HUSK|DROWNED|SPIDER|SLIME|MAGMA_CUBE> [1~100] [boss]");
                    EnemyType type;
                    try { type = EnemyType.valueOf(args[1].toUpperCase(Locale.ROOT)); }
                    catch (IllegalArgumentException exception) { throw new IllegalArgumentException("지원하지 않는 적 종류입니다."); }
                    int count = args.length >= 3 ? Integer.parseInt(args[2]) : 1;
                    if (count < 1 || count > 100) throw new IllegalArgumentException("적 수는 1~100입니다.");
                    if (args.length >= 4 && !args[3].equalsIgnoreCase("boss")) throw new IllegalArgumentException("마지막 옵션은 boss입니다.");
                    games.spawnEnemies(player(sender), type, count, args.length >= 4);
                }
                case "coins" -> {
                    require(args, 2, "/mud coins <1~1000000>");
                    long amount = Long.parseLong(args[1]);
                    if (amount < 1 || amount > 1_000_000) throw new IllegalArgumentException("금액은 1~1000000입니다.");
                    GameSession session = games.session(player(sender));
                    if (session == null || session.arena.ended()) throw new IllegalArgumentException("진행 중인 전장에 먼저 참가하세요.");
                    session.arena.credit(amount);
                    session.assisted = true;
                    sender.sendMessage(Component.text("골드 지급: " + amount));
                }
                default -> throw new IllegalArgumentException("알 수 없는 명령입니다. /mud로 도움말을 확인하세요.");
            }
        } catch (NumberFormatException exception) {
            sender.sendMessage(Component.text("올바른 정수를 입력하세요.", NamedTextColor.RED));
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(Component.text(exception.getMessage(), NamedTextColor.RED));
        } catch (IOException exception) {
            sender.sendMessage(Component.text("전장 설정을 저장하지 못했습니다.", NamedTextColor.RED));
        }
        return true;
    }
    private void require(String[] args, int size, String usage) { if (args.length < size) throw new IllegalArgumentException(usage); }
    private Player player(CommandSender sender) {
        if (!(sender instanceof Player player)) throw new IllegalArgumentException("플레이어만 사용할 수 있습니다.");
        return player;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> candidates = List.of();
        if (args.length == 1) candidates = sender.hasPermission("moma.admin") ? List.of("start", "join", "spectate", "speed", "lobby", "leave", "list", "create", "spawn", "coins", "bgm") : List.of("start", "join", "spectate", "speed", "lobby", "leave", "list", "bgm");
        if (args.length == 2 && args[0].equalsIgnoreCase("bgm") && sender.hasPermission("moma.admin")) candidates=List.of("auth");
        if (args.length == 2 && args[0].equalsIgnoreCase("speed")) candidates = List.of("1", "2", "4", "8");
        if (args.length == 2 && args[0].equalsIgnoreCase("spectate")) candidates = games.activeSessions().stream().map(GameService.SessionInfo::playerName).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("join")) candidates = maps.all().stream().map(ArenaMap::id).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("spawn") && sender.hasPermission("moma.admin")) candidates = Arrays.stream(EnemyType.values()).map(Enum::name).toList();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return candidates.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
