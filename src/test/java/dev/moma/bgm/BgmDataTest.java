package dev.moma.bgm;

import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BgmDataTest {
    @TempDir Path temp;
    @Test void fiveMinuteBoundaryIsInclusiveAndInvalidDurationsAreRejectedBeforeProcessing()throws Exception {
        for(double seconds:new double[]{.1,299.99,300}) {
            var info=new com.google.gson.JsonObject();info.addProperty("duration",seconds);
            assertEquals(seconds,BgmMedia.checkedDuration(info));
        }
        for(double seconds:new double[]{300.001,301,600,0,-1,Double.NaN,Double.POSITIVE_INFINITY}) {
            var info=new com.google.gson.JsonObject();info.addProperty("duration",seconds);
            assertThrows(BgmMedia.RejectedAudio.class,()->BgmMedia.checkedDuration(info));
        }
        assertThrows(BgmMedia.RejectedAudio.class,()->BgmMedia.checkedDuration(new com.google.gson.JsonObject()));
        var live=new com.google.gson.JsonObject();live.addProperty("duration",30);live.addProperty("is_live",true);
        assertThrows(BgmMedia.RejectedAudio.class,()->BgmMedia.checkedDuration(live));
        var media=new BgmMedia("must-not-launch","must-not-launch");
        assertThrows(BgmMedia.RejectedAudio.class,()->media.convert(temp.resolve("absent.ogg"),temp,"long",301));
        assertThrows(BgmMedia.RejectedAudio.class,()->media.synchronizedPack("long",temp.resolve("absent.ogg"),301,temp));
    }
    @Test void youtubeInputsAreCanonicalAndRejectOtherHostsAndPlaylistOnlyLinks() {
        for(String url:List.of("https://youtu.be/abcdefghijk?t=2","https://www.youtube.com/watch?v=abcdefghijk&list=PLtest","https://youtube.com/shorts/abcdefghijk","https://youtube.com/live/abcdefghijk"))
            assertEquals("https://www.youtube.com/watch?v=abcdefghijk",BgmMedia.youtube(url));
        for(String url:List.of("http://youtu.be/abcdefghijk","https://youtube.com.evil.test/watch?v=abcdefghijk","file:///etc/passwd","https://youtube.com/playlist?list=123","https://user@youtube.com/watch?v=abcdefghijk","https://youtube.com:443/watch?v=abcdefghijk"))
            assertThrows(IllegalArgumentException.class,()->BgmMedia.youtube(url));
    }
    @Test void dropboxPreservesAccessKeyAndReplacesDownloadFlag() {
        assertEquals("https://www.dropbox.com/scl/fi/abc/pack.zip?rlkey=xyz&dl=1",DropboxBgm.directLink("https://www.dropbox.com/scl/fi/abc/pack.zip?rlkey=xyz&dl=0"));
        assertEquals("https://www.dropbox.com/s/abc/pack.zip?dl=1",DropboxBgm.directLink("https://www.dropbox.com/s/abc/pack.zip?raw=1"));
        assertThrows(IllegalArgumentException.class,()->DropboxBgm.directLink("https://evil.test/a.zip"));
    }
    @Test void sqlitePersistsOwnershipSourceAndRepairedDeliveryAcrossRestart()throws Exception {
        UUID owner=UUID.randomUUID();Path db=temp.resolve("bgm.db");
        Track track=new Track("abc",owner,"작성자","노래","https://youtu.be/abcdefghijk","https://www.dropbox.com/a?dl=1","a".repeat(40),12);
        try(var store=new BgmStore(db)){store.save(track);assertEquals(List.of(track),store.list());}
        try(var store=new BgmStore(db)) {
            assertEquals(List.of(track),store.list());
            var repaired=new Track("abc",owner,"작성자","노래","https://youtu.be/abcdefghijk","https://www.dropbox.com/b?dl=1","b".repeat(40),13);
            store.save(repaired);assertEquals(List.of(repaired),store.list());assertNotEquals(track.packId(),repaired.packId());
        }
    }
    @Test void packContainsStreamingVorbisAssetAndOnlyDeletesWorkspace()throws Exception {
        Path work=Files.createDirectory(temp.resolve("work")), original=temp.resolve("original.ogg");
        byte[] audio;try(var in=getClass().getResourceAsStream("/bgm/default.ogg")){audio=in.readAllBytes();}
        Files.write(original,audio);Path archive=BgmMedia.pack("default",original,work);
        assertEquals(40,BgmMedia.sha1(archive).length());
        try(var zip=new ZipFile(archive.toFile())) {
            assertEquals(3,zip.size());
            var sounds=JsonParser.parseString(new String(zip.getInputStream(zip.getEntry("assets/mud_bgm/sounds.json")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(sounds.getAsJsonObject("track_default_part_0").getAsJsonArray("sounds").get(0).getAsJsonObject().get("stream").getAsBoolean());
            assertArrayEquals(audio,zip.getInputStream(zip.getEntry("assets/mud_bgm/sounds/tracks/default_part_0.ogg")).readAllBytes());
        }
        assertTrue(new String(audio,java.nio.charset.StandardCharsets.ISO_8859_1).contains("vorbis"));
        BgmMedia.cleanup(work);assertFalse(Files.exists(work));assertTrue(Files.exists(original));
    }
    @Test void uploaderLimitAllowsRepairAndDoesNotAffectOtherOwners()throws Exception {
        UUID owner=UUID.randomUUID();
        try(var store=new BgmStore(temp.resolve("limits.db"))) {
            for(int i=0;i<3;i++)store.save(new Track("track"+i,owner,"owner","title","https://youtu.be/abcdefghijk","","",10));
            assertThrows(java.sql.SQLException.class,()->store.save(new Track("fourth",owner,"owner","title","https://youtu.be/abcdefghijk","","",10)));
            store.save(new Track("track0",owner,"owner","title","https://youtu.be/abcdefghijk","https://www.dropbox.com/a?dl=1","a".repeat(40),10));
            store.save(new Track("other",UUID.randomUUID(),"other","title","https://youtu.be/abcdefghijk","","",10));
            assertEquals(4,store.list().size());assertTrue(store.list().getFirst().ready());
        }
    }
    @Test void legacyDatabaseIsMigratedAndOwnerPlaylistsSurviveRestart()throws Exception {
        Class.forName("org.sqlite.JDBC");Path file=temp.resolve("legacy.db");UUID owner=UUID.randomUUID();
        try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+file);var statement=connection.createStatement()) {
            statement.execute("CREATE TABLE tracks(id TEXT PRIMARY KEY,uploader TEXT NOT NULL,uploader_name TEXT NOT NULL,title TEXT NOT NULL,youtube_url TEXT NOT NULL,delivery_url TEXT NOT NULL,sha1 TEXT NOT NULL,seconds REAL NOT NULL)");
            statement.execute("INSERT INTO tracks VALUES('a','"+owner+"','owner','title','','https://www.dropbox.com/a?dl=1','"+"a".repeat(40)+"',10)");
        }
        var playlist=new BgmPlaylist(List.of("a","other"),BgmTimeline.Mode.MEDLEY,"a");
        try(var store=new BgmStore(file)) {
            assertTrue(store.list().getFirst().ready());assertFalse(store.list().getFirst().synchronizedReady());store.savePlaylist(owner,playlist);
        }
        try(var store=new BgmStore(file)){assertEquals(playlist,store.playlists().get(owner));}
    }
}
