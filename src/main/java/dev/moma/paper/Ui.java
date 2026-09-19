package dev.moma.paper;

import java.util.Arrays;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

final class Ui {
    private Ui() {}
    static Component text(String value) {
        return plain(LegacyComponentSerializer.legacyAmpersand().deserialize(value.replace('§', '&')));
    }
    private static Component plain(Component value) {
        return value.decoration(TextDecoration.ITALIC, false).children(value.children().stream().map(Ui::plain).toList());
    }
    static ItemStack item(Material material, String title, String... lore) {
        var item = new ItemStack(material); var meta = item.getItemMeta();
        meta.displayName(text(title)); meta.lore(Arrays.stream(lore).map(Ui::text).toList());
        item.setItemMeta(meta); return item;
    }
}
