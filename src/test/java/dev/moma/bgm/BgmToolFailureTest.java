package dev.moma.bgm;

import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BgmToolFailureTest {
    @TempDir Path temp;
    private List<String> command(String mode) {
        String executable=System.getProperty("os.name").startsWith("Windows")?"java.exe":"java";
        return List.of(Path.of(System.getProperty("java.home"),"bin",executable).toString(),"-cp",
                System.getProperty("java.class.path"),FailingTool.class.getName(),mode);
    }
    @Test void expiredCookiesAreReportedWithoutExposingToolOutputOrCredentials() {
        var failure=assertThrows(BgmToolFailure.class,()->BgmMedia.run(BgmToolFailure.Stage.METADATA,
                command("cookies"),temp.resolve("metadata.json"),Duration.ofSeconds(10)));
        assertEquals(BgmToolFailure.Reason.COOKIE_EXPIRED,failure.reason());
        assertEquals("YouTube 인증이 만료되었습니다. 관리자에게 문의하세요.",failure.getMessage());
        assertEquals("METADATA / COOKIE_EXPIRED / exit=1",failure.diagnostic());
        assertFalse(failure.toString().contains("test-private-cookie"));
        assertFalse(failure.diagnostic().contains("private.example"));
    }
    @Test void botChecksAndPrivateVideosHaveDifferentPlayerMessages()throws Exception {
        Path errors=temp.resolve("tool.err");
        Files.writeString(errors,"ERROR: Sign in to confirm you're not a bot");
        var blocked=BgmToolFailure.fromStderr(BgmToolFailure.Stage.DOWNLOAD,1,errors);
        assertEquals(BgmToolFailure.Reason.BOT_CHECK,blocked.reason());
        assertTrue(blocked.getMessage().contains("요청을 차단"));
        Files.writeString(errors,"ERROR: Private video. Sign in if you have access");
        var privateVideo=BgmToolFailure.fromStderr(BgmToolFailure.Stage.METADATA,1,errors);
        assertEquals(BgmToolFailure.Reason.ACCESS_RESTRICTED,privateVideo.reason());
        assertNotEquals(blocked.getMessage(),privateVideo.getMessage());
    }
    @Test void longStderrKeepsTheFinalErrorAndUnknownFailuresRetainTheirStage()throws Exception {
        Path errors=temp.resolve("long.err");
        Files.writeString(errors,"debug\n".repeat(10000)+"ERROR: cookies are no longer valid");
        assertEquals(BgmToolFailure.Reason.COOKIE_EXPIRED,BgmToolFailure.fromStderr(BgmToolFailure.Stage.METADATA,1,errors).reason());
        Files.writeString(errors,"unknown failure with test-private-cookie");
        var failure=BgmToolFailure.fromStderr(BgmToolFailure.Stage.CONVERT,2,errors);
        assertEquals(BgmToolFailure.Reason.FAILED,failure.reason());
        assertTrue(failure.getMessage().contains("음원을 변환"));
        assertFalse(failure.getMessage().contains("test-private-cookie"));
    }
    @Test void missingToolsAndTimeoutsAreDistinguished() {
        var missing=assertThrows(BgmToolFailure.class,()->BgmMedia.run(BgmToolFailure.Stage.DOWNLOAD,
                List.of(temp.resolve("does-not-exist").toString()),temp.resolve("missing.log"),Duration.ofSeconds(1)));
        assertEquals(BgmToolFailure.Reason.TOOL_START,missing.reason());
        var timeout=assertThrows(BgmToolFailure.class,()->BgmMedia.run(BgmToolFailure.Stage.SEGMENT,
                command("sleep"),temp.resolve("slow.log"),Duration.ofMillis(100)));
        assertEquals(BgmToolFailure.Reason.TIMEOUT,timeout.reason());
    }
    @Test void successfulProcessWarningsDoNotRejectAValidDownload()throws Exception {
        BgmMedia.run(BgmToolFailure.Stage.METADATA,command("success"),temp.resolve("success.json"),Duration.ofSeconds(10));
        assertEquals("{}",Files.readString(temp.resolve("success.json")).trim());
    }
    public static class FailingTool {
        public static void main(String[] args)throws Exception {
            if(args[0].equals("sleep")){Thread.sleep(30000);return;}
            System.err.println("WARNING: cookies are no longer valid; test-private-cookie; https://private.example/?token=private");
            if(args[0].equals("success")){System.out.println("{}");return;}
            System.err.println("ERROR: Sign in to confirm you're not a bot");
            System.exit(1);
        }
    }
}
