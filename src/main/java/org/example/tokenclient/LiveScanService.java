package org.example.tokenclient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** Capture never waits for recognition or UI. At most one waiting frame, plus the frame being decoded. */
final class LiveScanService implements AutoCloseable {
    interface FrameSource extends AutoCloseable { VideoFrame read() throws Exception; }
    interface Decoder extends AutoCloseable { List<String> decode(VideoFrame frame) throws Exception; }
    interface SourceFactory { FrameSource open(String room) throws Exception; }
    interface DecoderFactory { Decoder create() throws Exception; }
    record Candidate(String text, long generation, long connection, long sequence, long decodedAtNs, long decodeMs) {
        @Override public String toString() { return "Candidate[payload redacted, sequence=" + sequence + "]"; }
    }
    record Stats(String status, long frames, long scanned, long dropped, long stale, long decodeMs, long ageMs, long lagGrowthMs) { }
    private record Pending(VideoFrame frame, long connection, long sequence) { }
    private final SourceFactory sources;
    private final DecoderFactory decoders;
    private final long maxAgeNs;
    private long generation;
    private volatile Run current;

    LiveScanService() {
        this(room -> new FfmpegVideoSource(new DouyinStreamResolver().resolve(room)), WechatQrDecoder::new,
                Duration.ofMillis(Math.clamp(Long.getLong("app.scan.maxAgeMs", 500L), 50, 5000)));
    }
    LiveScanService(SourceFactory sources, DecoderFactory decoders, Duration maxAge) {
        this.sources = sources; this.decoders = decoders; this.maxAgeNs = maxAge.toNanos();
    }
    synchronized void start(String room, Consumer<Candidate> detected) {
        close();
        Run run = new Run(++generation, room, detected); current = run;
        run.scan = Thread.ofPlatform().daemon().name("wechat-scan").unstarted(() -> recognize(run));
        run.capture = Thread.ofPlatform().daemon().name("live-capture").unstarted(() -> capture(run));
        run.scan.start(); run.capture.start();
    }
    boolean accepts(Candidate candidate) {
        Run r = current;
        return r != null && active(r) && r.id == candidate.generation && r.connection.get() == candidate.connection
                && System.nanoTime() - candidate.decodedAtNs() <= maxAgeNs;
    }
    void resume() {
        Run r = current;
        if (r != null) { r.pending.set(null); r.paused.set(false); }
    }
    void pause() { Run r = current; if (r != null) { r.paused.set(true); r.pending.set(null); } }
    void suppress(String code) {
        Run r = current;
        if (r != null) synchronized (r.terminalCodes) {
            r.terminalCodes.add(code);
            if (r.terminalCodes.size() > 128) r.terminalCodes.removeFirst();
        }
    }
    boolean running() { Run r = current; return r != null && active(r); }
    Stats stats() {
        Run r = current;
        return r == null ? new Stats("等待中",0,0,0,0,0,0,0) : new Stats(r.status, r.frames.get(),r.scanned.get(),r.dropped.get(),r.stale.get(),r.decodeMs,r.ageMs,r.lagGrowthMs);
    }
    private boolean active(Run r) { return current == r && !r.stopped; }
    private void capture(Run r) {
        try { captureLoop(r); }
        catch (StackOverflowError e) { fail(r, ScanFailure.message(ScanFailure.Phase.CAPTURE, e), e); }
        finally {
            // An unexpected worker exit must not leave the UI reporting an active scan.
            if (active(r)) fail(r, "直播取流线程意外结束，请查看控制台诊断后重试。", null);
        }
    }
    private void captureLoop(Run r) {
        int failures = 0;
        while (active(r)) {
            long connection = r.connection.incrementAndGet(); r.pending.set(null);
            setStatus(r, failures == 0 ? "正在连接抖音直播…" : "连接中断，正在重新获取直播流…");
            long basePts = -1, baseTime = 0, previousPts = -1;
            int healthyFrames = 0;
            try (FrameSource source = sources.open(r.room)) {
                if (!active(r)) return;
                setStatus(r, "正在扫描直播二维码");
                while (active(r)) {
                    VideoFrame frame = source.read();
                    if (frame == null) throw new java.io.IOException("Stream ended");
                    if (!active(r)) return;
                    // PTS discontinuity invalidates in-flight results and resets the delay baseline.
                    if (previousPts >= 0 && frame.ptsUs() < previousPts) {
                        connection = r.connection.incrementAndGet(); r.pending.set(null); basePts = -1;
                    }
                    previousPts = frame.ptsUs();
                    if (basePts < 0) { basePts = frame.ptsUs(); baseTime = frame.decodedAtNs(); }
                    r.lagGrowthMs = Math.max(0, (frame.decodedAtNs() - baseTime) / 1_000_000 - (frame.ptsUs() - basePts) / 1000);
                    if (r.lagGrowthMs > 2500) throw new java.io.IOException("Growing stream delay");
                    long sequence = r.frames.incrementAndGet();
                    if (++healthyFrames >= 150) failures = 0;
                    if (!r.paused.get()) {
                        if (r.pending.getAndSet(new Pending(frame, connection, sequence)) != null) r.dropped.incrementAndGet();
                        synchronized (r.signal) { r.signal.notifyAll(); }
                    }
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); return;
            } catch (Exception | LinkageError e) {
                r.connection.incrementAndGet(); r.pending.set(null);
                if (!active(r)) return;
                if (e instanceof DouyinStreamResolver.Offline || ++failures > 3 || e instanceof LinkageError) {
                    String message = e instanceof DouyinStreamResolver.Offline || e instanceof DouyinStreamResolver.Failure
                            ? e.getMessage() : ScanFailure.message(ScanFailure.Phase.CAPTURE, e);
                    fail(r, message, e); return;
                }
                // Only resolver messages are safe to expose; native exceptions can contain signed URLs.
                setStatus(r, "正在重连（" + failures + "/3）");
                try { Thread.sleep(500L * failures); } catch (InterruptedException ex) { return; }
            }
        }
    }
    private void recognize(Run r) {
        boolean initialized = false;
        try (Decoder decoder = decoders.create()) {
            initialized = true;
            while (active(r)) {
                Pending p = r.pending.getAndSet(null);
                if (p == null || r.paused.get()) {
                    synchronized (r.signal) { if (active(r) && r.pending.get() == null) r.signal.wait(100); }
                    continue;
                }
                if (System.nanoTime() - p.frame.decodedAtNs() > maxAgeNs) { r.stale.incrementAndGet(); continue; }
                long start = System.nanoTime();
                List<String> codes = decoder.decode(p.frame);
                long end = System.nanoTime();
                r.scanned.incrementAndGet(); r.decodeMs = (end-start)/1_000_000;
                r.ageMs = (end-p.frame.decodedAtNs())/1_000_000;
                if (!active(r) || p.connection != r.connection.get() || end-p.frame.decodedAtNs() > maxAgeNs) {
                    r.stale.incrementAndGet(); continue;
                }
                for (String code : codes) {
                    if (!KuroAuthService.validQr(code)) continue;
                    synchronized (r.terminalCodes) { if (r.terminalCodes.contains(code)) continue; }
                    if (code.equals(r.lastCode) && end-r.lastCodeTime < Duration.ofSeconds(5).toNanos()) continue;
                    if (r.paused.compareAndSet(false, true)) {
                        r.lastCode = code; r.lastCodeTime = end;
                        r.detected.accept(new Candidate(code, r.id, p.connection, p.sequence, p.frame.decodedAtNs(), r.decodeMs));
                    }
                    break;
                }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt();
        } catch (Exception | LinkageError | StackOverflowError e) {
            fail(r, ScanFailure.message(initialized ? ScanFailure.Phase.QR_DECODE : ScanFailure.Phase.QR_INIT, e), e);
        } finally {
            if (active(r)) fail(r, "二维码识别线程意外结束，请查看控制台诊断后重试。", null);
            r.pending.set(null);
        }
    }
    private void setStatus(Run r, String status) {
        synchronized (r) { if (active(r)) r.status = status; }
    }
    private void fail(Run r, String message, Throwable error) {
        synchronized (r) {
            if (!active(r)) return;
            r.status = message;
            r.stopped = true;
            r.pending.set(null);
            r.capture.interrupt(); r.scan.interrupt();
        }
        if (error != null) System.getLogger(LiveScanService.class.getName()).log(System.Logger.Level.ERROR,
                message + "\n" + ScanFailure.diagnostic(error));
    }
    @Override public synchronized void close() {
        Run old = current; current = null;
        if (old != null) {
            old.stopped = true; old.pending.set(null);
            old.scan.interrupt(); old.capture.interrupt();
        }
    }
    private static final class Run {
        final long id; final String room; final Consumer<Candidate> detected;
        final Object signal = new Object();
        final AtomicReference<Pending> pending = new AtomicReference<>();
        final AtomicBoolean paused = new AtomicBoolean();
        final java.util.LinkedHashSet<String> terminalCodes = new java.util.LinkedHashSet<>();
        final AtomicLong connection = new AtomicLong(), frames = new AtomicLong(), scanned = new AtomicLong(), dropped = new AtomicLong(), stale = new AtomicLong();
        volatile boolean stopped;
        volatile String status = "正在初始化识别器…";
        volatile long decodeMs, ageMs, lagGrowthMs;
        String lastCode = ""; long lastCodeTime;
        Thread capture, scan;
        Run(long id, String room, Consumer<Candidate> detected) { this.id=id; this.room=room; this.detected=detected; }
    }
}
