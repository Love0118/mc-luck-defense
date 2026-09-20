package dev.moma.bgm;

import java.util.*;

public record BgmPlaylist(List<String> tracks, BgmTimeline.Mode mode, String selected) {
    public BgmPlaylist {
        tracks=List.copyOf(tracks);
        if(tracks.size()>3 || new HashSet<>(tracks).size()!=tracks.size())throw new IllegalArgumentException("재생 목록은 중복 없이 최대 3곡입니다.");
        Objects.requireNonNull(mode);Objects.requireNonNull(selected);
    }
    public BgmPlaylist toggle(String id) {
        var next=new ArrayList<>(tracks);
        if(!next.remove(id)){if(next.size()==3)throw new IllegalArgumentException("재생 목록이 가득 찼습니다. 기존 곡을 우클릭하여 빼주세요.");next.add(id);}
        return new BgmPlaylist(next,mode,next.contains(selected)?selected:next.isEmpty()?"default":next.getFirst());
    }
}
