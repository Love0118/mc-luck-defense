package dev.moma.paper;

import java.util.Locale;
import org.bukkit.Bukkit;

final class TabStatus {
    private static final net.kyori.adventure.text.Component HEADER = Ui.text(
            "&#4056DE마&#3E63DD인&#3C71DC크&#3A7EDB래&#388CD9프&#3699D8트 &#32B4D6운&#30C1D5빨 &#2CDCD2디&#2AEAD1펜&#28F7D0스");
    static void update() {
        double tps = Bukkit.getTPS()[0];
        double target = Bukkit.getServerTickManager().getTickRate();
        String color = tps >= target * .95 ? "&a" : tps >= target * .8 ? "&e" : "&c";
        var footer = Ui.text(String.format(Locale.ROOT, "&7TPS (1분) %s%.1f &7/ %.0f  &8| &7MSPT &f%.2f", color, tps, target, Bukkit.getAverageTickTime()));
        for (var player : Bukkit.getOnlinePlayers()) player.sendPlayerListHeaderAndFooter(HEADER, footer);
    }
}
