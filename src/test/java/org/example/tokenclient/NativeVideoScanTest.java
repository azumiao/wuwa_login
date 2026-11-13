package org.example.tokenclient;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import java.nio.file.Path;
import java.util.Map;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_FFV1;
import static org.junit.jupiter.api.Assertions.*;

class NativeVideoScanTest {
    @TempDir Path directory;
    @Test void realFfmpegFramesAreOwnedAndRecognizedByRealWechatModels() throws Exception {
        var image = MatrixToImageWriter.toBufferedImage(new QRCodeWriter().encode(KuroAuthServiceTest.QR,BarcodeFormat.QR_CODE,480,480));
        // JavaCV's recorder expects byte-per-channel pixels, not ZXing's packed 1-bit raster.
        var pixelsImage = new java.awt.image.BufferedImage(480,480,java.awt.image.BufferedImage.TYPE_3BYTE_BGR);
        var g = pixelsImage.createGraphics();
        try { g.drawImage(image,0,0,null); } finally { g.dispose(); }
        Path video = directory.resolve("qr.mkv");
        try (var recorder = new FFmpegFrameRecorder(video.toString(),480,480);
             var converter = new Java2DFrameConverter()) {
            recorder.setFormat("matroska"); recorder.setVideoCodec(AV_CODEC_ID_FFV1); recorder.setFrameRate(30);
            recorder.start();
            for (int i=0;i<5;i++) recorder.record(converter.convert(pixelsImage));
        }
        try (var source = new FfmpegVideoSource(new DouyinStreamResolver.Source(video.toString(),Map.of()));
             var decoder = new WechatQrDecoder()) {
            try (var first = source.read(); var second = source.read()) {
                assertNotNull(first); assertNotNull(second); assertTrue(second.ptsUs()>first.ptsUs());
                byte[] pixels = new byte[first.stride() * first.height()]; first.pixels().get(pixels);
                try (var third = source.read()) {
                    byte[] retained = new byte[pixels.length]; first.pixels().get(retained);
                    assertArrayEquals(pixels,retained,"Reading another frame must not overwrite retained pixels");
                    assertNotEquals(first.pixels().address(), third.pixels().address());
                }
                assertTrue(decoder.decode(first).contains(KuroAuthServiceTest.QR));
            }
        }
    }
}
