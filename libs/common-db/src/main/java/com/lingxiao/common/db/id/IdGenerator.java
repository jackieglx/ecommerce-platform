package com.lingxiao.common.db.id;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * Provides monotonic-friendly ULID and random UUID helpers.
 */
public class IdGenerator {

    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    public String newUuid() {
        return UUID.randomUUID().toString();
    }

    public String newUlid() {
        long time = Instant.now().toEpochMilli();
        byte[] randomness = new byte[10];
        RANDOM.nextBytes(randomness);

        byte[] data = new byte[16];
        // 48-bit timestamp
        data[0] = (byte) (time >>> 40);
        data[1] = (byte) (time >>> 32);
        data[2] = (byte) (time >>> 24);
        data[3] = (byte) (time >>> 16);
        data[4] = (byte) (time >>> 8);
        data[5] = (byte) (time);
        System.arraycopy(randomness, 0, data, 6, 10);

        return encodeBase32(data);
    }

    private String encodeBase32(byte[] data) {
        BigInteger value = new BigInteger(1, data);
        BigInteger base = BigInteger.valueOf(32);
        char[] out = new char[26];
        for (int i = 25; i >= 0; i--) {
            BigInteger[] divRem = value.divideAndRemainder(base);
            out[i] = CROCKFORD[divRem[1].intValue()];
            value = divRem[0];
        }
        return new String(out);
    }
}

