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


if __name__ == "__main__":
    unittest.main()
