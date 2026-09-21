package dev.moma.bootstrap;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.jar.JarFile;

/** Fixed repository feed. The mutable pointer refers to a commit-specific, checksummed JAR. */
final class BetaFeed {
    static final String REPOSITORY="Love0118/mc-luck-defense";
    static final URI MANIFEST=URI.create("https://github.com/"+REPOSITORY+"/releases/download/beta-latest/update.properties");
    static final int MAX_JAR=64*1024*1024;
    record Release(String version,String commit,String sha256,long size) {
        URI jar(){return URI.create("https://github.com/"+REPOSITORY+"/releases/download/beta-build-"+commit+"/MCLuckDefense.jar");}
        String label(){return version+" · "+commit.substring(0,8);}
    }
    @FunctionalInterface interface Transport {byte[] read(URI uri,int limit)throws IOException;}
    private final Transport transport;
    BetaFeed(){this(BetaFeed::download);}
    BetaFeed(Transport transport){this.transport=transport;}
    Release latest()throws IOException {
        Properties p=new Properties();p.load(new ByteArrayInputStream(transport.read(MANIFEST,8192)));
        try {
            String version=p.getProperty("version",""),commit=p.getProperty("commit",""),hash=p.getProperty("sha256","");
            long size=Long.parseLong(p.getProperty("size","0"));
            if(!"1".equals(p.getProperty("format")) || !REPOSITORY.equals(p.getProperty("repository")) || !"beta".equals(p.getProperty("branch"))
                    || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?") || !commit.matches("[a-f0-9]{40}")
                    || !hash.matches("[a-f0-9]{64}") || size<1 || size>MAX_JAR)throw new IllegalArgumentException();
            return new Release(version,commit,hash,size);
        } catch(IllegalArgumentException error){throw new IOException("베타 배포 정보가 올바르지 않습니다.",error);}
    }
    Path fetch(Release release,Path directory)throws IOException {
        Files.createDirectories(directory);Path pending=Files.createTempFile(directory,"beta-",".part");
        try {
            byte[] bytes=transport.read(release.jar(),Math.toIntExact(release.size()));
            if(bytes.length!=release.size())throw new IOException("다운로드 파일 크기가 일치하지 않습니다.");
            Files.write(pending,bytes);
            if(!RuntimeArtifact.hash(pending).equals(release.sha256()))throw new IOException("다운로드 SHA-256 검증에 실패했습니다.");
            RuntimeArtifact artifact=RuntimeArtifact.inspect(pending);
            try(var jar=new JarFile(pending.toFile())) {
                Properties p=new Properties();try(var in=jar.getInputStream(jar.getJarEntry("mud-runtime.properties"))){p.load(in);}
                if(!release.version().equals(artifact.version()) || !release.commit().equals(p.getProperty("commit")))
                    throw new IOException("배포 정보와 JAR의 버전 또는 커밋이 다릅니다.");
            }
            return pending;
        } catch(IOException|RuntimeException error){Files.deleteIfExists(pending);throw error;}
    }
    private static byte[] download(URI initial,int limit)throws IOException {
        URI current=initial;long deadline=System.nanoTime()+Duration.ofMinutes(2).toNanos();
        for(int hop=0;hop<6;hop++) {
            String host=current.getHost();
            if(!"https".equals(current.getScheme()) || current.getUserInfo()!=null || current.getPort()!=-1
                    || !Set.of("github.com","release-assets.githubusercontent.com","objects.githubusercontent.com").contains(host))
                throw new IOException("허용되지 않은 업데이트 다운로드 주소입니다.");
            HttpURLConnection connection=(HttpURLConnection)current.toURL().openConnection();
            connection.setConnectTimeout(10_000);connection.setReadTimeout(15_000);connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent","MC-Luck-Defense-Updater");
            connection.setRequestProperty("Accept-Encoding","identity");
            try {
                int status=connection.getResponseCode();
                if(status==301 || status==302 || status==303 || status==307 || status==308) {
                    String location=connection.getHeaderField("Location");if(location==null)throw new IOException("다운로드 리디렉션 주소가 없습니다.");
                    current=current.resolve(location);continue;
                }
                if(status!=200)throw new IOException(status==404?"공개된 베타 빌드가 아직 없습니다. 잠시 후 다시 시도하세요.":"GitHub 다운로드 실패 (HTTP "+status+")");
                if(connection.getContentLengthLong()>limit)throw new IOException("업데이트 파일이 허용 크기를 초과했습니다.");
                try(var input=connection.getInputStream();var output=new ByteArrayOutputStream(Math.min(limit,65536))) {
                    byte[] block=new byte[65536];int count;
                    while((count=input.read(block))!=-1) {
                        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("업데이트가 중단되었습니다.");
                        if(System.nanoTime()>deadline)throw new IOException("업데이트 다운로드 시간이 초과되었습니다.");
                        if((long)output.size()+count>limit)throw new IOException("업데이트 파일이 허용 크기를 초과했습니다.");
                        output.write(block,0,count);
                    }
                    return output.toByteArray();
                }
            } finally {connection.disconnect();}
        }
        throw new IOException("다운로드 리디렉션 횟수가 초과되었습니다.");
    }
}
