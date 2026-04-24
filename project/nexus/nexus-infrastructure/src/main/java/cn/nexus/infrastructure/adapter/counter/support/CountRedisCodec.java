package cn.nexus.infrastructure.adapter.counter.support;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Fixed-width slot codec used by Count Redis snapshots.
 */
public final class CountRedisCodec {

    private static final long UINT32_MAX = 0xFFFF_FFFFL;

    private CountRedisCodec() {
    }

    public static byte[] encodeSlots(long[] values, int slotCount) {
        int count = Math.max(0, slotCount);
        byte[] bytes = new byte[count * CountRedisSchema.SLOT_WIDTH_BYTES];
        for (int slot = 0; slot < count; slot++) {
            long value = values != null && slot < values.length ? clampNonNegative(values[slot]) : 0L;
            writeSlot(bytes, slot, value);
        }
        return bytes;
    }

    public static long[] decodeSlots(byte[] bytes, int slotCount) {
        int count = Math.max(0, slotCount);
        long[] values = new long[count];
        if (bytes == null) {
            return values;
        }
        for (int slot = 0; slot < count; slot++) {
            values[slot] = readSlot(bytes, slot);
        }
        return values;
    }

    public static String toRedisValue(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes == null ? new byte[0] : bytes);
    }

    public static byte[] fromRedisValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return new byte[0];
        }
        return Base64.getDecoder().decode(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static long clampNonNegative(long value) {
        if (value <= 0L) {
            return 0L;
        }
        return Math.min(UINT32_MAX, value);
    }

    private static void writeSlot(byte[] bytes, int slot, long value) {
        int start = slot * CountRedisSchema.SLOT_WIDTH_BYTES;
        for (int i = CountRedisSchema.SLOT_WIDTH_BYTES - 1; i >= 0; i--) {
            bytes[start + i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
    }

    private static long readSlot(byte[] bytes, int slot) {
        int start = slot * CountRedisSchema.SLOT_WIDTH_BYTES;
        int end = start + CountRedisSchema.SLOT_WIDTH_BYTES;
        if (start < 0 || end > bytes.length) {
            return 0L;
        }
        long value = 0L;
        for (int i = start; i < end; i++) {
            value = (value << 8) | (bytes[i] & 0xFFL);
        }
        return value;
    }
}
