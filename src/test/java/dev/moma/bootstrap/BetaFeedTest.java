package dev.moma.bootstrap;

import java.io.*;
import java.nio.file.*;
import java.util.jar.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BetaFeedTest {
    @TempDir Path dir;
    static final String COMMIT="a".repeat(40);
    static byte[] jar(String commit)throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var jar=new JarOutputStream(bytes)) {
            jar.putNextEntry(new JarEntry("mud-runtime.properties"));
            jar.write(("host-api=1\nstate-schema=1\nversion=0.17.0\ncommit="+commit+"\n").getBytes());jar.closeEntry();
            jar.putNextEntry(new JarEntry("plugin.yml"));jar.write("name: MCLuckDefense\nmain: dev.moma.paper.MomaPlugin\nversion: 0.17.0\n".getBytes());jar.closeEntry();
        }
        return bytes.toByteArray();
    }
    String manifest(byte[] bytes)throws Exception {
        Path file=dir.resolve("source.jar");Files.write(file,bytes);
        return "format=1\nrepository="+BetaFeed.REPOSITORY+"\nbranch=beta\ncommit="+COMMIT+"\nversion=0.17.0\nsha256="+RuntimeArtifact.hash(file)+"\nsize="+bytes.length+"\n";
    }
    @Test void pinsDownloadsToManifestCommitAndChecksJarProvenance()throws Exception {
        byte[] jar=jar(COMMIT);String manifest=manifest(jar);
        var feed=new BetaFeed((uri,limit)->{
            if(uri.equals(BetaFeed.MANIFEST))return manifest.getBytes();
            assertEquals("https://github.com/"+BetaFeed.REPOSITORY+"/releases/download/beta-build-"+COMMIT+"/MCLuckDefense.jar",uri.toString());
            assertEquals(jar.length,limit);return jar;
        });
        var release=feed.latest();assertEquals("0.17.0 · aaaaaaaa",release.label());
        assertArrayEquals(jar,Files.readAllBytes(feed.fetch(release,dir.resolve("downloads"))));
    }
    @Test void rejectsWrongRepositoryBranchVersionCommitHashAndSize()throws Exception {
        String valid=manifest(jar(COMMIT));
        for(String invalid:new String[]{valid.replace("branch=beta","branch=main"),valid.replace(BetaFeed.REPOSITORY,"other/repo"),
                valid.replace(COMMIT,"../../escape"),valid.replace("version=0.17.0","version=no"),
                valid.replaceFirst("sha256=[a-f0-9]+","sha256=bad"),valid.replaceFirst("size=[0-9]+","size=0"),
                valid.replaceFirst("size=[0-9]+","size=9999999999"),valid.replace("format=1","format=2")})
            assertThrows(IOException.class,()->new BetaFeed((u,n)->invalid.getBytes()).latest());
    }
    @Test void removesPartialFilesForBadChecksumSizeCommitVersionAndInvalidJar()throws Exception {
        byte[] valid=jar(COMMIT);String good=manifest(valid);
        for(int kind=0;kind<5;kind++) {
            byte[] downloaded=switch(kind){case 1->new byte[1];case 2->jar("b".repeat(40));case 4->"not a jar".getBytes();default->valid.clone();};
            String pointer=kind==2 || kind==4?manifest(downloaded):good;
            if(kind==0)downloaded[20]^=1;
            if(kind==3)pointer=pointer.replace("version=0.17.0","version=0.18.0");
            byte[] body=downloaded;String metadata=pointer;
            var feed=new BetaFeed((u,n)->u.equals(BetaFeed.MANIFEST)?metadata.getBytes():body);
            var release=feed.latest();Path downloads=dir.resolve("fail-"+kind);
            assertThrows(IOException.class,()->feed.fetch(release,downloads));
            try(var files=Files.list(downloads)){assertEquals(0,files.count());}
        }
    }
}
