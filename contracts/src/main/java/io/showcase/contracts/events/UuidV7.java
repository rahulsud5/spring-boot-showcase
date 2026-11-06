package io.showcase.contracts.events;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUID version 7 generator (RFC 9562): a 48-bit Unix-millisecond timestamp, then random bits.
 *
 * <p><b>Why it matters for databases:</b> random UUIDv4 primary keys scatter inserts across the
 * whole B-tree index, causing page splits and poor cache locality. UUIDv7 values are
 * <i>time-ordered</i>, so new rows land at the "right edge" of the index, like a sequence, while
 * staying globally unique and generable WITHOUT a database round trip (useful for the outbox and
 * for idempotent clients).
 *
 * <pre>
 *  0                   1                   2                   3
 *  | unix_ts_ms (48 bits)                        | ver=7 | rand_a (12) |
 *  | var=10 | rand_b (62 bits)                                         |
 * </pre>
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID next() {
        long timestamp = System.currentTimeMillis();
        long randA = RANDOM.nextInt(1 << 12);
        long msb = (timestamp << 16) | (0x7L << 12) | randA;           // timestamp | version 7 | rand_a
        long lsb = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL)
                | 0x8000_0000_0000_0000L;                               // variant bits "10" + rand_b
        return new UUID(msb, lsb);
    }

    /** The creation time encoded in a v7 UUID. Handy for debugging and partition pruning. */
    public static long timestampMillis(UUID uuid) {
        return uuid.getMostSignificantBits() >>> 16;
    }
}
