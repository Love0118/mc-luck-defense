package dev.moma.bgm;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BgmCookieTest {
    @TempDir Path temp;

    @Test void unsetCookiesPreserveAnonymousDownloads()throws Exception {
        assertEquals(List.of("yt-dlp","--ignore-config","--skip-download","--","https://example.test"),
                new BgmMedia("yt-dlp","ffmpeg").downloaderCommand("--skip-download","--","https://example.test"));
    }

    @Test void metadataAndAudioCommandsUseFilePathWithoutReadingSecretsIntoArguments()throws Exception {
        Path cookies=Files.writeString(temp.resolve("youtube cookies.txt"),"private-cookie-value");
        var media=new BgmMedia("yt-dlp","ffmpeg",BgmLimits.DEFAULT,cookies);
        for(String[] options:List.of(new String[]{"--skip-download","--dump-single-json"},new String[]{"-f","bestaudio"})) {
            var command=media.downloaderCommand(options);
            assertEquals(List.of("yt-dlp","--ignore-config","--cookies",cookies.toAbsolutePath().toString()),command.subList(0,4));
            assertEquals(List.of(options),command.subList(4,command.size()));
            assertFalse(command.toString().contains("private-cookie-value"));
        }
    }

    @Test void missingOrDirectoryCookiePathFailsBeforeLaunchingDownloader()throws Exception {
        for(Path cookies:List.of(temp,temp.resolve("absent.txt"))) {
            var media=new BgmMedia("must-not-launch","must-not-launch",BgmLimits.DEFAULT,cookies);
            var error=assertThrows(IOException.class,()->media.download("https://youtu.be/abcdefghijk",temp));
            assertEquals("YouTube 쿠키 파일을 읽을 수 없습니다.",error.getMessage());
        }
    }
}
