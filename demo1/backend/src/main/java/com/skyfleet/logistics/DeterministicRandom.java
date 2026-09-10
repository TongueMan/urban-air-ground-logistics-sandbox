package com.skyfleet.logistics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class DeterministicRandom {
    public static final String VERSION = "splitmix64-sha256/1";
    private long state;

    public DeterministicRandom(long seed) { this.state = seed; }

    public long nextLong() {
        long z = (state += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public int nextInt(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
        return (int) Long.remainderUnsigned(nextLong(), bound);
    }

    public boolean nextBoolean() { return (nextLong() & 1L) == 0; }

    public double nextDouble() { return (nextLong() >>> 11) * 0x1.0p-53; }

    public static long derive(String unsignedSeed, String domain) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((VERSION + "\0" + unsignedSeed + "\0" + domain).getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int index = 0; index < 8; index++) value = (value << 8) | (bytes[index] & 0xffL);
            return value;
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
