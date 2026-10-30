"""The design gate's walk side against a stand-in soma-canvas (the contract mailed to taebc3f66, 2026-10-29)."""
import importlib.util, json, os, tempfile, unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("design_gate", Path(__file__).with_name("design_gate.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

FAKE = r'''#!/bin/sh
# Logs each call; answers check from $FAKE_DIR/<card>.json (missing: no such command, as before taebc3f66 ships it).
echo "$*" >> "$FAKE_DIR/calls"
case $1 in
  publish) echo "published"; exit 0 ;;
  check) f="$FAKE_DIR/$2.json"
         [ -f "$f" ] || { echo "soma-canvas: unknown command check" >&2; exit 2; }
         cat "$f"; grep -q '"drift"' "$f" && ! grep -q '"waived": *true' "$f" && exit 3; exit 0 ;;
esac
'''

class Gate(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); t = Path(self.tmp.name)
        (t / "soma-canvas").write_text(FAKE); (t / "soma-canvas").chmod(0o755)
        self.env = patch.dict(os.environ, {"SOMA_CANVAS": str(t / "soma-canvas"), "FAKE_DIR": str(t)}); self.env.start()
        self.t, self.wdir = t, t / "walk"
        for w, verdict, cards in [("connections", "PASS", {"03-tile": "conn-tile", "05-footer": "conn-footer"}),
                                  ("news", "FAIL (1 of 9 steps, exit 1)", {"02-book": "news-book"}),
                                  ("pet", "PASS", None)]:
            (self.wdir / w).mkdir(parents=True)
            (self.wdir / w / "results.json").write_text(json.dumps({"script": "x.walk", "steps": []}))
            if cards:
                (self.wdir / w / "canvas.json").write_text(json.dumps(cards))
                for stem in cards: (self.wdir / w / (stem + ".png")).write_bytes(b"png")
            with open(self.wdir / "verdicts.txt", "a") as f: f.write(f"{w} {verdict}\n")

    def tearDown(self):
        self.env.stop(); self.tmp.cleanup()

    def answer(self, card, **r):
        (self.t / f"{card}.json").write_text(json.dumps({"card": card, **r}))

    def calls(self):
        return (self.t / "calls").read_text().splitlines()

    def test_a_difference_is_listed_and_holds_nothing(self):
        self.answer("conn-tile", result="match", differences=[], waived=False)
        self.answer("conn-footer", result="drift", waived=False, differences=[{"n": 1, "what": "Restart sits under the list"}])
        r = gate.run(str(self.wdir), "176", "abc123456")
        text = gate.summary(r)
        self.assertTrue(text.startswith("Design diff (advisory, holds nothing): 1 of 2 walked screens"))
        self.assertIn("conn-footer: Restart sits under the list", text)
        # One quick look each, before the publish; a failed walk is neither checked nor published.
        calls = self.calls()
        self.assertFalse(any("news" in c for c in calls))
        self.assertTrue(all("--quick" in c for c in calls if c.startswith("check")))
        self.assertTrue(calls[-1].startswith("publish soma-widgets "))
        self.assertEqual(0, gate.gate(str(self.wdir / "design-report.json"), "abc123456789"))

    def test_a_diff_that_cannot_run_says_so_and_moves_on(self):
        r = gate.run(str(self.wdir), "176", "abc123456")
        self.assertIn("couldn't run for conn-footer, conn-tile; moved on", gate.summary(r).replace("conn-tile, conn-footer", "conn-footer, conn-tile"))
        self.assertTrue(any(c.startswith("publish") for c in self.calls()))
        self.assertEqual(0, gate.gate(str(self.wdir / "design-report.json"), "abc123456"))

    def test_nothing_to_report_or_another_build_holds_nothing(self):
        self.assertEqual(0, gate.gate(None))
        self.assertEqual(0, gate.gate(str(self.t / "missing.json"), "abc"))
        (self.t / "r.json").write_text(json.dumps({"build": "fff999000", "cards": [{"card": "c", "result": "drift", "differences": []}]}))
        self.assertEqual(0, gate.gate(str(self.t / "r.json"), "abc123456"))

    def test_inbox_report_finds_taildrops_copy_and_none_holds_nothing(self):
        inbox = self.t / "inbox"; inbox.mkdir()
        self.assertIsNone(gate.inbox_report("abc123456789", "176", str(inbox)))
        (inbox / "design-abc123456-176 (2).json").write_text("{}")
        self.assertTrue(gate.inbox_report("abc123456789", "176", str(inbox)).endswith("(2).json"))

    def test_canvas_app_comes_from_the_script_or_the_walk_name(self):
        s = self.t / "c.walk"; s.write_text("# canvas-app: soma-messages\nlaunch x\n")
        self.assertEqual("soma-messages", gate.canvas_app("chat", {"script": str(s)}))
        self.assertEqual("hunch", gate.canvas_app("hunch-wallet", {}))
        self.assertEqual("soma", gate.canvas_app("no-such-walk", {}))
        # A lane's output read elsewhere: canvasApp from the walk itself wins over a path from the walking machine.
        self.assertEqual("soma-widgets", gate.canvas_app("connections", {"script": "/Users/x/connections.walk", "canvasApp": "soma-widgets"}))
        # Older output without it: the script of that name in this checkout.
        self.assertEqual("soma-widgets", gate.canvas_app("connections", {"script": "/Users/x/Projects/connections.walk"}))

if __name__ == "__main__":
    unittest.main()
