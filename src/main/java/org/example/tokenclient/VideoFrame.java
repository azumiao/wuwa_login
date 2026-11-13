package org.example.tokenclient;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.bytedeco.javacpp.BytePointer;

/** An exclusive lease on BGR memory, independent of FFmpeg's reusable frame. */
final class VideoFrame implements AutoCloseable {
    private final int width, height, stride;
    private final BytePointer pixels;
    private final long ptsUs, decodedAtNs;
    private final Consumer<BytePointer> release;
    private final AtomicBoolean closed = new AtomicBoolean();

    VideoFrame(int width, int height, int stride, byte[] bgr, long ptsUs, long decodedAtNs) {
        this(width, height, stride, new BytePointer(bgr), ptsUs, decodedAtNs, BytePointer::close);
    }

    VideoFrame(int width, int height, int stride, BytePointer pixels, long ptsUs, long decodedAtNs,
               Consumer<BytePointer> release) {
        this.width = width; this.height = height; this.stride = stride; this.pixels = pixels;
        this.ptsUs = ptsUs; this.decodedAtNs = decodedAtNs; this.release = release;
    }

    int width() { return width; }
    int height() { return height; }
    int stride() { return stride; }
    long ptsUs() { return ptsUs; }
    long decodedAtNs() { return decodedAtNs; }
    BytePointer pixels() {
        if (closed.get()) throw new IllegalStateException("Frame has been released");
        return pixels;
    }

    static VideoFrame fromImage(BufferedImage image) {
        BufferedImage bgr = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        var graphics = bgr.createGraphics();
        try { graphics.drawImage(image, 0, 0, null); } finally { graphics.dispose(); }
        return new VideoFrame(bgr.getWidth(), bgr.getHeight(), bgr.getWidth() * 3,
                ((DataBufferByte)bgr.getRaster().getDataBuffer()).getData(), 0, System.nanoTime());
    }
    @Override public void close() { if (closed.compareAndSet(false, true)) release.accept(pixels); }
    @Override public String toString() { return "VideoFrame[" + width + "x" + height + ", ptsUs=" + ptsUs + "]"; }
}
