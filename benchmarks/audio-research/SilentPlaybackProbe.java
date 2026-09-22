package probe;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.*;
import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.AL11.*;
import static org.lwjgl.openal.ALC10.*;
import static org.lwjgl.openal.SOFTLoopback.*;

/** Offline probe using the unmodified Minecraft 1.21.8 Channel and Vorbis decoder. */
public final class SilentPlaybackProbe {
    private static final Class<?> CHANNEL, VECTOR, STREAM;
    static {
        try {CHANNEL=Class.forName("flh");VECTOR=Class.forName("fis");STREAM=Class.forName("hwk");}
        catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    private static Object call(Object channel,String name,Class<?>[] types,Object... args)throws Exception {
        var method=CHANNEL.getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(channel,args);
    }
    private static void position(Object channel,double x)throws Exception {
        call(channel,"a",new Class<?>[]{VECTOR},VECTOR.getConstructor(double.class,double.class,double.class).newInstance(x,0d,0d));
    }
    private static final int RATE=48000;
    private static double render(long device,Object channel,int frames)throws Exception {
        double sum=0;int samples=0;
        while(frames>0) {
            int count=Math.min(480,frames);FloatBuffer output=BufferUtils.createFloatBuffer(count*2);
            alcRenderSamplesSOFT(device,output,count);call(channel,"j",new Class<?>[]{});
            for(int i=0;i<count*2;i++){double value=output.get(i);sum+=value*value;samples++;}
            frames-=count;
        }
        return Math.sqrt(sum/samples);
    }
    private static String run(long device,Path audio)throws Exception {
        Object channel=call(null,"a",new Class<?>[]{});if(channel==null)throw new IllegalStateException("No audio channel");
        try {
            Object stream=Class.forName("hwp").getConstructor(java.io.InputStream.class).newInstance(Files.newInputStream(audio));
            var format=(javax.sound.sampled.AudioFormat)stream.getClass().getMethod("a").invoke(stream);
            call(channel,"a",new Class<?>[]{float.class},1f);call(channel,"b",new Class<?>[]{float.class},1f);
            call(channel,"a",new Class<?>[]{boolean.class},false);call(channel,"b",new Class<?>[]{boolean.class},false);call(channel,"c",new Class<?>[]{float.class},16f);
            position(channel,128);call(channel,"a",new Class<?>[]{STREAM},stream);call(channel,"c",new Class<?>[]{});
            var sourceField=CHANNEL.getDeclaredField("d");sourceField.setAccessible(true);int source=sourceField.getInt(channel);
            double far=render(device,channel,3*RATE);
            float before=alGetSourcef(source,AL_SEC_OFFSET);
            position(channel,0);
            float after=alGetSourcef(source,AL_SEC_OFFSET);
            double near=render(device,channel,RATE/2);
            if(!(boolean)call(channel,"g",new Class<?>[]{}) || Math.abs(before-after)>0.00001 || near<0.01)throw new AssertionError("Continuity failed");
            if(format.getChannels()==1 && far>0.00001)throw new AssertionError("Mono source was audible at distance");
            if(format.getChannels()==2 && far<0.01)throw new AssertionError("Stereo control unexpectedly attenuated");
            return String.format(Locale.ROOT,"{\"channels\":%d,\"farRms\":%.9f,\"nearRms\":%.9f,\"renderedBeforeRevealSeconds\":3,\"offsetBeforeMove\":%.6f,\"offsetAfterMove\":%.6f,\"playCalls\":1,\"stillPlaying\":true}",format.getChannels(),far,near,before,after);
        } finally {call(channel,"b",new Class<?>[]{});}
    }
    public static void main(String[] args)throws Exception {
        long device=alcLoopbackOpenDeviceSOFT((ByteBuffer)null);
        if(device==0)throw new IllegalStateException("No loopback device");
        ALCCapabilities capabilities=ALC.createCapabilities(device);
        long context=alcCreateContext(device,new int[]{ALC_FREQUENCY,RATE,ALC_FORMAT_CHANNELS_SOFT,ALC_STEREO_SOFT,ALC_FORMAT_TYPE_SOFT,ALC_FLOAT_SOFT,0});
        if(context==0)throw new IllegalStateException("No loopback context");
        alcMakeContextCurrent(context);AL.createCapabilities(capabilities);
        alDistanceModel(AL_LINEAR_DISTANCE_CLAMPED);alListener3f(AL_POSITION,0,0,0);alListenerf(AL_GAIN,1);
        try {
            String report="{\"minecraft\":\"1.21.8\",\"test\":\"unmodified-client-audio-channel-loopback\",\"networkClientTest\":false,\"results\":["+run(device,Path.of(args[0]))+","+run(device,Path.of(args[1]))+"]}";
            Files.writeString(Path.of(args[2]),report+"\n");System.out.println(report);
        } finally {alcMakeContextCurrent(0);alcDestroyContext(context);alcCloseDevice(device);}
    }
}
