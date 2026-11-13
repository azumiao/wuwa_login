package org.example.tokenclient;

import java.util.ArrayList;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.global.opencv_dnn;
import org.bytedeco.opencv.global.opencv_wechat_qrcode;

/** Load WeChat's native dependency closure without bringing in OpenCV's GUI modules. */
final class WechatNativeLoader {
    private static boolean loaded;

    static synchronized void load() {
        if (loaded) return;
        Loader.load(opencv_dnn.class); // OpenBLAS, core, imgproc, dnn.
        Class<?> scope = opencv_wechat_qrcode.class;
        var properties = Loader.loadProperties(scope, Loader.loadProperties(), true);
        String prefix = "opencv_wechat_qrcode";
        String link = properties.get("platform.link").stream().filter(name -> name.startsWith(prefix))
                .findFirst().orElseThrow(() -> new IllegalStateException("Missing WeChat native link configuration"));
        String suffix = link.substring(prefix.length()); // @.410 on Unix, 4100 on Windows; follows the preset version.
        var preloaded = new ArrayList<String>();
        // The macOS 4.10 binary links these modules, but its JavaCPP preset only inherits dnn.
        // Load the native libraries directly: the objdetect JNI preset would also load highgui/videoio.
        for (String module : new String[]{"flann", "features2d", "calib3d", "objdetect"}) {
            String library = "opencv_" + module + suffix;
            String path = Loader.loadLibrary(scope, Loader.findLibrary(scope, properties, library), library,
                    preloaded.toArray(String[]::new));
            if (path != null) preloaded.add(path);
        }
        Loader.load(scope);
        loaded = true;
    }

    private WechatNativeLoader() { }
}
