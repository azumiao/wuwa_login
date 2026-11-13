package org.example.tokenclient;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LiveScanServiceTest {
    private static VideoFrame frame(int number) { return new VideoFrame(1,1,3,new byte[]{(byte)number,0,0},number*33333L,System.nanoTime()); }
    private static final class Source implements LiveScanService.FrameSource {
        final BlockingQueue<VideoFrame> frames = new LinkedBlockingQueue<>();
        final CountDownLatch closed = new CountDownLatch(1);
        public VideoFrame read() throws InterruptedException { var f = frames.take(); return f.width()==0 ? null : f; }
        public void close() { closed.countDown(); }
    }
    private static abstract class Decoder implements LiveScanService.Decoder { public void close() { } }
    @Test void slowRecognitionSkipsHistoryAndCaptureContinuesWithoutPreview() throws Exception {
        Source source = new Source();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), second = new CountDownLatch(1);
        List<Integer> seen = new CopyOnWriteArrayList<>();
        Decoder decoder = new Decoder() {
            public List<String> decode(VideoFrame f) throws InterruptedException {
                seen.add((int)f.bgr()[0]);
                if (seen.size()==1) { started.countDown(); release.await(); } else second.countDown();
                return List.of();
            }
        };
        try (var service = new LiveScanService(r -> source, () -> decoder,Duration.ofSeconds(5))) {
            service.start("123",c -> fail("Unexpected code")); source.frames.add(frame(1));
            assertTrue(started.await(3,TimeUnit.SECONDS));
            for (int n=2;n<=10;n++) source.frames.add(frame(n));
            await(() -> service.stats().frames()==10);
            release.countDown(); assertTrue(second.await(3,TimeUnit.SECONDS));
            assertEquals(List.of(1,10),seen); assertEquals(8,service.stats().dropped());
        }
        assertTrue(source.closed.await(3,TimeUnit.SECONDS));
    }
    @Test void lateRecognitionFromStoppedRoomCannotBeDelivered() throws Exception {
        Source source = new Source();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicInteger detected = new AtomicInteger();
        Decoder decoder = new Decoder() {
            public List<String> decode(VideoFrame f) {
                started.countDown();
                while (true) try { release.await(); break; } catch (InterruptedException ignored) { }
                return List.of(KuroAuthServiceTest.QR);
            }
            public void close() { done.countDown(); }
        };
        var service = new LiveScanService(r -> source, () -> decoder,Duration.ofSeconds(5));
        service.start("123",c -> detected.incrementAndGet()); source.frames.add(frame(1));
        assertTrue(started.await(3,TimeUnit.SECONDS)); service.close(); release.countDown();
        assertTrue(done.await(3,TimeUnit.SECONDS)); assertEquals(0,detected.get());
    }
    @Test void resultIsRejectedIfRecognitionExceedsFreshnessBudget() throws Exception {
        Source source = new Source(); AtomicInteger detected = new AtomicInteger();
        Decoder decoder = new Decoder() {
            public List<String> decode(VideoFrame f) throws InterruptedException { Thread.sleep(80); return List.of(KuroAuthServiceTest.QR); }
        };
        try (var service = new LiveScanService(r -> source,() -> decoder,Duration.ofMillis(20))) {
            service.start("123",c -> detected.incrementAndGet()); source.frames.add(frame(1));
            await(() -> service.stats().stale()>0); assertEquals(0,detected.get());
        }
    }
    @Test void candidateWaitsForConfirmationWhileCaptureKeepsReading() throws Exception {
        Source source = new Source(); BlockingQueue<LiveScanService.Candidate> found = new LinkedBlockingQueue<>();
        Decoder decoder = new Decoder() { public List<String> decode(VideoFrame f) { return List.of(KuroAuthServiceTest.QR); } };
        try (var service = new LiveScanService(r -> source,() -> decoder,Duration.ofSeconds(5))) {
            service.start("123",found::add); source.frames.add(frame(1));
            var first = found.poll(3,TimeUnit.SECONDS); assertNotNull(first); assertTrue(service.accepts(first));
            for (int i=2;i<=20;i++) source.frames.add(frame(i));
            await(() -> service.stats().frames()==20);
            assertEquals(1,service.stats().scanned()); assertTrue(found.isEmpty());
            service.close(); assertFalse(service.accepts(first));
        }
    }
    @Test void reconnectRefreshesSourceAndDiscardsPreviousConnectionResult() throws Exception {
        Source first = new Source(), second = new Source();
        AtomicInteger opens = new AtomicInteger();
        CountDownLatch decoding = new CountDownLatch(1), release = new CountDownLatch(1);
        BlockingQueue<LiveScanService.Candidate> found = new LinkedBlockingQueue<>();
        Decoder decoder = new Decoder() {
            public List<String> decode(VideoFrame f) throws InterruptedException {
                if (f.bgr()[0]==1) { decoding.countDown(); release.await(); }
                return List.of(KuroAuthServiceTest.QR);
            }
        };
        try (var service = new LiveScanService(r -> opens.incrementAndGet()==1 ? first : second, () -> decoder,Duration.ofSeconds(5))) {
            service.start("123",found::add); first.frames.add(frame(1));
            assertTrue(decoding.await(3,TimeUnit.SECONDS));
            first.frames.add(new VideoFrame(0,0,0,new byte[0],0,0));
            await(() -> opens.get()==2);
            second.frames.add(frame(2)); await(() -> service.stats().frames()==2);
            release.countDown();
            var candidate = found.poll(3,TimeUnit.SECONDS);
            assertNotNull(candidate); assertEquals(2,candidate.sequence());
            assertTrue(service.accepts(candidate)); assertEquals(1,service.stats().stale());
        }
    }

    @Test void successfulOrExpiredCodeIsSuppressedAfterConfirmationCloses() throws Exception {
        Source source = new Source(); AtomicInteger found = new AtomicInteger();
        Decoder decoder = new Decoder() { public List<String> decode(VideoFrame f) { return List.of(KuroAuthServiceTest.QR); } };
        try (var service = new LiveScanService(r -> source, () -> decoder,Duration.ofSeconds(5))) {
            service.start("123",c -> found.incrementAndGet());
            service.suppress(KuroAuthServiceTest.QR); service.resume(); source.frames.add(frame(1));
            await(() -> service.stats().scanned()==1); assertEquals(0,found.get());
        }
    }
    @Test void captureStackOverflowStopsBothWorkersAndPreservesCaptureFailure() throws Exception {
        CountDownLatch decoderClosed = new CountDownLatch(1);
        try (var service = new LiveScanService(room -> { throw new StackOverflowError("simulated page parser failure"); },
                () -> new Decoder() {
                    public List<String> decode(VideoFrame frame) { fail("No frames should be decoded"); return List.of(); }
                    public void close() { decoderClosed.countDown(); }
                }, Duration.ofSeconds(5))) {
            service.start("123", c -> fail("Unexpected QR"));
            await(() -> !service.running());
            assertTrue(decoderClosed.await(3, TimeUnit.SECONDS));
            assertTrue(service.stats().status().contains("直播取流/解码失败"));
            assertTrue(service.stats().status().contains("StackOverflowError"));
            assertFalse(service.stats().status().contains("二维码识别"));
        }
    }

    @Test void nativeInitializationFailureShowsCauseAndStopsCapture() throws Exception {
        Source source = new Source(); CountDownLatch opened = new CountDownLatch(1);
        try (var service = new LiveScanService(room -> { opened.countDown(); return source; }, () -> {
            assertTrue(opened.await(3, TimeUnit.SECONDS));
            var error = new NoClassDefFoundError("Could not initialize native QR detector");
            error.initCause(new UnsatisfiedLinkError("no jniopencv_wechat_qrcode in java.library.path"));
            throw error;
        }, Duration.ofSeconds(5))) {
            service.start("123", c -> fail("Unexpected QR"));
            await(() -> !service.running());
            assertTrue(source.closed.await(3, TimeUnit.SECONDS));
            String status = service.stats().status();
            assertTrue(status.contains("二维码识别初始化失败"));
            assertTrue(status.contains("NoClassDefFoundError"));
            assertTrue(status.contains("UnsatisfiedLinkError"));
            assertTrue(status.contains("原生库或其依赖加载失败"));
        }
    }

    @Test void recognitionFailureIsDistinctFromInitialization() throws Exception {
        Source source = new Source();
        try (var service = new LiveScanService(room -> source, () -> new Decoder() {
            public List<String> decode(VideoFrame frame) { throw new IllegalArgumentException("invalid image dimensions"); }
        }, Duration.ofSeconds(5))) {
            service.start("123", c -> fail("Unexpected QR")); source.frames.add(frame(1));
            await(() -> !service.running());
            assertTrue(source.closed.await(3, TimeUnit.SECONDS));
            assertTrue(service.stats().status().contains("QR_DECODE"));
            assertFalse(service.stats().status().contains("初始化"));
        }
    }

    @Test void diagnosticsRedactStreamUrlsAndQrPayloadsAndBoundLongExceptions() {
        String url = "https://cdn.example/live.flv?token=stream-secret&sign=secret-sign";
        Throwable error = new IllegalStateException("decode " + url + " " + KuroAuthServiceTest.QR
                + " token=token-secret cookie=cookie-secret " + "x".repeat(100_000));
        String diagnostic = ScanFailure.diagnostic(error);
        for (String secret : List.of(url, "stream-secret", "secret-sign", KuroAuthServiceTest.QR, "token-secret", "cookie-secret"))
            assertFalse(diagnostic.contains(secret));
        assertTrue(diagnostic.contains("IllegalStateException"));
        assertTrue(diagnostic.length() < 2500);
    }

    @Test void nativeDiagnosticsPreserveDyldReasonAfterLongCachePaths() {
        String path = "/Users/test/.javacpp/cache/" + "long-directory/".repeat(250);
        String raw = path + "libjniopencv_wechat_qrcode.dylib: dlopen(" + path + "), 0x0001):"
                + "\nLibrary not loaded: @rpath/libopencv_objdetect.410.dylib"
                + "\nReferenced from: " + path + "libopencv_wechat_qrcode.410.dylib"
                + "\nReason: tried: " + path + " (no such file)"
                + "\nhttps://cdn.example/live.flv?sign=secret-sign";
        String diagnostic = ScanFailure.diagnostic(new UnsatisfiedLinkError(raw));
        assertTrue(diagnostic.contains("\n  Library not loaded: @rpath/libopencv_objdetect.410.dylib"));
        assertTrue(diagnostic.contains("\n  Reason: tried:"));
        assertTrue(diagnostic.contains("no such file"));
        assertFalse(diagnostic.contains("secret-sign"));
        assertTrue(diagnostic.length() < 3500);
    }
    private static void await(java.util.function.BooleanSupplier check) throws InterruptedException {
        long end = System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while (!check.getAsBoolean() && System.nanoTime()<end) Thread.sleep(5);
        assertTrue(check.getAsBoolean());
    }
}
