package dev.moma.paper;

import dev.moma.bgm.BgmLimits;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BgmSettingsTest {
    @Test void missingKeysUseDefaultsAndExistingSettingsArePreserved() {
        var config=new YamlConfiguration();config.set("refresh-token","test-token");
        assertEquals(BgmLimits.DEFAULT,BgmSettings.limits(config));
        assertEquals(3,config.getInt("limits.uploads-per-player"));
        assertEquals("test-token",config.getString("refresh-token"));
        config.set("limits.uploads-per-player",12);config.set("limits.playlist-tracks",60);
        config.set("limits.duration-seconds",600);config.set("limits.file-size-mb",50);
        assertEquals(new BgmLimits(12,60,600,50),BgmSettings.limits(config));
        assertEquals(50*1024L*1024L,BgmSettings.limits(config).fileSizeBytes());
    }
    @Test void invalidValuesAreRejectedWithTheSettingName() {
        for(String key:new String[]{"uploads-per-player","playlist-tracks","duration-seconds","file-size-mb"})
            for(Object value:new Object[]{0,-1,1.5,"five",true,Long.MAX_VALUE}) {
                var config=new YamlConfiguration();config.set("limits."+key,value);
                assertTrue(assertThrows(IllegalArgumentException.class,()->BgmSettings.limits(config)).getMessage().contains(key));
            }
    }
}
