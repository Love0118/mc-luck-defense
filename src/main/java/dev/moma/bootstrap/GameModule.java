package dev.moma.bootstrap;

import dev.moma.paper.MomaPlugin;
import org.bukkit.command.*;

/** Stable host boundary. State uses checked bytes; only diagnostics expose an opaque game reference. */
public interface GameModule extends CommandExecutor,TabCompleter {
    void prepare(MomaPlugin host,byte[] snapshot)throws Exception;
    void activate(boolean initialBoot)throws Exception;
    void checkReloadReady();
    byte[] snapshot()throws Exception;
    String fingerprint();
    boolean hasSessions();
    void suspend()throws Exception;
    void discard();
    void shutdown();
    Object diagnosticState();
}
