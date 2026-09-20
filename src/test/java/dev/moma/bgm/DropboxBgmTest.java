package dev.moma.bgm;

import java.net.http.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DropboxBgmTest {
    @TempDir Path temp;
    @Test void refreshUploadLinkAndHashVerificationUseDirectApi()throws Exception {
        HttpClient client=mock(HttpClient.class);List<HttpRequest> requests=new ArrayList<>();
        Path file=temp.resolve("pack.zip");Files.write(file,new byte[]{80,75,3,4});String hash=BgmMedia.sha1(file);
        when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(call->{
            HttpRequest request=call.getArgument(0);requests.add(request);HttpResponse response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(200);
            Object body=switch(request.uri().getPath()) {
                case "/oauth2/token" -> "{\"access_token\":\"test-token\"}";
                case "/2/files/upload" -> "{}";
                case "/2/sharing/list_shared_links" -> "{\"links\":[{\"url\":\"https://www.dropbox.com/s/test/pack.zip?dl=0\"}]}";
                default -> new ByteArrayInputStream(Files.readAllBytes(file));
            };
            when(response.body()).thenReturn(body);return response;
        });
        String url=new DropboxBgm("test-refresh",client).publish("abc",hash,file);
        assertEquals("https://www.dropbox.com/s/test/pack.zip?dl=1",url);assertEquals(4,requests.size());
        assertEquals("Bearer test-token",requests.get(1).headers().firstValue("Authorization").orElseThrow());
        assertEquals("POST",requests.getFirst().method());
    }
}
