package probe;

import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Decode the first second through Minecraft's unmodified 1.21.8 Vorbis implementation. */
public final class AudioMetadataProbe {
    private static String firstSecond(Path path)throws Exception {
        var type=Class.forName("hwp");var stream=type.getConstructor(java.io.InputStream.class).newInstance(Files.newInputStream(path));
        try {
            ByteBuffer pcm=(ByteBuffer)type.getMethod("a",int.class).invoke(stream,96000);
            byte[] bytes=new byte[96000];pcm.get(bytes);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } finally {type.getMethod("close").invoke(stream);}
    }
    public static void main(String[] args)throws Exception {
        String expected=firstSecond(Path.of(args[0]));
        for(int i=1;i<args.length;i++) {
            String actual=firstSecond(Path.of(args[i]));
            if(!expected.equals(actual))throw new AssertionError("Audio changed: "+args[i]);
            System.out.println(Path.of(args[i]).getFileName()+": first second unchanged sha256="+actual);
        }
    }
}
