import android.app.UiAutomation;
import android.os.HandlerThread;
import android.os.Looper;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.util.SparseArray;
import java.util.List;

/** Read-only shell diagnostic. Keeps the user's accessibility services connected. */
public class AccessibilityProbe {
    public static void main(String[] args) throws Exception {
        HandlerThread thread = new HandlerThread("display-a11y-probe"); thread.start();
        Class<?> connection = Class.forName("android.app.IUiAutomationConnection");
        Object binder = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
        java.lang.reflect.Constructor<UiAutomation> ctor = UiAutomation.class.getDeclaredConstructor(Looper.class, connection);
        ctor.setAccessible(true);
        UiAutomation automation = ctor.newInstance(thread.getLooper(), binder);
        try {
            UiAutomation.class.getMethod("connect", int.class).invoke(automation, 1); // FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
            AccessibilityServiceInfo info = automation.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(info);
            Thread.sleep(500);
            SparseArray<List<AccessibilityWindowInfo>> all = automation.getWindowsOnAllDisplays();
            for (int i = 0; i < all.size(); i++) {
                for (AccessibilityWindowInfo window : all.valueAt(i)) {
                    android.view.accessibility.AccessibilityNodeInfo root = window.getRoot();
                    System.out.println("display=" + all.keyAt(i) + " window=" + window.getId() + " type=" + window.getType()
                        + " readable=" + (root != null) + " package=" + (root == null ? "" : root.getPackageName()));
                    if (root != null) root.recycle();
                }
            }
        } finally { UiAutomation.class.getMethod("disconnect").invoke(automation); thread.quitSafely(); }
        System.exit(0);
    }
}
