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

if __name__ == "__main__":
    unittest.main()
