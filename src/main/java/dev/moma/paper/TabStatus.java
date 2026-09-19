package dev.moma.paper;

import java.util.Locale;
import org.bukkit.Bukkit;

final class TabStatus {
    static void update() {
        double tps = Bukkit.getTPS()[0];
        double target = Bukkit.getServerTickManager().getTickRate();
        String color = tps >= target * .95 ? "&a" : tps >= target * .8 ? "&e" : "&c";
        var header = Ui.text("&6&lMC Luck Defense");
        var footer = Ui.text(String.format(Locale.ROOT, "&7TPS (1분) %s%.1f &7/ %.0f  &8| &7MSPT &f%.2f", color, tps, target, Bukkit.getAverageTickTime()));
        for (var player : Bukkit.getOnlinePlayers()) player.sendPlayerListHeaderAndFooter(header, footer);
    }
}
