package dev.moma.bgm;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BgmTimelineTest {
    private Track track(String id,double seconds){return new Track(id,UUID.randomUUID(),"owner",id,"","https://www.dropbox.com/a?dl=1","a".repeat(40),seconds);}
    @Test void singleLoopsAndMedleyUsesRealElapsedTimeIncludingPartialLastSegment() {
        Track a=track("a",5.5),b=track("b",4);var clock=new BgmTimeline();
        clock.configure(List.of(a,b),BgmTimeline.Mode.SINGLE,"a");assertNull(clock.at(0));clock.start(1_000_000_000L);
        assertEquals(0,clock.at(1_000_000_000L).segment());
        assertEquals(2,clock.at(5_100_000_000L).segment());assertEquals(100,clock.at(5_100_000_000L).lateMillis());
        assertEquals(1,clock.at(6_500_000_000L).cycle());assertEquals(0,clock.at(6_500_000_000L).segment());
        clock.configure(List.of(a,b),BgmTimeline.Mode.MEDLEY,"a");clock.start(0);
        assertEquals("b",clock.at(5_500_000_000L).track().id());assertEquals(0,clock.at(5_500_000_000L).offsetMillis());
        assertEquals("a",clock.at(9_500_000_000L).track().id());assertEquals(1,clock.at(9_500_000_000L).cycle());
        assertEquals("b",clock.at(24_500_000_000L).track().id());
    }
    @Test void medleyStartsAtSelectionAndWrapsWithoutChangingPlaylistOrder() {
        var playlist=List.of(track("a",4),track("b",6),track("c",2));var timeline=new BgmTimeline();
        timeline.configure(playlist,BgmTimeline.Mode.MEDLEY,"b");timeline.start(0);
        assertEquals("b",timeline.at(0).track().id());
        assertEquals("c",timeline.at(6_000_000_000L).track().id());
        assertEquals("a",timeline.at(8_000_000_000L).track().id());
        assertEquals("b",timeline.at(12_000_000_000L).track().id());
        long revision=timeline.revision();timeline.configure(playlist,BgmTimeline.Mode.MEDLEY,"b");
        assertEquals(revision,timeline.revision());assertTrue(timeline.started());
        timeline.configure(playlist,BgmTimeline.Mode.MEDLEY,"c");assertFalse(timeline.started());
        timeline.start(20_000_000_000L);assertEquals("c",timeline.at(20_000_000_000L).track().id());
        assertEquals(List.of("a","b","c"),playlist.stream().map(Track::id).toList());
    }
    @Test void unchangedConfigAndLateListenerDoNotRestartOwnerClock() {
        Track a=track("a",110);var timeline=new BgmTimeline();
        timeline.configure(List.of(a),BgmTimeline.Mode.SINGLE,"a");timeline.start(0);long revision=timeline.revision();
        timeline.configure(List.of(a),BgmTimeline.Mode.SINGLE,"a");timeline.start(45_000_000_000L);
        assertEquals(22,timeline.at(45_000_000_000L).segment());assertEquals(1000,timeline.at(45_000_000_000L).lateMillis());
        assertEquals(revision,timeline.revision());assertEquals(23,timeline.at(46_000_000_000L).segment());
        timeline.configure(List.of(a),BgmTimeline.Mode.MEDLEY,"a");assertFalse(timeline.started());assertTrue(timeline.revision()>revision);
    }
    @Test void playlistsCanUseOthersTracksButAreDistinctAndLimitedToThree() {
        BgmPlaylist list=new BgmPlaylist(List.of("a","b"),BgmTimeline.Mode.MEDLEY,"a");
        list=list.toggle("other_owner_track",3);assertEquals(3,list.tracks().size());
        BgmPlaylist full=list;assertThrows(IllegalArgumentException.class,()->full.toggle("fourth",3));
        assertEquals(4,list.toggle("fourth",4).tracks().size());
        assertEquals(List.of("b","other_owner_track"),list.toggle("a",1).tracks());assertEquals("b",list.toggle("a",1).selected());
    }
}
