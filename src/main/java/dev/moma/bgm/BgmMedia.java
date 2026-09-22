package dev.moma.bgm;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

public final class BgmMedia {
    private final BgmLimits limits;
    public static final class RejectedAudio extends IOException {
        public RejectedAudio(String message){super(message);}
    }
    static double checkedDuration(JsonObject info)throws RejectedAudio {
        return checkedDuration(info,BgmLimits.DEFAULT.durationSeconds());
    }
    static double checkedDuration(JsonObject info,int maximum)throws RejectedAudio {
        double duration;
        try {duration=info.has("duration") && !info.get("duration").isJsonNull()?info.get("duration").getAsDouble():0;}
        catch(RuntimeException error){throw new RejectedAudio("영상 길이를 확인할 수 없습니다.");}
        boolean live=info.has("is_live") && !info.get("is_live").isJsonNull() && info.get("is_live").getAsBoolean();
        if(live)throw new RejectedAudio("실시간 영상은 등록할 수 없습니다.");
        validateDuration(duration,maximum);return duration;
    }
    static void validateDuration(double seconds,int maximum)throws RejectedAudio {
        if(!Double.isFinite(seconds) || seconds<=0)throw new RejectedAudio("영상 길이를 확인할 수 없습니다.");
        if(seconds>maximum)throw new RejectedAudio("최대 "+maximum+"초까지 등록할 수 있습니다.");
    }
    private final String downloader,ffmpeg;
    private final Path cookies;
    public BgmMedia(String downloader,String ffmpeg) { this(downloader,ffmpeg,BgmLimits.DEFAULT); }
    public BgmMedia(String downloader,String ffmpeg,BgmLimits limits) { this(downloader,ffmpeg,limits,null); }
    public BgmMedia(String downloader,String ffmpeg,BgmLimits limits,Path cookies) {
        this.downloader=downloader;this.ffmpeg=ffmpeg;this.limits=limits;this.cookies=cookies;
    }
    List<String> downloaderCommand(String... arguments)throws IOException {
        List<String> command=new ArrayList<>(List.of(downloader,"--ignore-config"));
        if(cookies!=null) {
            if(!Files.isRegularFile(cookies) || !Files.isReadable(cookies))throw new IOException("YouTube 쿠키 파일을 읽을 수 없습니다.");
            command.add("--cookies");command.add(cookies.toAbsolutePath().toString());
        }
        command.addAll(List.of(arguments));return command;
    }
    public record Audio(Path file,String title,double seconds) {}
    public static String youtube(String input) {
        try {
            URI uri=URI.create(input.trim());String host=uri.getHost();String id=null;
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo()!=null || uri.getPort()!=-1 || host==null) throw new IllegalArgumentException();
            if(host.equalsIgnoreCase("youtu.be")) id=uri.getPath().substring(1);
            else if(Set.of("youtube.com","www.youtube.com","m.youtube.com","music.youtube.com").contains(host.toLowerCase(Locale.ROOT))) {
                if("/watch".equals(uri.getPath()) && uri.getRawQuery()!=null) {
                    for(String p:uri.getRawQuery().split("&")) if(p.startsWith("v=")) id=URLDecoder.decode(p.substring(2),StandardCharsets.UTF_8);
                }
                else if(uri.getPath().startsWith("/shorts/") || uri.getPath().startsWith("/live/")) id=uri.getPath().substring(uri.getPath().lastIndexOf('/')+1);
            }
            if(id==null || !id.matches("[A-Za-z0-9_-]{11}")) throw new IllegalArgumentException();
            return "https://www.youtube.com/watch?v="+id;
        } catch(RuntimeException e) { throw new IllegalArgumentException("유효한 YouTube 영상 링크를 입력하세요."); }
    }
    public Audio download(String url,Path workspace) throws Exception {
        url=youtube(url);
        Path metadata=workspace.resolve("metadata.json");
        run(downloaderCommand("--js-runtimes","node","--no-playlist","--skip-download","--dump-single-json","--",url),metadata,Duration.ofMinutes(2));
        JsonObject info=JsonParser.parseString(Files.readString(metadata)).getAsJsonObject();
        double duration=checkedDuration(info,limits.durationSeconds());
        String title=info.get("title").getAsString().replaceAll("[\\p{Cntrl}]","");
        if(title.length()>160)title=title.substring(0,160);
        run(downloaderCommand("--js-runtimes","node","--no-playlist","--max-filesize",Long.toString(limits.fileSizeBytes()),"--socket-timeout","20","--retries","2",
                "-f","bestaudio","-o",workspace.resolve("source.%(ext)s").toString(),"--",url),workspace.resolve("download.log"),Duration.ofMinutes(8));
        Path source;
        try(var files=Files.list(workspace)) { source=files.filter(p->p.getFileName().toString().startsWith("source.") && !p.toString().endsWith(".part")).findFirst().orElseThrow(()->new IOException("영상 오디오를 다운로드하지 못했습니다.")); }
        if(Files.size(source)>limits.fileSizeBytes())throw new RejectedAudio("오디오 파일이 "+limits.fileSizeMb()+"MB를 초과합니다.");
        return convert(source,workspace,title,duration);
    }
    public Audio convert(Path source,Path workspace,String title,double seconds) throws Exception {
        validateDuration(seconds,limits.durationSeconds());
        Path audio=workspace.resolve("audio.ogg");
        run(List.of(ffmpeg,"-hide_banner","-nostdin","-y","-i",source.toString(),"-vn","-t",Integer.toString(limits.durationSeconds()),"-ac","2","-ar","48000","-c:a","libvorbis","-q:a","4",audio.toString()),
                workspace.resolve("convert.log"),Duration.ofMinutes(5));
        if(!Files.isRegularFile(audio)||Files.size(audio)>limits.fileSizeBytes())throw new IOException("OGG 변환 결과가 없거나 "+limits.fileSizeMb()+"MB를 초과합니다.");
        return new Audio(audio,title,seconds);
    }
    static void run(List<String> command,Path output,Duration timeout) throws Exception {
        Process process=new ProcessBuilder(command).redirectOutput(output.toFile()).redirectError(output.resolveSibling(output.getFileName()+".err").toFile()).start();
        try {
            if(!process.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS))throw new IOException("오디오 처리 시간이 초과되었습니다.");
            if(process.exitValue()!=0)throw new IOException("오디오 처리에 실패했습니다. 다운로드 도구와 영상 접근 가능 여부를 확인하세요.");
        } finally { if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();} }
    }
    public static Path pack(String id,Path audio,Path workspace) throws IOException {
        return pack(id,audio,List.of(),workspace,BgmLimits.DEFAULT.fileSizeBytes());
    }
    public Path synchronizedPack(String id,Path audio,double seconds,Path workspace)throws Exception {
        validateDuration(seconds,limits.durationSeconds());
        List<Path> segments=new ArrayList<>();
        for(int i=0;i<(int)Math.ceil(seconds/2);i++) {
            Path segment=workspace.resolve("part_"+i+".ogg");
            run(List.of(ffmpeg,"-hide_banner","-nostdin","-y","-ss",Integer.toString(i*2),"-i",audio.toString(),
                    "-t",Double.toString(Math.min(2,seconds-i*2)),"-vn","-ac","2","-ar","48000","-c:a","libvorbis","-q:a","4",segment.toString()),
                    workspace.resolve("segment.log"),Duration.ofSeconds(30));
            segments.add(segment);
        }
        return pack(id,audio,segments,workspace,limits.fileSizeBytes());
    }
    static Path pack(String id,Path fullAudio,List<Path> segments,Path workspace,long maximumBytes)throws IOException {
        if(!id.matches("[a-z0-9_]+"))throw new IllegalArgumentException("Invalid track id");
        Path zip=workspace.resolve("pack.zip");
        try(var output=new ZipOutputStream(Files.newOutputStream(zip))) {
            JsonObject metadata=new JsonObject(),format=new JsonObject();
            format.addProperty("pack_format",64);format.add("supported_formats",new Gson().toJsonTree(new int[]{64,97}));
            format.add("min_format",new Gson().toJsonTree(new int[]{64,0}));format.add("max_format",new Gson().toJsonTree(new int[]{97,1}));
            format.addProperty("description","MC Luck Defense BGM");metadata.add("pack",format);
            entry(output,"pack.mcmeta",metadata.toString().getBytes(StandardCharsets.UTF_8));
            JsonObject sounds=new JsonObject();
            if(fullAudio!=null) {
                addSound(sounds,"track_"+id,"mud_bgm:tracks/"+id);
                output.putNextEntry(new ZipEntry("assets/mud_bgm/sounds/tracks/"+id+".ogg"));Files.copy(fullAudio,output);output.closeEntry();
            }
            for(int i=0;i<segments.size();i++) {
                String name=id+"_part_"+i;
                addSound(sounds,"track_"+name,"mud_bgm:tracks/"+name);
                output.putNextEntry(new ZipEntry("assets/mud_bgm/sounds/tracks/"+name+".ogg"));Files.copy(segments.get(i),output);output.closeEntry();
            }
            entry(output,"assets/mud_bgm/sounds.json",sounds.toString().getBytes(StandardCharsets.UTF_8));
        }
        if(Files.size(zip)>maximumBytes)throw new RejectedAudio("리소스팩이 "+(maximumBytes/1024/1024)+"MB를 초과합니다.");
        return zip;
    }
    private static void addSound(JsonObject sounds,String key,String resource) {
        JsonObject event=new JsonObject(),sound=new JsonObject();JsonArray variants=new JsonArray();
        sound.addProperty("name",resource);sound.addProperty("stream",true);variants.add(sound);event.add("sounds",variants);sounds.add(key,event);
    }
    private static void entry(ZipOutputStream output,String name,byte[] data)throws IOException { output.putNextEntry(new ZipEntry(name));output.write(data);output.closeEntry(); }
    public static String sha1(Path file)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(file)));}
    public static void cleanup(Path workspace)throws IOException {
        try(var files=Files.walk(workspace)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
}
