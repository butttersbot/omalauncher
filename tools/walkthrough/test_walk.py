"""Control names exposed by Android WebView can live in hint, even with aria-label."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("walk", Path(__file__).with_name("walk.py"))
walk = importlib.util.module_from_spec(spec)
spec.loader.exec_module(walk)

class WebInputNames(unittest.TestCase):
    def test_hint_identifies_input_without_matching_the_error_above_it(self):
        xml = '''<hierarchy><node text="Enter a valid email address." content-desc="" hint="" bounds="[100,400][500,440]"/>
          <node text="" content-desc="" hint="Sign-in email" class="android.widget.EditText" bounds="[157,1107][1089,1233]"/>
          <node text="Send email code" content-desc="" hint="" bounds="[157,1265][1089,1412]"/></hierarchy>'''
        with patch.object(walk, "adb", return_value=xml), patch.object(walk, "size", return_value=(1246,2600)):
            self.assertEqual(walk.find("emulator", "Sign-in email", wait_ms=0), (623,1170))
            self.assertEqual(walk.find("emulator", "Send email code", wait_ms=0), (623,1338))


class HumanControlMatches(unittest.TestCase):
    def find(self, xml, wanted, actionable=False):
        with patch.object(walk, "adb", return_value=xml), patch.object(walk, "size", return_value=(1246,2600)):
            return walk.find("emulator", wanted, wait_ms=0, actionable=actionable)

    def test_money_tab_beats_fixture_banner(self):
        xml = '''<hierarchy><node text="FICTIONAL MARKET DEMO · NO REAL MONEY" bounds="[0,0][1000,80]"/>
          <node text="Money" clickable="true" class="android.widget.Button" bounds="[800,1800][1200,1950]"/></hierarchy>'''
        self.assertEqual(self.find(xml,"Money",True),(1000,1875))

    def test_result_button_beats_its_query_in_the_input(self):
        xml = '''<hierarchy><node text="Paper Cranes" hint="Search markets" class="android.widget.EditText" clickable="true" bounds="[100,100][1000,200]"/>
          <node text="Paper Cranes vs Ink Foxes" class="android.widget.Button" clickable="true" bounds="[100,400][1000,500]"/></hierarchy>'''
        self.assertEqual(self.find(xml,"Paper Cranes",True),(550,450))

    def test_typographic_apostrophe_is_the_same_label(self):
        xml = '''<hierarchy><node text="Today’s issue" bounds="[100,100][900,200]"/></hierarchy>'''
        self.assertEqual(self.find(xml,"Today's issue"),(500,150))

class FreshAccessibilityTrees(unittest.TestCase):
    ready = '<hierarchy><node class="android.webkit.WebView" content-desc="Hunch" bounds="[0,0][100,200]"><node text="Copy wallet address" bounds="[0,30][100,50]"/></node></hierarchy>'
    empty = '<hierarchy><node class="android.webkit.WebView" content-desc="Hunch" bounds="[0,0][100,200]"/></hierarchy>'

    # From emulator-5554's own dumps (169, 2026-10-26): Android's share prompt and shade are all com.android.systemui.
    share_prompt = ('<hierarchy><node package="com.android.systemui" resource-id="" bounds="[0,0][1080,2400]">'
                    '<node package="com.android.systemui" text="Share your screen with Omalauncher Boss?" bounds="[90,560][990,700]"/>'
                    '<node package="com.android.systemui" text="Share entire screen" bounds="[90,760][990,860]"/>'
                    '<node package="com.android.systemui" text="Cancel" bounds="[520,1200][700,1280]"/>'
                    '<node package="com.android.systemui" text="Share screen" bounds="[730,1200][990,1280]"/></node></hierarchy>')
    shade = ('<hierarchy><node package="com.android.systemui" resource-id="com.android.systemui:id/notification_panel" bounds="[0,0][1080,2400]">'
             '<node package="com.android.systemui" resource-id="com.android.systemui:id/split_shade_status_bar" bounds="[0,0][1080,120]">'
             '<node package="com.android.systemui" resource-id="com.android.systemui:id/clock" text="9:06" bounds="[20,20][120,100]"/></node>'
             '<node package="com.android.systemui" resource-id="android:id/title" text="Recording · tap to stop" bounds="[60,400][900,460]"/>'
             '</node></hierarchy>')
    status_bar_only = ('<hierarchy><node package="com.android.systemui" resource-id="com.android.systemui:id/status_bar" bounds="[0,0][1080,120]">'
                       '<node package="com.android.systemui" resource-id="com.android.systemui:id/clock" text="9:06" bounds="[20,20][120,100]"/>'
                       '<node package="com.android.systemui" resource-id="com.android.systemui:id/system_icons" bounds="[800,20][1060,100]">'
                       '<node package="com.android.systemui" resource-id="com.android.systemui:id/wifi_signal" content-desc="Wifi signal full." bounds="[820,20][880,100]"/>'
                       '<node package="com.android.systemui" content-desc="Battery charging, 100 percent." bounds="[900,20][1060,100]"/></node></node>'
                       '<node package="dev.omalauncher.boss" class="android.widget.FrameLayout" bounds="[0,120][1080,2400]"/></hierarchy>')

    def test_android_share_prompt_is_a_ready_tree(self):
        labels = [l.strip() for l, _ in walk.parse_nodes(self.share_prompt)]
        self.assertIn("Share screen", labels)

    def test_android_shade_with_a_notification_is_a_ready_tree(self):
        self.assertIn("Recording · tap to stop", [l.strip() for l, _ in walk.parse_nodes(self.shade)])

    def test_status_bar_alone_is_not_a_ready_tree(self):
        with self.assertRaises(walk.TreeUnavailable):
            walk.parse_nodes(self.status_bar_only)

    def test_named_webview_shell_is_not_a_ready_tree(self):
        with self.assertRaises(walk.TreeUnavailable):
            walk.parse_nodes(self.empty)

    def test_empty_webview_retries_before_returning_fresh_controls(self):
        reads = iter([self.empty, self.ready])
        def adb(serial, *args, **kwargs):
            return next(reads) if args[:2] == ("shell", "cat") else ""
        with patch.object(walk, "adb", side_effect=adb) as call, patch.object(walk.time, "sleep"):
            self.assertIn("Copy wallet address", walk.nodes("emu", wait_ms=0)[1][0])
            self.assertEqual(2, sum(c.args[1:4] == ("shell", "rm", "-f") for c in call.call_args_list))

    def test_failed_dump_never_reads_the_previous_xml_file(self):
        def adb(serial, *args, **kwargs):
            if args[:2] == ("shell", "uiautomator"):
                raise RuntimeError("UiAutomation Bad file descriptor")
            return self.ready  # stale text exists, but must never be read after a failed dump
        with patch.object(walk, "adb", side_effect=adb) as call, patch.object(walk.time, "sleep"):
            with self.assertRaises(walk.TreeUnavailable):
                walk.nodes("emu", wait_ms=0)
            self.assertFalse(any(c.args[1:3] == ("shell", "cat") for c in call.call_args_list))

    def test_absent_cannot_pass_without_accessibility_evidence(self):
        with patch.object(walk, "shot"), patch.object(walk, "size", return_value=(100,200)), patch.object(walk, "nodes", side_effect=walk.TreeUnavailable("empty")):
            with self.assertRaises(walk.TreeUnavailable):
                walk.run_step("emu", "absent", ["Copy wallet address"], "/tmp", 1)

    def test_scroll_does_not_move_the_screen_on_an_unknown_tree(self):
        with patch.object(walk, "adb") as adb, patch.object(walk, "find", side_effect=walk.TreeUnavailable("empty")):
            with self.assertRaises(walk.TreeUnavailable):
                walk.run_step("emu", "scrollto", ["Copy wallet address"], "/tmp", 1)
            adb.assert_not_called()

    def test_launch_and_rotate_wait_for_fresh_controls(self):
        with patch.object(walk, "adb"), patch.object(walk, "size", return_value=(100,200)), patch.object(walk.time, "sleep"), patch.object(walk, "wait_for_tree") as ready:
            walk.run_step("emu", "launch", ["app/.Activity"], "/tmp", 1)
            walk.run_step("emu", "shell", ["am", "start", "-n", "app/.Activity"], "/tmp", 2)
            walk.run_step("emu", "rotate", ["landscape"], "/tmp", 3)
            self.assertEqual(3, ready.call_count)




class WholeLabelBounds(unittest.TestCase):
    def test_uses_rotated_capture_and_accepts_left_aligned_footer(self):
        import tempfile
        def capture(serial, path):
            Path(path).write_bytes(b"\x89PNG\r\n\x1a\n" + b"\0" * 8 + (1848).to_bytes(4, "big") + (2448).to_bytes(4, "big"))
        for bounds, expected in [([90, 1971, 1282, 2012], True), ([90, 1971, 1282, 2200], False)]:
            with tempfile.TemporaryDirectory() as out, patch.object(walk, "size", return_value=(2448, 1848)), patch.object(walk, "shot", side_effect=capture), patch.object(walk, "nodes", return_value=[("Fixture only", bounds)]):
                ok, _, _ = walk.run_step("emu", "within", ["0.1", "0.85", "Fixture", "only"], out, 1)
                self.assertEqual(expected, ok)

if __name__ == "__main__":
    unittest.main()
