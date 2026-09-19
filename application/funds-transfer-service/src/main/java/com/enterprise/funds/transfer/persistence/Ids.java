package com.enterprise.funds.transfer.persistence;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * Identifier helpers. All keys are RAW(16) (decision: UUIDs stored as RAW(16)); ids are time-ordered
 * UUIDv7 generated here (design P2), so index inserts stay near the right edge of the B-tree.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {}

    public static UUID newId() {
        return uuidV7(System.currentTimeMillis());
    }

    /** RFC 9562 UUIDv7: 48-bit unix millis, version 7, 74 random bits (SecureRandom: ids are exposed on the API). */
    static UUID uuidV7(long unixMillis) {
        byte[] r = new byte[10];
        RANDOM.nextBytes(r);
        long msb = (unixMillis & 0xFFFFFFFFFFFFL) << 16;
        msb |= 0x7000L | ((r[0] & 0x0FL) << 8) | (r[1] & 0xFFL);
        long lsb = 0;
        for (int i = 2; i < 10; i++) {
            lsb = (lsb << 8) | (r[i] & 0xFFL);
        }
        lsb = (lsb & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }

    public static byte[] toBytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    public static UUID fromBytes(byte[] raw) {
        if (raw == null) {
            return null;
        }
        if (raw.length != 16) {
            throw new IllegalArgumentException("RAW(16) expected, got " + raw.length + " bytes");
        }
        ByteBuffer buf = ByteBuffer.wrap(raw);
        return new UUID(buf.getLong(), buf.getLong());
    }
}
