package dev.moma.bootstrap;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeResourcePackagingTest {
    @Test void runtimeResourcesKeepTheInstalledLoadersLineEndings()throws Exception {
        for(String resource:List.of("campaign.properties","config.yml","wave-themes.tsv")) {
            try(var input=getClass().getResourceAsStream("/"+resource)) {
                assertNotNull(input,resource);
                String text=new String(input.readAllBytes(),StandardCharsets.UTF_8);
                assertTrue(text.contains("\r\n"),resource+" must retain CRLF in release artifacts");
                assertFalse(text.replace("\r\n","").contains("\n"),resource+" contains an unnormalized line ending");
            }
        }
    }
    @Test void rewardTemplateKeepsItsInstalledLineEndings()throws Exception {
        try(var input=getClass().getResourceAsStream("/rewards.yml")) {
            assertNotNull(input);
            String text=new String(input.readAllBytes(),StandardCharsets.UTF_8);
            assertTrue(text.endsWith("\r\n"));
            assertFalse(text.substring(0,text.length()-2).contains("\r"));
        }
    }
}
