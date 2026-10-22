import android.content.Context;
import android.content.ContextWrapper;
import android.content.AttributionSource;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Looper;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.nio.file.Files;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;

/** Shell-owned off-screen display. No mirroring of the owner's display and no lock-screen changes. */
public final class SomaDisplay {
    private static final File DIR = new File("/data/local/tmp/soma-vd");
    // DisplayManager hidden flags, also used by scrcpy's NewDisplayCapture.
    private static final int FLAGS = 1 | 8 | 64 | 256 | 1024 | 2048 | 4096 | 16384;

    private static Context shellContext() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        return new ContextWrapper(system) {
            @Override public String getPackageName() { return "com.android.shell"; }
            @Override public String getOpPackageName() { return "com.android.shell"; }
            @Override public Context getApplicationContext() { return this; }
            @Override public AttributionSource getAttributionSource() {
                return new AttributionSource.Builder(2000).setPackageName("com.android.shell").build();
            }
        };
    }

    private static String mac(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes("UTF-8")));
    }
    private static boolean same(String a, String b) throws Exception {
        return MessageDigest.isEqual(a.getBytes("UTF-8"), b.getBytes("UTF-8"));
    }
    private static String line(BufferedReader reader) throws Exception {
        StringBuilder text = new StringBuilder();
        for (int n = 0; n < 1024; n++) {
            int ch = reader.read();
            if (ch < 0) throw new IllegalStateException("Connection closed");
            if (ch == '\n') return text.toString();
            text.append((char) ch);
        }
        throw new IllegalArgumentException("Oversized request");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("SomaDisplay WIDTH HEIGHT DPI SOMA_UID");
        int width = Integer.parseInt(args[0]), height = Integer.parseInt(args[1]), dpi = Integer.parseInt(args[2]);
        int appUid = Integer.parseInt(args[3]);
        if (appUid < 10000) throw new IllegalArgumentException("Soma app UID required");
        if (width < 320 || height < 320 || width > 2560 || height > 2560 || dpi < 120 || dpi > 640)
            throw new IllegalArgumentException("Invalid display size or density");
        DIR.mkdirs();
        android.system.Os.chmod(DIR.getPath(), 0700);
        byte[] key = Files.readAllBytes(new File(DIR, "key").toPath());
        if (key.length != 64) throw new IllegalArgumentException("Private helper key required");
        ServerSocket server = new ServerSocket(18746, 4, InetAddress.getByName("127.0.0.1"));
        Looper.prepareMainLooper();
        Context context = shellContext();
        Constructor<DisplayManager> ctor = DisplayManager.class.getDeclaredConstructor(Context.class);
        ctor.setAccessible(true);
        DisplayManager manager = ctor.newInstance(context);
        ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        VirtualDisplay display = manager.createVirtualDisplay("soma-agent", width, height, dpi, reader.getSurface(), FLAGS);
        if (display == null || display.getDisplay().getDisplayId() == 0) throw new IllegalStateException("No secondary display");
        DIR.mkdirs();
        File request = new File(DIR, "shot"), png = new File(DIR, "shot.png"), stop = new File(DIR, "stop");
        request.delete(); png.delete(); stop.delete();
        try (FileOutputStream out = new FileOutputStream(new File(DIR, "display"))) {
            out.write(Integer.toString(display.getDisplay().getDisplayId()).getBytes("UTF-8"));
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { display.release(); reader.close(); new File(DIR, "display").delete(); }));
        System.out.println("DISPLAY " + display.getDisplay().getDisplayId());
        System.out.flush();
        new Thread(() -> {
            while (true) {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(1000);
                    BufferedReader commands = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
                    String c = line(commands);
                    if (!c.matches("[A-Za-z0-9+/=]{24,64}")) continue;
                    byte[] random = new byte[24]; new SecureRandom().nextBytes(random);
                    String nonce = Base64.getEncoder().encodeToString(random);
                    String prefix = c + " " + nonce;
                    client.getOutputStream().write((nonce + " " + mac(key, prefix + " server") + "\n").getBytes("UTF-8"));
                    String[] requestParts = line(commands).split(" ", 2);
                    if (requestParts.length != 2 || !same(requestParts[1], mac(key, prefix + " " + requestParts[0]))) continue;
                    String command = requestParts[0];
                    if ("takeover".equals(command)) {
                        Class<?> atm = Class.forName("android.app.ActivityTaskManager");
                        Object service = atm.getMethod("getService").invoke(null);
                        Class<?> api = Class.forName("android.app.IActivityTaskManager");
                        List<?> tasks = (List<?>) api.getMethod("getAllRootTaskInfosOnDisplay", int.class).invoke(service, display.getDisplay().getDisplayId());
                        for (Object task : tasks) {
                            int id = task.getClass().getField("taskId").getInt(task);
                            api.getMethod("moveRootTaskToDisplay", int.class, int.class).invoke(service, id, 0);
                        }
                    } else if (!"status".equals(command)) continue;
                    String result = new JSONObject().put("id", display.getDisplay().getDisplayId())
                        .put("width", width).put("height", height).put("alwaysUnlocked", true).toString();
                    client.getOutputStream().write((result + " " + mac(key, prefix + " " + result) + "\n").getBytes("UTF-8"));
                } catch (Exception error) { System.err.println("Display connection refused: " + error.getClass().getSimpleName()); }
            }
        }, "soma-display-control").start();
        // Always drain frames: an undrained ImageReader stops the display's producer after a few frames.
        new Thread(() -> {
            Image latest = null;
            try {
                while (!stop.exists()) {
                    Image frame = reader.acquireLatestImage();
                    if (frame != null) {
                        if (latest != null) latest.close();
                        latest = frame;
                    }
                    if (request.exists() && latest != null) {
                        Image.Plane plane = latest.getPlanes()[0];
                        if (plane.getPixelStride() != 4) throw new IllegalStateException("Unexpected pixel stride");
                        Bitmap padded = Bitmap.createBitmap(plane.getRowStride() / 4, height, Bitmap.Config.ARGB_8888);
                        plane.getBuffer().rewind();
                        padded.copyPixelsFromBuffer(plane.getBuffer());
                        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
                        try {
                            File staging = new File(DIR, "shot.tmp");
                            try (FileOutputStream out = new FileOutputStream(staging)) {
                                if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IllegalStateException("PNG failed");
                            }
                            if (!staging.renameTo(png)) throw new IllegalStateException("PNG rename failed");
                            request.delete();
                        } finally { if (cropped != padded) cropped.recycle(); padded.recycle(); }
                    }
                    Thread.sleep(100);
                }
            } catch (Exception error) { error.printStackTrace(); }
            finally { if (latest != null) latest.close(); System.exit(0); }
        }, "soma-frames").start();
        Looper.loop();
    }
}
