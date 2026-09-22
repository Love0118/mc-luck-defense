package dev.moma.bgm;

import java.util.UUID;

public record Track(String id, UUID uploader, String uploaderName, String title, String youtubeUrl,
                    String deliveryUrl, String sha1, double seconds, int packVersion) implements java.io.Serializable {
    public static final int CURRENT_PACK_VERSION = 3;
    public Track(String id, UUID uploader, String uploaderName, String title, String youtubeUrl,
                 String deliveryUrl, String sha1, double seconds) {
        this(id,uploader,uploaderName,title,youtubeUrl,deliveryUrl,sha1,seconds,CURRENT_PACK_VERSION);
    }
    public String sound() { return "mud_bgm:track_" + id; }
    public String segmentSound(int segment) { return sound()+"_part_"+segment; }
    public UUID packId() { return UUID.nameUUIDFromBytes((id + sha1).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    public boolean ready() { return !deliveryUrl.isEmpty() && sha1.length() == 40; }
    public boolean synchronizedReady() { return ready() && packVersion == CURRENT_PACK_VERSION; }
}
