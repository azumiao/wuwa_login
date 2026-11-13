package org.example.tokenclient;

import java.util.ArrayDeque;
import org.bytedeco.javacpp.BytePointer;

/** Three leases cover capture, the latest pending frame, and recognition. Never waits for a lease. */
final class FrameBufferPool implements AutoCloseable {
    private final ArrayDeque<BytePointer> idle = new ArrayDeque<>();
    private int buffers, allocations;
    private boolean closed;

    synchronized VideoFrame acquire(int width, int height, int stride, long ptsUs, long decodedAtNs) {
        if (closed) throw new IllegalStateException("Frame pool is closed");
        int size = Math.multiplyExact(stride, height);
        if (width < 1 || height < 1 || stride < Math.multiplyExact(width, 3))
            throw new IllegalArgumentException("Invalid BGR dimensions");
        BytePointer pixels = idle.pollFirst();
        if (pixels != null && pixels.capacity() < size) {
            pixels.close(); buffers--; pixels = null;
        }
        if (pixels == null) {
            if (buffers == 3) throw new IllegalStateException("All frame buffers are in use");
            pixels = new BytePointer(size);
            buffers++; allocations++;
        }
        pixels.position(0).limit(size);
        return new VideoFrame(width, height, stride, pixels, ptsUs, decodedAtNs, this::release);
    }

    private synchronized void release(BytePointer pixels) {
        if (closed) { pixels.close(); buffers--; }
        else idle.addLast(pixels);
    }

    synchronized int allocations() { return allocations; }
    synchronized int outstanding() { return buffers - idle.size(); }

    @Override public synchronized void close() {
        closed = true;
        while (!idle.isEmpty()) { idle.removeFirst().close(); buffers--; }
        // In-flight recognition owns its lease until decode returns, even after a reconnect or stop.
    }
}
