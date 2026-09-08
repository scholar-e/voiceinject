package com.voiceinject;

import java.util.Arrays;

public final class RecordingBufferTest {
    public static void main(String[] args) {
        RecordingBuffer buffer = new RecordingBuffer(4, 12);
        buffer.append(new byte[]{1, 2, 3, 4, 5, 6}, 6);
        buffer.start();
        buffer.append(new byte[]{7, 8}, 2);
        // Tail audio arriving after the stop request must be included before finish.
        buffer.append(new byte[]{9, 10}, 2);
        check(buffer.finish(), new byte[]{3, 4, 5, 6, 7, 8, 9, 10}, "pre-roll order and tail");
        buffer.discard();
        buffer.start();
        buffer.append(new byte[]{11, 12}, 2);
        check(buffer.finish(), new byte[]{11, 12}, "discard clears old audio");
        buffer.discard();
        buffer.start();
        buffer.append(new byte[20], 20);
        if (!buffer.full() || buffer.finish().length != 12) throw new AssertionError("recording cap");
        buffer.discard();
        buffer.append(new byte[]{1, 2}, 2);
        buffer.start();
        check(buffer.finish(), new byte[]{1, 2}, "partially filled pre-roll");
        System.out.println("Recording buffer tests passed");
    }
    private static void check(byte[] actual, byte[] expected, String label) {
        if (!Arrays.equals(actual, expected)) throw new AssertionError(label);
    }
}
