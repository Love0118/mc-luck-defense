package dev.moma.runtime;

import dev.moma.core.HashRandom;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Only in-process snapshots produced by our runtime are accepted. No player-supplied serialization. */
public final class StateCodec {
    private static final int MAX_BYTES=64*1024*1024;
    private StateCodec() {}
    public static byte[] write(Serializable state)throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var output=new ObjectOutputStream(bytes)){output.writeObject(state);}
        if(bytes.size()>MAX_BYTES)throw new IOException("세션 백업이 허용 크기를 초과했습니다.");
        return bytes.toByteArray();
    }
    public static <T> T read(byte[] bytes,Class<T> type)throws IOException,ClassNotFoundException {
        if(bytes.length>MAX_BYTES)throw new IOException("Oversized snapshot");
        try(var input=new ObjectInputStream(new ByteArrayInputStream(bytes)) {
            @Override protected Class<?> resolveClass(ObjectStreamClass desc)throws ClassNotFoundException {
                return Class.forName(desc.getName(),false,StateCodec.class.getClassLoader());
            }
        }) {
            input.setObjectInputFilter(info->{
                if(info.depth()>80 || info.references()>2_000_000 || info.arrayLength()>MAX_BYTES)return ObjectInputFilter.Status.REJECTED;
                Class<?> c=info.serialClass();if(c==null)return ObjectInputFilter.Status.UNDECIDED;
                while(c.isArray())c=c.getComponentType();String n=c.getName();
                return c.isPrimitive() || n.startsWith("dev.moma.core.") || n.startsWith("dev.moma.runtime.")
                        || n.startsWith("dev.moma.paper.SessionState$") || n.startsWith("dev.moma.paper.BgmService$")
                        || n.equals("dev.moma.bgm.BgmTimeline") || n.equals("dev.moma.bgm.Track")
                        || n.startsWith("java.lang.") || n.startsWith("java.util.")
                        ? ObjectInputFilter.Status.ALLOWED:ObjectInputFilter.Status.REJECTED;
            });
            return type.cast(input.readObject());
        }
    }
    /** Field-value digest ignores object sharing, but includes private combat timers and RNG state. */
    public static String fingerprint(Object state) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");append(digest,state);
            return HexFormat.of().formatHex(digest.digest());
        } catch(ReflectiveOperationException|NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static void text(MessageDigest digest,String value) {
        byte[] data=value.getBytes(StandardCharsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(data.length).array());digest.update(data);
    }
    private static void append(MessageDigest digest,Object object)throws ReflectiveOperationException {
        if(object==null){text(digest,"null");return;}
        Class<?> type=object.getClass();
        if(object instanceof Map<?,?> map){text(digest,"map:"+map.size());for(var entry:map.entrySet()){append(digest,entry.getKey());append(digest,entry.getValue());}return;}
        if(object instanceof Collection<?> list){text(digest,"list:"+list.size());for(Object item:list)append(digest,item);return;}
        text(digest,type.getName());
        if(object instanceof HashRandom random){append(digest,random.stateBytes());return;}
        if(object instanceof String || object instanceof Number || object instanceof Boolean || object instanceof Character || object instanceof UUID || object instanceof Enum<?>){text(digest,object.toString());return;}
        if(type.isArray()){int size=Array.getLength(object);text(digest,"size:"+size);for(int i=0;i<size;i++)append(digest,Array.get(object,i));return;}
        for(ObjectStreamField serial:ObjectStreamClass.lookup(type).getFields()) {
            Field field=type.getDeclaredField(serial.getName());field.setAccessible(true);text(digest,field.getName());append(digest,field.get(object));
        }
    }
}
