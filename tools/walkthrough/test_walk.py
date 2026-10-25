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
