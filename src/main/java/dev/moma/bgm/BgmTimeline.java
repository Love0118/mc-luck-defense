package dev.moma.bgm;

import java.util.*;

/** One monotonic wall-clock timeline shared by every listener, independent of simulation speed. */
public final class BgmTimeline {
    public static final long SEGMENT_MILLIS = 2000;
    public enum Mode { SINGLE, MEDLEY }
    public record Cue(Track track, int segment, long cycle, long offsetMillis, long lateMillis) {
        public String sound() { return track.segmentSound(segment); }
        public String identity() { return track.id()+":"+track.sha1()+":"+cycle+":"+segment; }
    }
    private List<Track> tracks=List.of();
    private String signature="";
    private long startedNanos;
    private boolean started;
    private long revision;
    public long revision(){return revision;}

    public void configure(List<Track> playlist, Mode mode, String selected) {
        List<Track> usable=playlist.stream().filter(Track::synchronizedReady).toList();
        if(mode==Mode.SINGLE) usable=usable.stream().filter(t->t.id().equals(selected)).limit(1).toList();
        else {
            var ordered=new ArrayList<>(usable);
            for(int i=0;i<ordered.size();i++)if(ordered.get(i).id().equals(selected)) {
                Collections.rotate(ordered,-i);break;
            }
            usable=ordered;
        }
        String next=mode+":"+usable.stream().map(t->t.id()+":"+t.sha1()+":"+t.seconds()).toList();
        if(next.equals(signature))return;
        signature=next;tracks=List.copyOf(usable);started=false;revision++;
    }
    public void start(long nowNanos) { if(!started && !tracks.isEmpty()){startedNanos=nowNanos;started=true;} }
    public boolean started(){return started;}
    public Cue at(long nowNanos) {
        if(!started || tracks.isEmpty())return null;
        long total=tracks.stream().mapToLong(BgmTimeline::length).sum();
        long elapsed=Math.max(0,(nowNanos-startedNanos)/1_000_000);
        long cycle=elapsed/total,offset=elapsed%total;
        for(Track track:tracks) {
            long duration=length(track);
            if(offset<duration)return new Cue(track,(int)(offset/SEGMENT_MILLIS),cycle,offset,offset%SEGMENT_MILLIS);
            offset-=duration;
        }
        throw new IllegalStateException("Timeline outside playlist");
    }
    private static long length(Track track){return Math.max(1,Math.round(track.seconds()*1000));}
}
