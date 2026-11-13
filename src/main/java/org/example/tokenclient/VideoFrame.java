package org.example.tokenclient;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

/** Owned BGR bytes: never points into FFmpeg's reusable native frame. */
record VideoFrame(int width, int height, int stride, byte[] bgr, long ptsUs, long decodedAtNs) {
    static VideoFrame fromImage(BufferedImage image) {
        BufferedImage bgr = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        var graphics = bgr.createGraphics();
        try { graphics.drawImage(image, 0, 0, null); } finally { graphics.dispose(); }
        return new VideoFrame(bgr.getWidth(), bgr.getHeight(), bgr.getWidth() * 3,
                ((DataBufferByte)bgr.getRaster().getDataBuffer()).getData(), 0, System.nanoTime());
    }
    @Override public String toString() { return "VideoFrame[" + width + "x" + height + ", ptsUs=" + ptsUs + "]"; }
}
