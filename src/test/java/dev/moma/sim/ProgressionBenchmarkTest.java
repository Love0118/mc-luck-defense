package dev.moma.sim;

import dev.moma.core.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProgressionBenchmarkTest {
    @TempDir Path folder;
    private byte[] rows(Path output)throws Exception {
        try(var in=new GZIPInputStream(Files.newInputStream(output.resolve("none.jsonl.gz")))){return in.readAllBytes();}
    }
    @Test void growthOverridesRebuildTheSmoothModelAndExplicitCurvesRemainLegacy()throws Exception {
        var defaults=ProgressionBenchmarkMain.loadRules(ProgressionBenchmarkMain.Options.parse(new String[0]));
        assertEquals(CampaignRules.standard(),defaults);
        var power=ProgressionBenchmarkMain.loadRules(ProgressionBenchmarkMain.Options.parse(
                new String[]{"1","0","x","none","--health-power","3.2"}));
        assertEquals(defaults.healthCurve().at(200),power.healthCurve().at(200));
        assertNotEquals(defaults.healthCurve().at(1500),power.healthCurve().at(1500));
        var custom=ProgressionBenchmarkMain.loadRules(ProgressionBenchmarkMain.Options.parse(
                new String[]{"1","0","x","none","--health-curve","1:10,100:1000","--health-power","3.2"}));
        assertEquals(HealthCurve.parse("1:10,100:1000"),custom.healthCurve());assertEquals(3.2,custom.endlessHealthPower());
    }
    @Test void workersAndResumePreserveSeedOrderAndDiscardOnlyIncompleteTrailingRecord()throws Exception {
        Path one=folder.resolve("one"),many=folder.resolve("many");
        ProgressionBenchmarkMain.main(new String[]{"8","100",one.toString(),"none","--threads","1","--cap","5"});
        ProgressionBenchmarkMain.main(new String[]{"8","100",many.toString(),"none","--threads","4","--cap","5"});
        assertArrayEquals(rows(one),rows(many));
        byte[] expected=rows(many);Path journal=many.resolve("none.partial.jsonl");
        List<String> lines=Files.readAllLines(journal);
        Files.writeString(journal,String.join("\n",lines.subList(0,3))+"\n{\"seed\":10");
        ProgressionBenchmarkMain.main(new String[]{"8","100",many.toString(),"none","--threads","2","--cap","5","--resume"});
        assertArrayEquals(expected,rows(many));assertEquals(8,Files.readAllLines(journal).size());
        assertThrows(IllegalArgumentException.class,()->ProgressionBenchmarkMain.main(new String[]{"8","101",many.toString(),"none","--cap","5","--resume"}));
        assertThrows(IllegalArgumentException.class,()->ProgressionBenchmarkMain.main(new String[]{"8","100",many.toString(),"none","--cap","5"}));
        var resume=ProgressionBenchmarkMain.Options.parse(new String[]{"8","100",many.toString(),"none","--cap","5","--resume"});
        assertThrows(IllegalArgumentException.class,()->ProgressionBenchmarkMain.run(resume,CampaignRules.standard().withHealthScale(2)));
    }
    @Test void defaultsUseAvailableProcessorsAndInvalidFlagsAreRejected() {
        assertEquals(Runtime.getRuntime().availableProcessors(),ProgressionBenchmarkMain.Options.parse(new String[0]).threads());
        for(String[] args:List.of(new String[]{"0"},new String[]{"1","0","x","none","--threads","0"},
                new String[]{"1","0","x","none,none"},new String[]{"1","0","x","none","--cap"},
                new String[]{"1","0","x","none","--unknown","1"}))
            assertThrows(IllegalArgumentException.class,()->ProgressionBenchmarkMain.Options.parse(args));
        assertEquals(3,ProgressionBenchmarkMain.loadouts().get("equipped").entries().size());
        assertEquals(4,ProgressionBenchmarkMain.loadouts().get("top_fusion_four").entries().size());
    }
}
