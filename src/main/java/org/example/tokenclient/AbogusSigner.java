package org.example.tokenclient;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.bouncycastle.crypto.digests.SM3Digest;

/** Java port of jackspeng/douyinliverecord ab_sign.py (MIT); see THIRD_PARTY.md. */
final class AbogusSigner {
    private static final String S3 = "ckdp1h4ZKsUB80/Mfvw36XIgR25+WQAlEi7NLboqYTOPuzmFjJnryx9HVGDaStCe";
    private static final String S4 = "Dkdpgh2ZmsQB80/MfvV36XI1R45-WUAlEixNLwoqYTOPuzKFjJnry79HbGcaStCe";
    private static final String ENV = "1920|1080|1920|1040|0|30|0|0|1872|92|1920|1040|1857|92|1|24|Win32";

    static String sign(String query, String userAgent, long now) {
        byte[] q = hash(hash(bytes(query + "cus"))), suffix = hash(hash(bytes("cus")));
        byte[] ua = hash(bytes(encode(rc4(bytes(userAgent), new byte[]{0, 1, 14}), S3)));
        int[] b = new int[73];
        put32(b, 20, now); b[24] = (int)(now >>> 32) & 255; b[25] = (int)(now >>> 40) & 255;
        b[18] = 44; b[31] = 1; b[37] = 14;
        b[38] = q[21] & 255; b[39] = q[22] & 255;
        b[40] = suffix[21] & 255; b[41] = suffix[22] & 255;
        b[42] = ua[23] & 255; b[43] = ua[24] & 255;
        long end = now + 100;
        put32(b, 44, end); b[48] = 3; b[49] = (int)(end >>> 32) & 255; b[50] = (int)(end >>> 40) & 255;
        put32(b, 52, 110624);
        b[57] = 6383 & 255; b[58] = (6383 >>> 8) & 255;
        b[65] = ENV.length();
        int[] check = {18,20,26,30,38,40,42,21,27,31,35,39,41,43,22,28,32,36,23,29,33,37,44,45,46,47,48,49,50,24,25,52,53,54,55,57,58,59,60,65,66,70,71};
        for (int i : check) b[72] ^= b[i];
        int[] order = {18,20,52,26,30,34,58,38,40,53,42,21,27,54,55,31,35,57,39,41,43,22,28,32,60,36,23,29,33,37,44,45,59,46,47,48,49,50,24,25,65,66,70,71};
        byte[] body = new byte[order.length + ENV.length() + 1];
        for (int i = 0; i < order.length; i++) body[i] = (byte)b[order[i]];
        System.arraycopy(bytes(ENV), 0, body, order.length, ENV.length());
        body[body.length - 1] = (byte)b[72];
        byte[] encrypted = rc4(body, new byte[]{121});
        byte[] all = new byte[12 + encrypted.length];
        int[][] options = {{3,45},{1,0},{1,5}};
        int[] random = {1234,9876,5555}; // Matches upstream's fixed prefix.
        for (int i = 0; i < 3; i++) {
            int a = random[i] & 255, c = random[i] >>> 8;
            all[i*4] = (byte)((a & 170) | (options[i][0] & 85));
            all[i*4+1] = (byte)((a & 85) | (options[i][0] & 170));
            all[i*4+2] = (byte)((c & 170) | (options[i][1] & 85));
            all[i*4+3] = (byte)((c & 85) | (options[i][1] & 170));
        }
        System.arraycopy(encrypted, 0, all, 12, encrypted.length);
        return encode(all, S4) + "=";
    }

    private static void put32(int[] b, int at, long value) {
        for (int i = 0; i < 4; i++) b[at+i] = (int)(value >>> (24-i*8)) & 255;
    }
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static byte[] hash(byte[] input) {
        var d = new SM3Digest(); d.update(input, 0, input.length);
        byte[] out = new byte[32]; d.doFinal(out, 0); return out;
    }
    private static byte[] rc4(byte[] data, byte[] key) {
        int[] s = new int[256]; Arrays.setAll(s, i -> i);
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + s[i] + (key[i % key.length] & 255)) & 255;
            int t = s[i]; s[i] = s[j]; s[j] = t;
        }
        byte[] out = new byte[data.length]; int i = 0; j = 0;
        for (int n = 0; n < data.length; n++) {
            i = (i + 1) & 255; j = (j + s[i]) & 255;
            int t = s[i]; s[i] = s[j]; s[j] = t;
            out[n] = (byte)(data[n] ^ s[(s[i] + s[j]) & 255]);
        }
        return out;
    }
    private static String encode(byte[] data, String alphabet) {
        StringBuilder out = new StringBuilder();
        for (int n = 0; n < (data.length * 8 + 5) / 6; n++) {
            int bit = n * 6, at = bit / 8;
            int pair = (data[at] & 255) << 8;
            if (at + 1 < data.length) pair |= data[at+1] & 255;
            out.append(alphabet.charAt((pair >>> (10 - bit % 8)) & 63));
        }
        return out.toString();
    }
}
