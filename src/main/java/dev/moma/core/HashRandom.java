package dev.moma.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.function.IntSupplier;
import java.util.random.RandomGenerator;

/** Versioned SHA-256 counter stream. One instance per game; not thread-safe. */
public final class HashRandom implements RandomGenerator, java.io.Serializable {
    private static final long serialVersionUID=1L;
    private record Saved(byte[] seed,long counter,byte[] remaining) implements java.io.Serializable {
        private Object readResolve() {
            HashRandom restored=new HashRandom(seed);restored.counter=counter;restored.block=ByteBuffer.wrap(remaining);return restored;
        }
    }
    private Object writeReplace() {
        byte[] remaining=new byte[block.remaining()];block.duplicate().get(remaining);
        return new Saved(seed.clone(),counter,remaining);
    }
    public byte[] stateBytes() {
        ByteBuffer state=ByteBuffer.allocate(seed.length+Long.BYTES+block.remaining());
        state.put(seed).putLong(counter).put(block.duplicate());return state.array();
    }
    public static final String ALGORITHM = "sha256-counter-v1";
    private static final byte[] DOMAIN = "moma-defense/sha256-counter/v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom SEED_SOURCE = new SecureRandom();
    private final MessageDigest digest;
    private final byte[] seed;
    private final ByteBuffer counterBytes = ByteBuffer.allocate(Long.BYTES);
    private ByteBuffer block = ByteBuffer.allocate(0);
    private long counter;

    /** Simulation seeds use all 64 bits, big-endian, followed by 24 zero bytes. */
    public HashRandom(long seed) { this(ByteBuffer.allocate(32).putLong(seed).array()); }
    public HashRandom(byte[] seed) {
        if (seed.length != 32) throw new IllegalArgumentException("Expected a 256-bit seed");
        this.seed = seed.clone();
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }
    public static HashRandom secure() {
        byte[] seed = new byte[32];
        SEED_SOURCE.nextBytes(seed);
        return new HashRandom(seed);
    }
    @Override public int nextInt() {
        if (!block.hasRemaining()) {
            counterBytes.clear(); counterBytes.putLong(counter);
            counter = Math.incrementExact(counter);
            digest.update(DOMAIN); digest.update(seed);
            block = ByteBuffer.wrap(digest.digest(counterBytes.array()));
        }
        return block.getInt();
    }
    @Override public long nextLong() {
        return (long) nextInt() << 32 | Integer.toUnsignedLong(nextInt());
    }
    @Override public int nextInt(int bound) { return boundedInt(bound, this::nextInt); }

    /** Reject the incomplete bucket at the top of the unsigned 32-bit range. */
    static int boundedInt(int bound, IntSupplier words) {
        if (bound <= 0) throw new IllegalArgumentException("Bound must be positive");
        long size = 1L << 32, limit = size - size % bound, value;
        do { value = Integer.toUnsignedLong(words.getAsInt()); } while (value >= limit);
        return (int) (value % bound);
    }
}
