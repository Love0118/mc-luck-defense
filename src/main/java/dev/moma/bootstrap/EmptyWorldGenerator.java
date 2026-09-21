package dev.moma.bootstrap;

import org.bukkit.generator.ChunkGenerator;

/** Worlds retain their generator for their lifetime, so it belongs to the permanent host. */
public final class EmptyWorldGenerator extends ChunkGenerator {
    @Override public boolean shouldGenerateNoise(){return false;}
    @Override public boolean shouldGenerateSurface(){return false;}
    @Override public boolean shouldGenerateCaves(){return false;}
    @Override public boolean shouldGenerateDecorations(){return false;}
    @Override public boolean shouldGenerateMobs(){return false;}
    @Override public boolean shouldGenerateStructures(){return false;}
}
