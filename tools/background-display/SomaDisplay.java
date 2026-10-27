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
    // How long the display stays after Soma last asked for it.
    private static final long IDLE_MS = 120_000;
    // OWN_FOCUS keeps a focused window on the agent display, but alone still lets a
    // launch or injected touch steal the system's top-focused display / IME from Boss.
    // Android's STEAL_TOP_FOCUS_DISABLED requires TRUSTED + OWN_FOCUS (already set).
    // Source: android16-release DisplayManager.java, VIRTUAL_DISPLAY_FLAG_*.
    private static final int OWN_FOCUS = 1 << 14;
    private static final int STEAL_TOP_FOCUS_DISABLED = 1 << 16;
    private static final int FLAGS = 1 | 8 | 64 | 256 | 1024 | 2048 | 4096
            | OWN_FOCUS | STEAL_TOP_FOCUS_DISABLED;

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
        File request = new File(DIR, "shot"), png = new File(DIR, "shot.png"), stop = new File(DIR, "stop");
        request.delete(); png.delete(); stop.delete(); new File(DIR, "display").delete();
        // On demand (Boss, 2026-10-26): the display exists only while an agent works in the background. Banking apps
        // (Robinhood) refuse to run beside any extra display, and FFIV Companion drew its touch screen on it. Soma asks
        // for it ("open") before each background step; after IDLE_MS with no ask it is released. "close" releases now.
        final Object lock = new Object();
        final VirtualDisplay[] shown = new VirtualDisplay[1];
        final ImageReader[] frames = new ImageReader[1];
        final long[] lastAsked = new long[1];
        Runnable release = () -> {
            synchronized (lock) {
                if (shown[0] == null) return;
                shown[0].release(); shown[0] = null;
                frames[0].close(); frames[0] = null;
                new File(DIR, "display").delete(); png.delete();
                System.out.println("CLOSED");
                System.out.flush();
            }
        };
        Runtime.getRuntime().addShutdownHook(new Thread(release));
        System.out.println("READY (no display until asked)");
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
                    int id = 0;
                    synchronized (lock) {
                        if ("open".equals(command)) {
                            lastAsked[0] = System.currentTimeMillis();
                            if (shown[0] == null) {
                                frames[0] = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
                                VirtualDisplay made = manager.createVirtualDisplay("soma-agent", width, height, dpi, frames[0].getSurface(), FLAGS);
                                if (made == null || made.getDisplay().getDisplayId() == 0) { frames[0].close(); frames[0] = null; throw new IllegalStateException("No secondary display"); }
                                shown[0] = made;
                                try (FileOutputStream out = new FileOutputStream(new File(DIR, "display"))) {
                                    out.write(Integer.toString(made.getDisplay().getDisplayId()).getBytes("UTF-8"));
                                }
                                System.out.println("DISPLAY " + made.getDisplay().getDisplayId());
                                System.out.flush();
                            }
                        } else if ("takeover".equals(command) && shown[0] != null) {
                            Class<?> atm = Class.forName("android.app.ActivityTaskManager");
                            Object service = atm.getMethod("getService").invoke(null);
                            Class<?> api = Class.forName("android.app.IActivityTaskManager");
                            List<?> tasks = (List<?>) api.getMethod("getAllRootTaskInfosOnDisplay", int.class).invoke(service, shown[0].getDisplay().getDisplayId());
                            for (Object task : tasks) {
                                int taskId = task.getClass().getField("taskId").getInt(task);
                                api.getMethod("moveRootTaskToDisplay", int.class, int.class).invoke(service, taskId, 0);
                            }
                        } else if (!"status".equals(command) && !"close".equals(command)) continue;
                        if (shown[0] != null) id = shown[0].getDisplay().getDisplayId();
                    }
                    if ("close".equals(command)) { release.run(); id = 0; }
                    String result = new JSONObject().put("id", id).put("onDemand", true)
                        .put("width", width).put("height", height).put("alwaysUnlocked", true).toString();
                    client.getOutputStream().write((result + " " + mac(key, prefix + " " + result) + "\n").getBytes("UTF-8"));
                } catch (Exception error) { System.err.println("Display connection refused: " + error.getClass().getSimpleName()); }
            }
        }, "soma-display-control").start();
        // Drains frames while the display exists (an undrained ImageReader stops the producer), takes screenshots, and
        // lets the display go after IDLE_MS without an "open".
        new Thread(() -> {
            Image latest = null;
            try {
                while (!stop.exists()) {
                    synchronized (lock) {
                        if (shown[0] != null && System.currentTimeMillis() - lastAsked[0] > IDLE_MS) {
                            if (latest != null) { latest.close(); latest = null; }
                            release.run();
                        }
                        Image frame = frames[0] == null ? null : frames[0].acquireLatestImage();
                        if (frames[0] == null && latest != null) { try { latest.close(); } catch (RuntimeException gone) { } latest = null; }
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
                    }
                    Thread.sleep(100);
                }
            } catch (Exception error) { error.printStackTrace(); }
            finally { if (latest != null) latest.close(); release.run(); System.exit(0); }
        }, "soma-frames").start();
        Looper.loop();
    }
}
