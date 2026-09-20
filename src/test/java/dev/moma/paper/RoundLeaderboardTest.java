package dev.moma.paper;

import org.bukkit.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoundLeaderboardTest {
    @Test void boardIsFiveBlocksAheadIgnoringSpawnPitch() {
        World world=mock(World.class);
        for(float yaw:new float[]{0,90,180,-90}) {
            Location spawn=new Location(world,20,65,-10,yaw,50);
            Location result=RoundLeaderboard.location(spawn);
            var delta=result.toVector().subtract(spawn.toVector());
            assertEquals(2.8,delta.getY(),1e-9);delta.setY(0);
            assertEquals(5,delta.length(),1e-9);
            Location horizontal=spawn.clone();horizontal.setPitch(0);
            assertEquals(1,delta.normalize().dot(horizontal.getDirection()),1e-9);
            assertEquals(65,spawn.getY());
        }
    }
}
