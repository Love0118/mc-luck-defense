package dev.moma.bootstrap;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;

record RuntimeArtifact(Path path,String version,String schema,String hostHash,String balanceHash,String sha256) {
    static RuntimeArtifact inspect(Path path)throws IOException {
        if(!Files.isRegularFile(path) || Files.size(path)>64*1024*1024)throw new IOException("올바른 플러그인 JAR가 아닙니다.");
        try(var jar=new JarFile(path.toFile())) {
            JarEntry descriptor=jar.getJarEntry("mud-runtime.properties");
            if(descriptor==null)throw new IOException("세션 유지 업데이트를 지원하지 않는 버전입니다.");
            var properties=new Properties();try(var in=jar.getInputStream(descriptor)){properties.load(in);}
            String version=properties.getProperty("version",""),schema=properties.getProperty("state-schema","");
            if(!"1".equals(properties.getProperty("host-api")) || !version.matches("[0-9]+[.][0-9]+[.][0-9]+(?:-[A-Za-z0-9.-]+)?") || !schema.matches("[0-9]+"))
                throw new IOException("지원하지 않는 런타임 버전입니다.");
            var host=digest();var balance=digest();long total=0;
            for(var entry:jar.stream().filter(e->!e.isDirectory()).sorted(Comparator.comparing(JarEntry::getName)).toList()) {
                String name=entry.getName();byte[] bytes;
                try(var in=jar.getInputStream(entry)){bytes=in.readNBytes(64*1024*1024+1);}
                total+=bytes.length;if(total>128*1024*1024)throw new IOException("압축 해제 크기가 너무 큽니다.");
                if(name.equals("plugin.yml")) {
                    String yaml=new String(bytes,StandardCharsets.UTF_8);
                    if(!yaml.contains("name: MCLuckDefense") || !yaml.contains("main: dev.moma.paper.MomaPlugin"))throw new IOException("다른 플러그인입니다.");
                    bytes=yaml.replaceAll("(?m)^version:.*(?:\\r?\\n|$)","").getBytes(StandardCharsets.UTF_8);
                }
                boolean projectMetadata=name.equals("mud-runtime.properties") || name.equals("META-INF/MANIFEST.MF") || name.startsWith("META-INF/maven/dev.moma/");
                if(!projectMetadata && (!name.startsWith("dev/moma/") && !name.equals("campaign.properties")
                        || name.startsWith("dev/moma/bootstrap/") || name.startsWith("dev/moma/paper/MomaPlugin")))update(host,name,bytes);
                if(name.equals("campaign.properties") || name.matches("dev/moma/core/(Rarity|UnitType|AttackRole|TraitCatalog|WaveSchedule|WaveTheme|SummonTier|Grid)(\\$.*)?\\.class"))update(balance,name,bytes);
            }
            return new RuntimeArtifact(path,version,schema,HexFormat.of().formatHex(host.digest()),HexFormat.of().formatHex(balance.digest()),hash(path));
        }
    }
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    private static void update(MessageDigest digest,String name,byte[] data){digest.update(name.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);digest.update(data);}
    static String hash(Path path)throws IOException {
        var digest=digest();try(var input=Files.newInputStream(path)){byte[] block=new byte[65536];int count;while((count=input.read(block))>=0)digest.update(block,0,count);}
        return HexFormat.of().formatHex(digest.digest());
    }
}
