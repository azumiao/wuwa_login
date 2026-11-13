package org.example.tokenclient;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrameBufferPoolTest {
    @Test void leasesAreExclusiveBoundedAndReusedWithoutPixelAllocations() {
        try (var pool = new FrameBufferPool();
             var scanning = pool.acquire(4, 4, 12, 0, 0);
             var pending = pool.acquire(4, 4, 12, 1, 1)) {
            scanning.pixels().put(0, (byte) 7);
            pending.pixels().put(0, (byte) 9);
            long reusable;
            try (var capture = pool.acquire(4, 4, 12, 2, 2)) {
                reusable = capture.pixels().address();
                assertThrows(IllegalStateException.class, () -> pool.acquire(4, 4, 12, 3, 3));
            }
            for (int i = 0; i < 1000; i++) {
                try (var capture = pool.acquire(4, 4, 12, i, i)) {
                    assertEquals(reusable, capture.pixels().address());
                    capture.pixels().put(0, (byte) i);
                }
            }
            assertEquals(3, pool.allocations());
            assertEquals(2, pool.outstanding());
            assertEquals(7, scanning.pixels().get(0));
            assertEquals(9, pending.pixels().get(0));
        }
    }

    @Test void closeDefersFreeingInFlightMemoryAndLeaseReturnIsIdempotent() {
        var pool = new FrameBufferPool();
        var frame = pool.acquire(2, 2, 6, 0, 0);
        var pointer = frame.pixels();
        pointer.put(0, (byte) 42);
        pool.close(); pool.close();
        assertEquals(42, frame.pixels().get(0));
        assertEquals(1, pool.outstanding());
        assertThrows(IllegalStateException.class, () -> pool.acquire(2, 2, 6, 0, 0));
        frame.close(); frame.close();
        assertEquals(0, pool.outstanding());
        assertTrue(pointer.isNull());
        assertThrows(IllegalStateException.class, frame::pixels);
    }

    @Test void resolutionChangesReplaceIdleMemoryWhileRetainedFramesRemainValid() {
        try (var pool = new FrameBufferPool(); var retained = pool.acquire(2, 2, 6, 0, 0)) {
            retained.pixels().put(0, (byte) 42);
            try (var small = pool.acquire(2, 2, 6, 1, 1)) { }
            try (var large = pool.acquire(8, 8, 24, 2, 2)) {
                assertEquals(192, large.pixels().limit());
                large.pixels().put(191, (byte) 11);
                assertEquals(42, retained.pixels().get(0));
            }
            try (var small = pool.acquire(2, 2, 6, 3, 3)) {
                assertEquals(12, small.pixels().limit());
                assertEquals(3, pool.allocations(), "Shrinking reuses the larger allocation");
            }
        }
    }
}
