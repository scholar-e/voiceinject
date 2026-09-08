package com.voiceinject;

import java.io.ByteArrayOutputStream;

/** PCM buffer with bounded pre-roll; all access is serialized by the capture owner. */
final class RecordingBuffer {
    private final byte[] preRoll;
    private final int maximum;
    private final ByteArrayOutputStream recording = new ByteArrayOutputStream();
    private int cursor;
    private int filled;
    private boolean active;

    RecordingBuffer(int preRollBytes, int maximumBytes) {
        preRoll = new byte[preRollBytes];
        maximum = maximumBytes;
    }

    void start() {
        recording.reset();
        int first = (cursor - filled + preRoll.length) % preRoll.length;
        for (int i = 0; i < filled; i++) recording.write(preRoll[(first + i) % preRoll.length]);
        active = true;
    }

    void append(byte[] bytes, int count) {
        if (active) recording.write(bytes, 0, Math.min(count, maximum - recording.size()));
        for (int i = 0; i < count; i++) {
            preRoll[cursor] = bytes[i];
            cursor = (cursor + 1) % preRoll.length;
        }
        filled = Math.min(preRoll.length, filled + count);
    }

    boolean full() { return active && recording.size() >= maximum; }

    byte[] finish() {
        active = false;
        byte[] result = recording.toByteArray();
        recording.reset();
        return result;
    }

    void discard() {
        active = false;
        recording.reset();
        filled = 0;
        cursor = 0;
        java.util.Arrays.fill(preRoll, (byte) 0);
    }
}
