package dev.moma.bgm;

import java.util.UUID;

public record Track(String id, UUID uploader, String uploaderName, String title, String youtubeUrl,
                    String deliveryUrl, String sha1, double seconds) {
    public String sound() { return "mud_bgm:track_" + id; }
    public UUID packId() { return UUID.nameUUIDFromBytes((id + sha1).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    public boolean ready() { return !deliveryUrl.isEmpty() && sha1.length() == 40; }
}
