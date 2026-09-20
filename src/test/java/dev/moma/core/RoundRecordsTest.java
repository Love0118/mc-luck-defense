package dev.moma.core;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RoundRecordsTest {
    @TempDir Path temp;
    @Test void persistsPersonalBestsDeduplicatesAndOrdersTopTen()throws Exception {
        RoundRecords records=new RoundRecords();
        for(int i=0;i<12;i++)records.record(new UUID(0,i),"Player"+i,i+1);
        UUID id=new UUID(0,0);records.record(id,"한글",250);records.record(id,"한글",3);
        assertFalse(records.record(id,"한글",1));
        Path file=temp.resolve("records.properties");RoundRecords.save(file,records.snapshot());
        var loaded=RoundRecords.load(file);assertEquals(records.top(10),loaded.top(10));
        assertEquals(10,loaded.top(10).size());assertEquals(250,loaded.top(10).getFirst().round());
        assertEquals("한글",loaded.top(10).getFirst().name());
        assertEquals(12,loaded.snapshot().size());
    }
}
