package org.example.tokenclient;

import java.nio.ByteBuffer;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import static org.bytedeco.ffmpeg.global.avutil.*;

/** Created, read and released by the capture worker; reads have a finite I/O timeout. */
final class FfmpegVideoSource implements LiveScanService.FrameSource {
    private final FFmpegFrameGrabber grabber;
    FfmpegVideoSource(DouyinStreamResolver.Source source) throws Exception {
        // FFmpeg's default diagnostics include signed stream URLs. Keep them out of application logs.
        av_log_set_level(AV_LOG_QUIET);
        grabber = new FFmpegFrameGrabber(source.url());
        grabber.setPixelFormat(AV_PIX_FMT_BGR24);
        grabber.setOption("rw_timeout", "5000000");
        grabber.setVideoOption("threads", "1");
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
        byte[] copy = new byte[size];
        ByteBuffer buffer = ((ByteBuffer)frame.image[0]).duplicate();
        buffer.position(0); buffer.get(copy);
        return new VideoFrame(frame.imageWidth, frame.imageHeight, frame.imageStride, copy,
                grabber.getTimestamp(), System.nanoTime());
    }
    @Override public void close() throws Exception { grabber.close(); }
}
