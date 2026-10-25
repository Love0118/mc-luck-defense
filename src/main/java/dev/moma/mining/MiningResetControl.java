package dev.moma.mining;

import dev.noqui.api.MiningControlService;
import java.io.IOException;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** Loaded only when the optional Noqui API is available. */
final class MiningResetControl implements MiningControlService {
    private final MiningResetCycle cycle;
    private final JavaPlugin plugin;
    private MiningResetControl(MiningResetCycle cycle,JavaPlugin plugin){this.cycle=cycle;this.plugin=plugin;}
    static void register(JavaPlugin plugin,MiningWorld world){
        var cycle=MiningResetCycle.current(world);var control=new MiningResetControl(cycle,plugin);
        var services=Bukkit.getServicesManager();services.register(MiningControlService.class,control,plugin,ServicePriority.Normal);
        cycle.onClose(()->services.unregister(MiningControlService.class,control));
    }
    @Override public Status status(){var value=cycle.snapshot();return new Status(value.remainingOre(),value.initialOre(),value.threshold(),value.remainingMillis(),value.ready());}
    @Override public void reset(){
        try{cycle.requestReset();}
        catch(IOException error){plugin.getLogger().log(java.util.logging.Level.SEVERE,"Mining reset failed",error);throw new IllegalArgumentException("광산을 저장하지 못해 리셋하지 못했습니다.",error);}
    }
}
