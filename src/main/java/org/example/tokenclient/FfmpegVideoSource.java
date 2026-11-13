package org.example.tokenclient;

import java.nio.ByteBuffer;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import static org.bytedeco.ffmpeg.global.avutil.*;

/** Created, read and released by the capture worker; reads have a finite I/O timeout. */
final class FfmpegVideoSource implements LiveScanService.FrameSource {
    private final FFmpegFrameGrabber grabber;
    private final FrameBufferPool buffers = new FrameBufferPool();
    FfmpegVideoSource(DouyinStreamResolver.Source source) throws Exception {
        this(source, decoderThreads());
    }
    FfmpegVideoSource(DouyinStreamResolver.Source source, int decoderThreads) throws Exception {
        if (decoderThreads < 0 || decoderThreads > 16) throw new IllegalArgumentException("Decoder threads must be 0..16");
        // FFmpeg's default diagnostics include signed stream URLs. Keep them out of application logs.
        av_log_set_level(AV_LOG_QUIET);
        grabber = new FFmpegFrameGrabber(source.url());
        grabber.setPixelFormat(AV_PIX_FMT_BGR24);
        grabber.setOption("rw_timeout", "5000000");
        grabber.setVideoOption("threads", Integer.toString(decoderThreads));
        // Slice threading avoids the extra frame queue introduced by FFmpeg frame threading.
        grabber.setVideoOption("thread_type", "slice");
        if (source.url().startsWith("http")) {
            grabber.setOption("fflags", "nobuffer");
            grabber.setOption("analyzeduration", "500000");
            grabber.setOption("probesize", "32768");
            StringBuilder headers = new StringBuilder();
            source.headers().forEach((k,v) -> {
                if (k.indexOf('\n') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) throw new IllegalArgumentException("Invalid stream header");
                headers.append(k).append(": ").append(v).append("\r\n");
            });
            grabber.setOption("headers", headers.toString());
        }
        try { grabber.start(); }
        catch (Exception | LinkageError e) { try { grabber.release(); } catch (Exception ignored) { } throw e; }
    }
    @Override public VideoFrame read() throws Exception {
        var frame = grabber.grabImage();
        if (frame == null) return null;
        if (frame.imageWidth < 1 || frame.imageHeight < 1 || frame.imageWidth > 7680 || frame.imageHeight > 4320)
            throw new IllegalStateException("Unsupported video dimensions");
        int size = Math.multiplyExact(frame.imageStride, frame.imageHeight);
        VideoFrame copy = buffers.acquire(frame.imageWidth, frame.imageHeight, frame.imageStride,
                grabber.getTimestamp(), System.nanoTime());
        try {
            ByteBuffer buffer = ((ByteBuffer)frame.image[0]).duplicate();
            buffer.position(0).limit(size);
            copy.pixels().asByteBuffer().put(buffer);
            return copy;
        } catch (RuntimeException | Error e) { copy.close(); throw e; }
    }
    static int decoderThreads() {
        String value = System.getProperty("app.scan.decoderThreads", "2");
        if (value.equalsIgnoreCase("auto")) return 0;
        try {
            int threads = Integer.parseInt(value);
            if (threads >= 0 && threads <= 16) return threads;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("app.scan.decoderThreads must be auto or 0..16");
    }
    @Override public void close() throws Exception {
        try { grabber.close(); } finally { buffers.close(); }
    }
}
