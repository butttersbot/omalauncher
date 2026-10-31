import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.LocalServerSocket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Developer probe run with `adb shell run-as dev.omalauncher.boss` on a -PsomaDebuggable build (emulator); no debug-exec switch needed. */
public class Probe {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("cert")) {
            android.os.Looper.prepareMainLooper();
            Class<?> type = Class.forName("android.app.ActivityThread");
            Object thread = type.getMethod("systemMain").invoke(null);
            android.content.Context context = (android.content.Context) type.getMethod("getSystemContext").invoke(thread);
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo("dev.omalauncher.boss", android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(info.signingInfo.getApkContentsSigners()[0].toByteArray());
            for (byte b : hash) System.out.printf("%02x", b);
            System.out.println(); System.exit(0);
        }
        if (args[0].equals("serve")) {
            try (LocalServerSocket server = new LocalServerSocket("dev.omalauncher.boss.bgdisplay"); LocalSocket socket = server.accept()) {
                System.out.println("HELPER_UID " + socket.getPeerCredentials().getUid());
                socket.setSoTimeout(3000);
                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                System.out.println(input.readLine());
                socket.getOutputStream().write("status\n".getBytes(StandardCharsets.UTF_8));
                System.out.println(input.readLine());
            }
            System.exit(0);
        }
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress(args[0], args[0].startsWith("/") ? LocalSocketAddress.Namespace.FILESYSTEM : LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(12000);
            byte[] body = args[1].getBytes(StandardCharsets.UTF_8);
            if (args[0].endsWith(".phone")) {
                socket.getOutputStream().write(("POST /phone HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().write(body);
            } else socket.getOutputStream().write((args[1] + "\n").getBytes(StandardCharsets.UTF_8));
            byte[] chunk = new byte[8192];
            int n;
            while ((n = socket.getInputStream().read(chunk)) != -1) System.out.write(chunk, 0, n);
        }
        System.exit(0);
    }
}
