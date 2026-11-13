package org.example.tokenclient;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_wechat_qrcode.WeChatQRCode;
import static org.bytedeco.opencv.global.opencv_core.CV_8UC3;

/** One detector per worker; native detector is not shared across threads. */
final class WechatQrDecoder implements LiveScanService.Decoder {
    private static Path models;
    private final WeChatQRCode detector;
    WechatQrDecoder() throws IOException {
        Path directory = models();
        detector = new WeChatQRCode(directory.resolve("detect.prototxt").toString(), directory.resolve("detect.caffemodel").toString(),
                directory.resolve("sr.prototxt").toString(), directory.resolve("sr.caffemodel").toString());
        detector.setScaleFactor(0.4f);
    }
    @Override public List<String> decode(VideoFrame frame) {
        try (var bytes = new BytePointer(frame.bgr());
             var mat = new Mat(frame.height(), frame.width(), CV_8UC3, bytes, frame.stride());
             var codes = detector.detectAndDecode(mat)) {
            List<String> found = new ArrayList<>();
            for (long i = 0; i < codes.size(); i++) {
                String text = codes.get(i).getString(java.nio.charset.StandardCharsets.UTF_8);
                // Some WeChat decoder paths include the C string terminator in std::string's length.
                // Do not pass that NUL to the game API as part of its QR ticket.
                int end = text.length();
                while (end > 0 && text.charAt(end - 1) == '\0') end--;
                text = text.substring(0, end);
                if (!text.isEmpty() && text.chars().noneMatch(Character::isISOControl)) found.add(text);
            }
            return found;
        }
    }
    @Override public void close() { detector.close(); }

    private static synchronized Path models() throws IOException {
        if (models != null) return models;
        var files = Map.of(
                "detect.caffemodel", "cc49b8c9babaf45f3037610fe499df38c8819ebda29e90ca9f2e33270f6ef809",
                "detect.prototxt", "e8acfc395caf443a47f15686a9b9207b36cb8f7e6ceb8fbaf6466665e68a9466",
                "sr.caffemodel", "e5d36889d8e6ef2f1c1f515f807cec03979320ac81792cd8fb927c31fd658ae3",
                "sr.prototxt", "8ae41acba97e8b4a8e741ee350481e49b8e01d787193f470a4c95ee1c02d5b61");
        Path dir = Files.createTempDirectory("wuwa-wechat-"); dir.toFile().deleteOnExit();
        for (var file : files.entrySet()) {
            try (var input = WechatQrDecoder.class.getResourceAsStream("wechat/" + file.getKey())) {
                if (input == null) throw new IOException("缺少微信二维码模型，请重新安装应用");
                byte[] data = input.readAllBytes();
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
                if (!hash.equals(file.getValue())) throw new IOException("微信二维码模型校验失败");
                Path output = dir.resolve(file.getKey()); Files.write(output, data); output.toFile().deleteOnExit();
            } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        }
        models = dir;
        return dir;
    }
}
