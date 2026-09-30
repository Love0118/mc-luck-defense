package dev.moma.bgm;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;

public final class BgmToolFailure extends IOException {
    public enum Stage {
        METADATA("영상 정보를 가져오지 못했습니다."),
        DOWNLOAD("YouTube 음원을 내려받지 못했습니다."),
        CONVERT("음원을 변환하지 못했습니다."),
        SEGMENT("음원 동기화 준비에 실패했습니다.");
        final String message;
        Stage(String message){this.message=message;}
    }
    public enum Reason { COOKIE_UNAVAILABLE, COOKIE_EXPIRED, BOT_CHECK, ACCESS_RESTRICTED, NETWORK, TOOL_START, TIMEOUT, FAILED }
    private final Stage stage;
    private final Reason reason;
    private final int exitCode;

    public BgmToolFailure(Stage stage,Reason reason,int exitCode) {
        super(switch(reason) {
            case COOKIE_UNAVAILABLE -> "YouTube 쿠키 파일을 읽을 수 없습니다.";
            case COOKIE_EXPIRED -> "YouTube 인증이 만료되었습니다. 관리자에게 문의하세요.";
            case BOT_CHECK -> "YouTube가 다운로드 요청을 차단했습니다. 관리자에게 문의하세요.";
            case ACCESS_RESTRICTED -> "비공개·연령 제한 등으로 영상에 접근할 수 없습니다.";
            case NETWORK -> "음원 서버에 연결하지 못했습니다. 잠시 후 다시 시도하세요.";
            case TOOL_START -> "음원 처리 도구를 실행하지 못했습니다. 관리자에게 문의하세요.";
            case TIMEOUT -> "음원 처리 시간이 초과되었습니다. 잠시 후 다시 시도하세요.";
            case FAILED -> stage.message+" 관리자에게 문의하세요.";
        });
        this.stage=stage;this.reason=reason;this.exitCode=exitCode;
    }
    public Stage stage(){return stage;}
    public Reason reason(){return reason;}
    public String diagnostic(){return stage+" / "+reason+" / exit="+exitCode;}
    static BgmToolFailure fromStderr(Stage stage,int exitCode,Path errors) {
        String text="";
        try(var input=Files.newByteChannel(errors)) {
            input.position(Math.max(0,input.size()-16384));
            ByteBuffer bytes=ByteBuffer.allocate(16384);
            while(bytes.hasRemaining() && input.read(bytes)>0) {}
            text=new String(bytes.array(),0,bytes.position(),StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        } catch(IOException ignored) {}
        Reason reason;
        if(text.contains("cookies are no longer valid") || text.contains("cookies have expired"))reason=Reason.COOKIE_EXPIRED;
        else if(text.contains("sign in to confirm") && text.contains("not a bot"))reason=Reason.BOT_CHECK;
        else if(text.contains("private video") || text.contains("confirm your age") || text.contains("members-only"))reason=Reason.ACCESS_RESTRICTED;
        else if(text.contains("unable to download") || text.contains("connection refused") || text.contains("timed out")
                || text.contains("temporary failure in name resolution"))reason=Reason.NETWORK;
        else reason=Reason.FAILED;
        return new BgmToolFailure(stage,reason,exitCode);
    }
}
