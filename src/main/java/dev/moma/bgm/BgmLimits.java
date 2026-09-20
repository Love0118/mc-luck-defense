package dev.moma.bgm;

/** Song policy shared by the menu, storage and media pipeline. */
public record BgmLimits(int uploadsPerPlayer, int playlistTracks, int durationSeconds, int fileSizeMb) {
    public static final BgmLimits DEFAULT = new BgmLimits(3, 3, 300, 25);
    public BgmLimits {
        if (uploadsPerPlayer < 1 || playlistTracks < 1 || durationSeconds < 1 || fileSizeMb < 1)
            throw new IllegalArgumentException("BGM limits must be positive integers");
    }
    public long fileSizeBytes() { return fileSizeMb * 1024L * 1024L; }
}
