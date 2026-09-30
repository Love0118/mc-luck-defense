package dev.moma.sim;

import dev.moma.core.CampaignRules;
import java.nio.file.*;
import java.util.*;

public final class SmoothCurveRunner {
    public static void main(String[] args)throws Exception {
        Properties properties=new Properties();
        try(var input=Files.newInputStream(Path.of(args[0]))){properties.load(input);}
        var options=ProgressionBenchmarkMain.Options.parse(Arrays.copyOfRange(args,1,args.length));
        ProgressionBenchmarkMain.run(options,CampaignRules.fromProperties(properties));
    }
}
