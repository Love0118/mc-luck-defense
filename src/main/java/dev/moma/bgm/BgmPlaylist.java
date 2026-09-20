package dev.moma.bgm;

import java.util.*;

public record BgmPlaylist(List<String> tracks, BgmTimeline.Mode mode, String selected) {
    public BgmPlaylist {
        tracks=List.copyOf(tracks);
        if(new HashSet<>(tracks).size()!=tracks.size())throw new IllegalArgumentException("재생 목록에 중복된 곡이 있습니다.");
        Objects.requireNonNull(mode);Objects.requireNonNull(selected);
    }
    public BgmPlaylist toggle(String id, int maximum) {
        var next=new ArrayList<>(tracks);
        if(!next.remove(id)){if(next.size()>=maximum)throw new IllegalArgumentException("재생 목록은 최대 "+maximum+"곡입니다. 기존 곡을 우클릭하여 빼주세요.");next.add(id);}
        return new BgmPlaylist(next,mode,next.contains(selected)?selected:next.isEmpty()?"default":next.getFirst());
    }
}
