package dev.moma.core;

import dev.moma.runtime.StateCodec;
import java.nio.file.*;
import java.security.MessageDigest;
import java.nio.ByteBuffer;
import java.util.HexFormat;

public final class CurveStateProbe {
    public static void main(String[] args)throws Exception {
        Path path=Path.of(args[1]);
        CampaignRules rules;
        if(args[0].equals("write")) {
            rules=CampaignRules.standard();Files.write(path,StateCodec.write(rules));
        } else rules=StateCodec.read(Files.readAllBytes(path),CampaignRules.class);
        var digest=MessageDigest.getInstance("SHA-256");
        for(int round:new int[]{1,30,100,200,201,300,500,800,1000,1500,2000,10000,10001,100000})
            for(var entry:WaveSchedule.create(round,rules).entries())
                digest.update(ByteBuffer.allocate(8).putDouble(entry.enemy().health()).array());
        System.out.println(StateCodec.fingerprint(rules)+" "+HexFormat.of().formatHex(digest.digest())+" "+Files.size(path));
    }
}
