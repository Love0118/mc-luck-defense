package dev.moma.core;

import java.nio.ByteBuffer;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HashRandomTest {
    @Test void matchesIndependentPythonSha256VectorsAcrossBlockBoundaries() {
        long[] seeds = {0, 1, -1, 1L << 48};
        String[] expected = {
            "483eea1a3606ee1a284e14bf433bf7fe3d5f2cdfe90304b83039ada69ac46b97edbf6761e7722df0098a2a4650e83071b6aa143a43d8dbf396a92dbd2ea4bfc6",
            "75752e4da07c5ad9da1f70b2609597c1ff654632e8eb46db58d8d18acb9d1a36a233b8b8a4d602d29002108fcda1bc552901bc99af4955dae41685aa6d049370",
            "9fa3e681d1a55330a1bf5c1154da0a172491adcb91cda6c262bdaa45582c8baa49ee27333363b2b2826793cd80b7dc0ea13cba856a6afc9beca672a4c408e30f",
            "750d41d61f7b982d119b8b85e8d18c410203437362ddfee0f33feba84cc18ce717fcae233916acc03c1ae55636a53988b31a4bbe144d647fa79cf57da298f86f"};
        for (int i = 0; i < seeds.length; i++) {
            var random = new HashRandom(seeds[i]);
            var bytes = ByteBuffer.allocate(64);
            for (int j = 0; j < 16; j++) bytes.putInt(random.nextInt());
            assertEquals(expected[i], HexFormat.of().formatHex(bytes.array()));
        }
    }
    @Test void rejectionSkipsTheIncompleteBucketIncludingUnsignedBoundary() {
        for (int bound : new int[]{24, 100_000, Integer.MAX_VALUE}) {
            long size = 1L << 32, limit = size - size % bound;
            int[] words = {(int) limit, -1, (int) (limit - 1), 0};
            int[] cursor = {0};
            assertEquals(bound - 1, HashRandom.boundedInt(bound, () -> words[cursor[0]++]));
            assertEquals(3, cursor[0]);
            assertEquals(0, HashRandom.boundedInt(bound, () -> words[cursor[0]++]));
        }
        assertEquals(15, HashRandom.boundedInt(16, () -> -1));
        assertEquals(0, HashRandom.boundedInt(1, () -> -1));
        assertThrows(IllegalArgumentException.class, () -> new HashRandom(0).nextInt(0));
        assertThrows(IllegalArgumentException.class, () -> new HashRandom(0).nextInt(-1));
    }
    @Test void streamIsReproducibleIsolatedAndDoesNotRetainMutableSeed() {
        byte[] seed = ByteBuffer.allocate(32).putLong(42).array();
        var first = new HashRandom(seed); seed[0]++;
        var second = new HashRandom(42);
        var unrelated = new HashRandom(99);
        for (int i = 0; i < 10_000; i++) {
            unrelated.nextLong();
            assertEquals(first.nextInt(100_000), second.nextInt(100_000));
            assertEquals(first.nextInt(24), second.nextInt(24));
        }
        assertThrows(IllegalArgumentException.class, () -> new HashRandom(new byte[8]));
    }
}
