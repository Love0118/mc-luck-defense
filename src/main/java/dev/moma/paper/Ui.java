package dev.moma.paper;

import java.util.Arrays;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class Ui {
    enum Cue {
        OPEN("minecraft:block.barrel.open", .35f, 1.3f),
        CLICK("minecraft:ui.button.click", .4f, 1.1f),
        SUMMON("minecraft:block.note_block.pling", .6f, 1.2f),
        RARE_SUMMON("minecraft:block.amethyst_block.chime", .8f, 1.2f),
        SELL("minecraft:entity.experience_orb.pickup", .5f, 1.3f),
        SPEED("minecraft:block.note_block.hat", .5f, 1.3f),
        ERROR("minecraft:block.note_block.bass", .6f, .6f);

        final String sound;
        final float volume, pitch;
        Cue(String sound,float volume,float pitch) { this.sound=sound;this.volume=volume;this.pitch=pitch; }
    }
    private Ui() {}
    static void sound(Player player,Cue cue) {
        player.playSound(player.getLocation(),cue.sound,SoundCategory.MASTER,cue.volume,cue.pitch);
    }
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
