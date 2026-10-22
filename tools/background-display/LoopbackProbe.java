import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Developer-only authenticated helper check, run with Soma's UID. Never prints the private key. */
public class LoopbackProbe {
    private static String mac(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes("UTF-8")));
    }
    public static void main(String[] args) throws Exception {
        byte[] key = Files.readAllBytes(Paths.get("/data/user/0/dev.omalauncher.boss/files/background-display.key"));
        try (Socket socket = new Socket("127.0.0.1", 18746)) {
            socket.setSoTimeout(2000);
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            byte[] bytes = new byte[24]; new SecureRandom().nextBytes(bytes);
            String c = Base64.getEncoder().encodeToString(bytes);
            socket.getOutputStream().write((c + "\n").getBytes("UTF-8"));
            String[] hello = input.readLine().split(" ", 2);
            String prefix = c + " " + hello[0];
            if (!MessageDigest.isEqual(hello[1].getBytes("UTF-8"), mac(key, prefix + " server").getBytes("UTF-8"))) throw new SecurityException("Bad helper identity");
            socket.getOutputStream().write((args[0] + " " + mac(key, prefix + " " + args[0]) + "\n").getBytes("UTF-8"));
            String response = input.readLine(); int split = response.lastIndexOf(' ');
            if (!MessageDigest.isEqual(response.substring(split + 1).getBytes("UTF-8"), mac(key, prefix + " " + response.substring(0, split)).getBytes("UTF-8"))) throw new SecurityException("Bad response signature");
            System.out.println(response.substring(0, split));
        }
        System.exit(0);
    }
}
