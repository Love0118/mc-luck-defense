package dev.moma.paper;

import java.lang.reflect.Method;
import org.bukkit.*;
import org.bukkit.entity.*;

/** Paper 26.3 adapter: bypass shulker block snapping, keeping bounds and section tracking in sync. */
final class ShulkerMotion {
    private ShulkerMotion() {}
    private record Access(Method handle,Method bounds,Method rawPosition,Method setBounds) {}
    private static final ClassValue<Access> ACCESS=new ClassValue<>() {
        @Override protected Access computeValue(Class<?> craftType) {
            try {
                Method handle=craftType.getMethod("getHandle");
                Class<?> entity=Class.forName("net.minecraft.world.entity.Entity");
                Method bounds=entity.getMethod("getBoundingBoxAt",double.class,double.class,double.class);
                return new Access(handle,bounds,entity.getMethod("setPosRaw",double.class,double.class,double.class),
                        entity.getMethod("setBoundingBox",bounds.getReturnType()));
            } catch(ReflectiveOperationException error) {throw new IllegalStateException("Paper 26.3 shulker motion unavailable",error);}
        }
    };
    static boolean move(Entity entity,Location destination) {
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Shulker motion requires server thread");
        destination.checkFinite();
        if(!entity.getWorld().equals(destination.getWorld()) || !destination.getWorld().isChunkLoaded(destination.getBlockX()>>4,destination.getBlockZ()>>4))return false;
        try {
            Access access=ACCESS.get(entity.getClass());Object handle=access.handle.invoke(entity);
            Object bounds=access.bounds.invoke(handle,destination.getX(),destination.getY(),destination.getZ());
            access.rawPosition.invoke(handle,destination.getX(),destination.getY(),destination.getZ());
            access.setBounds.invoke(handle,bounds);
            entity.setRotation(destination.getYaw(),destination.getPitch());
            ((LivingEntity)entity).setBodyYaw(destination.getYaw());
            return true;
        } catch(ReflectiveOperationException error) {throw new IllegalStateException("Shulker motion failed",error);}
    }
}
