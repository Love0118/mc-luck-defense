package dev.moma.bgm;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** Direct Dropbox OAuth/API integration; defaults match the referenced public OAuth application. */
public final class DropboxBgm {
    public static final String APP_KEY="5jcck7diasz0rqy";
    private static final String APP_SECRET="1n9m04y2zx7bf26";
    private final HttpClient client;
    private final long maximumBytes;
    private volatile String refreshToken;
    public DropboxBgm(String refreshToken){this(refreshToken,BgmLimits.DEFAULT.fileSizeBytes());}
    public DropboxBgm(String refreshToken,long maximumBytes){this(refreshToken,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build(),maximumBytes);}
    DropboxBgm(String refreshToken,HttpClient client){this(refreshToken,client,BgmLimits.DEFAULT.fileSizeBytes());}
    DropboxBgm(String refreshToken,HttpClient client,long maximumBytes){this.refreshToken=refreshToken;this.client=client;this.maximumBytes=maximumBytes;}
    public boolean connected(){return refreshToken!=null && !refreshToken.isBlank();}
    public String authorizationUrl(String state){return "https://www.dropbox.com/oauth2/authorize?client_id="+APP_KEY+"&response_type=code&token_access_type=offline&state="+encode(state);}
    public String authorize(String code)throws Exception {
        if(code.isBlank() || code.length()>2048)throw new IOException("인증 코드를 확인하세요.");
        JsonObject response=form("grant_type=authorization_code&code="+encode(code.trim()));
        if(!response.has("refresh_token"))throw new IOException("Dropbox 갱신 토큰이 없습니다.");
        refreshToken=response.get("refresh_token").getAsString();return refreshToken;
    }
    private JsonObject form(String body)throws Exception {
        return json(HttpRequest.newBuilder(URI.create("https://api.dropboxapi.com/oauth2/token")).timeout(Duration.ofSeconds(30))
                .header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body+"&client_id="+APP_KEY+"&client_secret="+APP_SECRET)).build());
    }
    private String token()throws Exception {
        if(!connected())throw new IOException("관리자가 Dropbox 계정을 먼저 연결해야 합니다.");
        return form("grant_type=refresh_token&refresh_token="+encode(refreshToken)).get("access_token").getAsString();
    }
    public String publish(String id,String hash,Path archive)throws Exception {
        String access=token(),path="/mc-luck-defense/bgm/"+id+"-"+hash+".zip";
        JsonObject argument=new JsonObject();argument.addProperty("path",path);argument.addProperty("mode","overwrite");argument.addProperty("mute",true);
        json(HttpRequest.newBuilder(URI.create("https://content.dropboxapi.com/2/files/upload")).timeout(Duration.ofMinutes(2))
                .header("Authorization","Bearer "+access).header("Dropbox-API-Arg",argument.toString()).header("Content-Type","application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofFile(archive)).build());
        JsonObject listBody=new JsonObject();listBody.addProperty("path",path);listBody.addProperty("direct_only",true);
        JsonArray links=post("sharing/list_shared_links",access,listBody).getAsJsonArray("links");
        String link;
        if(!links.isEmpty())link=links.get(0).getAsJsonObject().get("url").getAsString();
        else {
            JsonObject create=new JsonObject();create.addProperty("path",path);
            JsonObject settings=new JsonObject();settings.addProperty("requested_visibility","public");create.add("settings",settings);
            link=post("sharing/create_shared_link_with_settings",access,create).get("url").getAsString();
        }
        String direct=directLink(link);
        if(!healthy(direct,hash))throw new IOException("Dropbox 배포 파일을 검증하지 못했습니다.");
        return direct;
    }
    public boolean healthy(String url,String expectedSha1)throws Exception {
        URI uri=URI.create(directLink(url));
        var response=client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
        try(var input=response.body()) {
            if(response.statusCode()!=200)return false;
            MessageDigest digest=MessageDigest.getInstance("SHA-1");byte[] buffer=new byte[8192];long total=0;int size;
            while((size=input.read(buffer))!=-1){total+=size;if(total>maximumBytes)return false;digest.update(buffer,0,size);}
            return HexFormat.of().formatHex(digest.digest()).equals(expectedSha1);
        }
    }
    private JsonObject post(String endpoint,String token,JsonObject body)throws Exception {
        return json(HttpRequest.newBuilder(URI.create("https://api.dropboxapi.com/2/"+endpoint)).timeout(Duration.ofSeconds(30))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
    }
    private JsonObject json(HttpRequest request)throws Exception {
        var response=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if(response.statusCode()/100!=2)throw new IOException("Dropbox 요청 실패 (HTTP "+response.statusCode()+")");
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
    public static String directLink(String url) {
        URI uri=URI.create(url);String host=uri.getHost();
        if(!"https".equals(uri.getScheme()) || host==null || !(host.equals("www.dropbox.com") || host.equals("dropbox.com") || host.equals("dl.dropboxusercontent.com")) || uri.getUserInfo()!=null || uri.getPort()!=-1)
            throw new IllegalArgumentException("Dropbox HTTPS 링크가 아닙니다.");
        List<String> query=new ArrayList<>();
        if(uri.getRawQuery()!=null)for(String p:uri.getRawQuery().split("&"))if(!p.startsWith("dl=") && !p.startsWith("raw="))query.add(p);
        query.add("dl=1");return "https://"+host+uri.getRawPath()+"?"+String.join("&",query);
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
}
