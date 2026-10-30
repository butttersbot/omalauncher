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
  check) if [ "$3" = --waiver ]; then f="$FAKE_DIR/$2.waiver.json"; else f="$FAKE_DIR/$2.json"; fi
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

    def test_drift_is_a_design_fail_and_refuses_until_boss_waives_it(self):
        self.answer("conn-tile", result="match", differences=[], waived=False)
        self.answer("conn-footer", result="drift", waived=False,
                    differences=[{"n": 1, "what": "Restart sits under the list, Approved has it in the header"}])
        r = gate.run(str(self.wdir), "176", "abc123456")
        self.assertEqual("fail", r["result"])
        text = gate.summary(r)
        self.assertTrue(text.startswith("DESIGN FAIL: 1 of 2 walked cards"))
        self.assertIn("conn-footer (connections, 05-footer.png)", text)
        self.assertIn("1. Restart sits under the list", text)
        # Only a passed walk is published or checked: news failed, so its card never reaches Canvas.
        self.assertFalse(any("news" in c for c in self.calls()))
        self.assertIn("publish soma-widgets " + str(self.wdir / "connections"), " ".join(self.calls()))  # its script in this checkout
        report = str(self.wdir / "design-report.json")
        self.assertEqual(3, gate.gate(report, "abc123456789"))
        # Boss waives it on Canvas after the walk: install asks again and lets it through.
        (self.t / "conn-footer.waiver.json").write_text(json.dumps({"card": "conn-footer", "result": "drift", "waived": True,
                                                                   "waiver": {"by": "boss", "build": "abc123456"}}))
        self.assertEqual(0, gate.gate(report, "abc123456789"))

    def test_a_check_that_cannot_run_never_blocks_but_says_design_unchecked(self):
        r = gate.run(str(self.wdir), "176", "abc123456")
        self.assertEqual("unchecked", r["result"])
        self.assertTrue(all(c["result"] == "error" and "check unavailable" in c["error"] for c in r["cards"]))
        self.assertIn("DESIGN UNCHECKED: conn-tile, conn-footer", gate.summary(r))
        with patch("builtins.print") as out:
            self.assertEqual(0, gate.gate(str(self.wdir / "design-report.json"), "abc123456"))
        self.assertIn("DESIGN UNCHECKED", " ".join(str(c.args[0]) for c in out.call_args_list))
        # No report at all (walks still running, or no carded walk) is unchecked too, never silence.
        with patch("builtins.print") as out:
            self.assertEqual(0, gate.gate(None))
        self.assertTrue(out.call_args.args[0].startswith("DESIGN UNCHECKED"))

    def test_a_card_with_nothing_approved_is_design_unapproved_and_never_blocks(self):
        # Amendment 77d43019 (Boss: "I don't want things to be blocked if I forgot to click a few approve buttons").
        self.answer("conn-tile", result="no-approved", waived=False)
        self.answer("conn-footer", result="match", waived=False)
        r = gate.run(str(self.wdir), "176", "abc123456")
        self.assertEqual("unapproved", r["result"])
        text = gate.summary(r)
        self.assertIn("DESIGN UNAPPROVED: conn-tile", text)
        self.assertIn("one native question card", text)
        self.assertNotIn("DESIGN FAIL", text)
        report = str(self.wdir / "design-report.json")
        (self.t / "conn-tile.waiver.json").write_text(json.dumps({"card": "conn-tile", "result": "no-approved", "waived": False}))
        with patch("builtins.print") as out:
            self.assertEqual(0, gate.gate(report, "abc123456"))
        self.assertIn("DESIGN UNAPPROVED: conn-tile", " ".join(str(c.args[0]) for c in out.call_args_list))
        # He approves it (chat or Canvas) after the walk and Shipped matches: plain pass, nothing printed as unapproved.
        (self.t / "conn-tile.waiver.json").write_text(json.dumps({"card": "conn-tile", "result": "match", "waived": False}))
        with patch("builtins.print") as out:
            self.assertEqual(0, gate.gate(report, "abc123456"))
        self.assertNotIn("UNAPPROVED", " ".join(str(c.args[0]) for c in out.call_args_list))
        # What he approved differs from what shipped: that is drift, and drift still blocks.
        (self.t / "conn-tile.waiver.json").write_text(json.dumps({"card": "conn-tile", "result": "drift", "waived": False,
                                                                 "differences": [{"n": 1, "what": "no Restart"}]}))
        self.assertEqual(3, gate.gate(report, "abc123456"))

    def test_canvas_unreachable_at_install_keeps_the_walks_answer(self):
        self.answer("conn-tile", result="no-approved", waived=False)
        self.answer("conn-footer", result="drift", waived=False, differences=[{"n": 1, "what": "x"}])
        gate.run(str(self.wdir), "176", "abc123456")
        # No waiver files: every re-ask errors, so drift still refuses and unapproved still passes.
        self.assertEqual(3, gate.gate(str(self.wdir / "design-report.json"), "abc123456"))
        self.answer("conn-footer", result="match", waived=False)
        gate.run(str(self.wdir), "176", "abc123456")
        self.assertEqual(0, gate.gate(str(self.wdir / "design-report.json"), "abc123456"))

    def test_report_for_another_build_holds_nothing(self):
        self.answer("conn-tile", result="match", waived=False)
        self.answer("conn-footer", result="drift", waived=False, differences=[{"n": 1, "what": "x"}])
        gate.run(str(self.wdir), "176", "abc123456")
        self.assertEqual(0, gate.gate(str(self.wdir / "design-report.json"), "fff999000"))

    def test_inbox_report_finds_taildrops_copy_and_none_holds_nothing(self):
        inbox = self.t / "inbox"; inbox.mkdir()
        self.assertIsNone(gate.inbox_report("abc123456789", "176", str(inbox)))
        (inbox / "design-abc123456-176 (2).json").write_text("{}")
        self.assertTrue(gate.inbox_report("abc123456789", "176", str(inbox)).endswith("(2).json"))

    def test_font_fallback_from_the_check_is_printed_never_blocking(self):
        self.answer("conn-tile", result="match", waived=False, differences=[],
                    fontFallback=[{"font": "Roboto", "chars": "✓△", "where": "status row"}])
        self.answer("conn-footer", result="match", waived=False, differences=[], fontFallback=[])
        r = gate.run(str(self.wdir), "176", "abc123456")
        self.assertEqual("match", r["result"])
        text = gate.summary(r)
        self.assertIn("FONT FALLBACK: conn-tile draw characters in a device font", text)
        self.assertIn("conn-tile: ✓△ in Roboto (status row)", text)
        self.assertNotIn("conn-footer:", text)
        self.assertEqual(0, gate.gate(str(self.wdir / "design-report.json"), "abc123456"))

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
