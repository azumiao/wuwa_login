package org.example.tokenclient;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.*;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.bytedeco.ffmpeg.avcodec.AVCodecContext.FF_THREAD_FRAME;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in local decode/copy benchmark; it makes no network or live-latency claims. */
@EnabledIfSystemProperty(named = "app.scan.benchmark", matches = "true")
class VideoScanPerformanceTest {
    @TempDir Path directory;
    private static final int FRAMES = 180, WARMUP = 30;
    private static final ThreadMXBean MEMORY = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private record Result(String mode, double p50, double p95, long heapPerFrame, long checksum,
                          int frames, int activeThreads, int threadType) { }

    @Test void compareSingleDoubleAndAutomaticSliceThreadsAndLegacyCopies() throws Exception {
        av_log_set_level(AV_LOG_QUIET);
        System.out.printf("BENCH JDK=%s CPUs=%d 1920x1080 H.264 60fps; %d warmup + %d measured frames/run%n",
                System.getProperty("java.version"), Runtime.getRuntime().availableProcessors(), WARMUP, FRAMES-WARMUP);
        for (int slices : new int[]{1, 4}) {
            Path video = fixture(slices);
            // Warm native libraries/JIT before collecting the rotated three-round comparison.
            measure(video, 1, false);
            List<Result> results = new ArrayList<>();
            for (int round = 0; round < 3; round++) {
                int[] modes = {1, 2, 0};
                for (int n = 0; n < modes.length; n++) results.add(measure(video, modes[(n+round)%3], false));
                results.add(measure(video, 1, true));
            }
            long checksum = results.getFirst().checksum;
            for (Result r : results) {
                assertEquals(FRAMES, r.frames);
                assertEquals(checksum, r.checksum, "Every mode must decode the same pixels and PTS");
                assertEquals(0, r.threadType & FF_THREAD_FRAME, "Frame-thread queues must stay disabled");
            }
            for (String mode : List.of("pooled/1", "pooled/2", "pooled/auto", "legacy/1")) {
                var group = results.stream().filter(r -> r.mode.equals(mode)).toList();
                System.out.printf(Locale.ROOT,
                        "BENCH slices=%d %-11s median P50=%.3fms P95=%.3fms heap=%.0f bytes/frame activeThreads=%d threadType=%d%n",
                        slices, mode, median(group.stream().mapToDouble(Result::p50).toArray()),
                        median(group.stream().mapToDouble(Result::p95).toArray()),
                        median(group.stream().mapToDouble(r -> r.heapPerFrame).toArray()),
                        group.getFirst().activeThreads, group.getFirst().threadType);
            }
        }
    }

    private Result measure(Path video, int threads, boolean legacy) throws Exception {
        double[] times = new double[FRAMES-WARMUP];
        long allocated = 0, checksum = 0, previousPts = -1;
        int count = 0, activeThreads, threadType;
        try (var source = legacy ? null : new FfmpegVideoSource(new DouyinStreamResolver.Source(video.toString(), Map.of()), threads);
             var old = legacy ? new FFmpegFrameGrabber(video.toString()) : null) {
            FFmpegFrameGrabber grabber;
            if (legacy) {
                grabber = old;
                grabber.setPixelFormat(AV_PIX_FMT_BGR24);
                grabber.setVideoOption("threads", "1");
                grabber.start();
            } else {
                var field = FfmpegVideoSource.class.getDeclaredField("grabber"); field.setAccessible(true);
                grabber = (FFmpegFrameGrabber) field.get(source);
            }
            var codecField = FFmpegFrameGrabber.class.getDeclaredField("video_c"); codecField.setAccessible(true);
            var codec = (AVCodecContext) codecField.get(grabber);
            assertEquals("h264", codec.codec().name().getString());
            while (true) {
                long bytes = MEMORY.getCurrentThreadAllocatedBytes(), start = System.nanoTime();
                VideoFrame frame;
                if (legacy) {
                    var f = grabber.grabImage(); if (f == null) break;
                    byte[] copy = new byte[f.imageStride*f.imageHeight];
                    ((ByteBuffer) f.image[0]).duplicate().position(0).get(copy);
                    // Original hot path: FFmpeg -> byte[] -> new native BytePointer for recognition.
                    frame = new VideoFrame(f.imageWidth, f.imageHeight, f.imageStride, copy, grabber.getTimestamp(), System.nanoTime());
                } else { frame = source.read(); if (frame == null) break; }
                try (frame) {
                    assertTrue(frame.ptsUs() > previousPts); previousPts = frame.ptsUs();
                    checksum += frame.ptsUs() + frame.pixels().get(0) + frame.pixels().get(frame.stride()*frame.height()-1);
                    if (count >= WARMUP) {
                        times[count-WARMUP] = (System.nanoTime()-start)/1_000_000.0;
                        allocated += MEMORY.getCurrentThreadAllocatedBytes()-bytes;
                    }
                    count++;
                }
            }
            // H.264 can finish initializing slice workers only after reading its first picture.
            activeThreads = codec.thread_count(); threadType = codec.active_thread_type();
        }
        Arrays.sort(times);
        return new Result((legacy ? "legacy/" : "pooled/")+(threads == 0 ? "auto" : threads),
                times[times.length/2], times[(int)(times.length*0.95)], allocated/times.length,
                checksum, count, activeThreads, threadType);
    }

    private Path fixture(int slices) throws Exception {
        Path video = directory.resolve("h264-"+slices+".mp4");
        Path qrFile = directory.resolve("qr.png"), log = directory.resolve("encoder-"+slices+".log");
        MatrixToImageWriter.writeToPath(new QRCodeWriter().encode(KuroAuthServiceTest.QR, BarcodeFormat.QR_CODE, 300, 300), "PNG", qrFile);
        // The application's non-GPL FFmpeg package has no x264 encoder. Use the optional system CLI
        // only to generate fixtures; all measured decoding uses the application's packaged FFmpeg.
        var encoder = new ProcessBuilder(System.getProperty("app.scan.benchmark.ffmpeg", "ffmpeg"),
                "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=1920x1080:rate=60",
                "-loop", "1", "-framerate", "60", "-i", qrFile.toString(),
                "-filter_complex_threads", "1", "-filter_complex", "[0:v][1:v]overlay=1400:500",
                "-frames:v", Integer.toString(FRAMES), "-an", "-c:v", "libx264", "-preset", "veryfast",
                "-crf", "22", "-threads", "2", "-pix_fmt", "yuv420p",
                "-x264-params", "slices="+slices+":bframes=2:keyint=60", video.toString())
                .redirectError(log.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertTrue(encoder.waitFor(60, TimeUnit.SECONDS), "Fixture encoder timed out");
            assertEquals(0, encoder.exitValue(), () -> {
                try { return Files.readString(log); } catch (Exception e) { return e.toString(); }
            });
        } finally { if (encoder.isAlive()) encoder.destroyForcibly(); }
        try (var source = new FfmpegVideoSource(new DouyinStreamResolver.Source(video.toString(), Map.of()), 1);
             var decoder = new WechatQrDecoder(); var frame = source.read()) {
            assertTrue(decoder.decode(frame).contains(KuroAuthServiceTest.QR), "The compressed fixture must retain a readable QR");
        }
        return video;
    }

    private static double median(double[] values) { Arrays.sort(values); return values[values.length/2]; }
}
