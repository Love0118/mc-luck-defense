package dev.moma.paper;

import dev.moma.bootstrap.RuntimeController;
import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;

/** Permanent Paper entrypoint. Game generations own their own classloaders and lifecycle. */
public final class MomaPlugin extends JavaPlugin {
    private RuntimeController runtime;
    private Object games;
    @Override public String namespace(){return "momadefense";}
    public void runtimeChanged(Object games){this.games=games;}
    public RuntimeController runtime(){return runtime;}
    @Override public void onEnable() {
        try {
            runtime=new RuntimeController(this,getFile().toPath(),getServer().getUpdateFolderFile().toPath());
            runtime.start();
            var command=Objects.requireNonNull(getCommand("mud"));command.setExecutor(runtime);command.setTabCompleter(runtime);
            getLogger().info("MC Luck Defense enabled: "+runtime.status());
        } catch(Exception|LinkageError error) {
            getLogger().log(java.util.logging.Level.SEVERE,"MC Luck Defense runtime failed to start",error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    @Override public void onDisable() {
        if(runtime!=null)try{runtime.close();}catch(Exception error){getLogger().log(java.util.logging.Level.SEVERE,"Runtime shutdown failed",error);}
        games=null;
    }
}
