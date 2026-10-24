package dev.moma.mining;

import java.io.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Receipt is saved with vanilla playerdata before releasing the durable inventory vault. */
final class MiningRecovery {
    private final MiningStore store;
    private final NamespacedKey receipt;
    MiningRecovery(JavaPlugin plugin,MiningStore store){this.store=store;receipt=new NamespacedKey(plugin,"mining_restored");}
    void capture(Player player)throws IOException {
        var p=store.profile(player.getUniqueId());if(p.recovery()!=null)throw new IOException("Previous mining inventory is still in recovery");
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)) {
            out.writeInt(1);var l=player.getLocation();out.writeUTF(l.getWorld().getUID().toString());out.writeUTF(l.getWorld().getName());
            out.writeDouble(l.getX());out.writeDouble(l.getY());out.writeDouble(l.getZ());out.writeFloat(l.getYaw());out.writeFloat(l.getPitch());
            out.writeUTF(player.getGameMode().name());out.writeBoolean(player.getAllowFlight());out.writeBoolean(player.isFlying());out.writeInt(player.getInventory().getHeldItemSlot());
            write(out,ItemStack.serializeItemsAsBytes(player.getInventory().getContents()));
            write(out,ItemStack.serializeItemsAsBytes(new ItemStack[]{player.getItemOnCursor()}));
        }
        store.save(player.getUniqueId(),p.recovery(new MiningStore.Recovery(UUID.randomUUID(),bytes.toByteArray())));
        // Snapshot is durable before any inventory is cleared or player mode is changed.
        player.saveData();
    }
    boolean restore(Player player,boolean teleport)throws IOException {
        UUID id=player.getUniqueId();var p=store.profile(id);var recovery=p.recovery();if(recovery==null)return false;
        var data=player.getPersistentDataContainer();String token=recovery.token().toString();
        if(!token.equals(data.get(receipt,PersistentDataType.STRING))) {
            try(var in=new DataInputStream(new ByteArrayInputStream(recovery.data()))) {
                if(in.readInt()!=1)throw new IOException("Unknown recovery version");
                UUID worldId=UUID.fromString(in.readUTF());String name=in.readUTF();
                World world=Bukkit.getWorld(worldId);if(world==null)world=Bukkit.getWorld(name);
                double x=in.readDouble(),y=in.readDouble(),z=in.readDouble();float yaw=in.readFloat(),pitch=in.readFloat();
                GameMode mode=GameMode.valueOf(in.readUTF());boolean flight=in.readBoolean(),flying=in.readBoolean();int held=in.readInt();
                var inventory=ItemStack.deserializeItemsFromBytes(read(in));var cursor=ItemStack.deserializeItemsFromBytes(read(in));
                if(in.available()!=0 || inventory.length!=player.getInventory().getSize() || cursor.length!=1 || held<0 || held>8)throw new IOException("Invalid recovery inventory");
                Location location=world==null?null:new Location(world,x,y,z,yaw,pitch);if(location!=null)location.checkFinite();
                if(teleport && location==null)throw new IOException("Original mining entry world is unavailable");
                player.closeInventory();player.getInventory().setContents(inventory);player.setItemOnCursor(cursor[0]);
                player.getInventory().setHeldItemSlot(held);player.setGameMode(mode);player.setAllowFlight(flight);player.setFlying(flight&&flying);
                if(teleport && !player.teleport(location))throw new IOException("Mining recovery teleport was refused");
                data.set(receipt,PersistentDataType.STRING,token);
                try { player.saveData(); }
                catch(RuntimeException failure){data.remove(receipt);throw failure;}
            } catch(IllegalArgumentException e){throw new IOException("Invalid mining recovery snapshot",e);}
        }
        // A crash after saveData and before this write replays only cleanup, never inventory replacement.
        store.save(id,p.recovery(null));data.remove(receipt);player.saveData();return true;
    }
    private static void write(DataOutput out,byte[] data)throws IOException{out.writeInt(data.length);out.write(data);}
    private static byte[] read(DataInputStream in)throws IOException{
        int size=in.readInt();if(size<1 || size>16_777_216)throw new IOException("Invalid item payload");
        byte[] bytes=in.readNBytes(size);if(bytes.length!=size)throw new EOFException("Incomplete recovery inventory");return bytes;
    }
}
