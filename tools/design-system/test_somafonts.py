"""The font store's consumers (Boss, 2026-10-29: Canvas is the one store of theme fonts): build.py's stacks and faces,
--inline adding only the emoji/CJK a page needs, and soma-look's FONT FALLBACK. A stand-in store, no network."""
import hashlib, importlib.machinery, importlib.util, json, os, sys, tempfile, unittest
from pathlib import Path
from unittest.mock import patch

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
import somafonts


def load(name, path):
    loader = importlib.machinery.SourceFileLoader(name, str(path))
    spec = importlib.util.spec_from_loader(name, loader)
    mod = importlib.util.module_from_spec(spec); loader.exec_module(mod); return mod


class Store(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.cache = Path(self.tmp.name)
        folder = self.cache / "riso"; folder.mkdir()
        def face(role, family, data, coverage, bundle=True, ext="ttf", weight=(400, 400)):
            sha = hashlib.sha256(data).hexdigest(); (folder / f"{sha}.{ext}").write_bytes(data)
            return {"family": family, "role": role, "file": f"{sha}.{ext}", "sha256": sha, "size": len(data),
                    "weight": {"min": weight[0], "max": weight[1]}, "style": "normal", "coverage": coverage, "bundle": bundle}
        latin = [[0x20, 0x7E], [0xA0, 0xFF]]
        shared = [face("symbols", "Noto Sans Symbols 2", b"symbols", [[0x2190, 0x21FF], [0x2600, 0x26FF]]),
                  face("emoji", "Noto Color Emoji", b"emoji", [[0x1F300, 0x1FAFF]], bundle=False),
                  face("cjk", "Noto Sans CJK JP", b"cjk", [[0x3040, 0x30FF], [0x4E00, 0x9FFF]], bundle=False)]
        theme = {"display": face("display", "Dela Gothic One", b"display", latin),
                 "body": face("body", "Schibsted Grotesk", b"body", latin, weight=(400, 900)),
                 "hand": face("hand", "Patrick Hand", b"hand", latin),
                 "mono": face("mono", "JetBrains Mono", b"mono", latin, weight=(100, 800))}
        self.manifest = {"theme": "riso", "version": "ab" * 32, "voices": {v: [theme[v]] + shared for v in theme}}
        (folder / "manifest.json").write_text(json.dumps(self.manifest))
        self.env = patch.dict(os.environ, {"SOMA_FONTS_CACHE": str(self.cache)}); self.env.start()
        self.folder = str(folder)

    def tearDown(self):
        self.env.stop(); self.tmp.cleanup()

    def page(self, text, roles=("display", "body", "hand", "mono", "symbols")):
        css = "\n".join(somafonts.face_css(f, self.folder) for f in somafonts.faces(self.manifest) if f["role"] in roles)
        return (f'<html><head><meta name="soma-fonts" content="riso@abab"><style>{css}</style></head>'
                f'<body><script>let x="ありがとう 🎉"</script><p>{text}</p></body></html>')

    def test_stacks_keep_the_store_order_and_end_without_a_generic_family(self):
        self.assertEqual('"Dela Gothic One", "Noto Sans Symbols 2", "Noto Color Emoji", "Noto Sans CJK JP"',
                         somafonts.stack(self.manifest, "display"))
        css = somafonts.face_css(self.manifest["voices"]["body"][0], self.folder)
        self.assertIn('font-family: "Schibsted Grotesk"', css)
        self.assertIn("font-weight: 400 900", css)
        self.assertIn('format("truetype")', css)
        self.assertEqual(7, len(somafonts.faces(self.manifest)))  # each file once, though every voice shares the last three

    def test_latin_and_symbols_are_drawn_emoji_and_cjk_are_fallback_until_inlined(self):
        missing, could = somafonts.fallback(self.page("Ready → café ☀"), self.manifest)
        self.assertEqual([], missing)  # and the script's emoji/kana never count: they aren't shown
        missing, could = somafonts.fallback(self.page("Ship it 🎉 ありがとう ✈"), self.manifest)
        self.assertEqual(sorted("🎉✈ありがとう"), sorted(c for c, _ in missing))
        self.assertIn(["🎉", "U+1F389"], missing)
        self.assertEqual({"Noto Color Emoji", "Noto Sans CJK JP"}, {f["family"] for f in could})
        # ✈ U+2708 is outside every face: no store font draws it.
        self.assertIn(["✈", "U+2708"], missing)

    def test_inline_adds_only_the_faces_the_page_needs_once(self):
        build = load("build_ds", HERE / "build.py")
        with tempfile.NamedTemporaryFile("w", suffix=".html", delete=False, encoding="utf-8") as f:
            f.write(self.page("Approve 🎉"))
        with patch.object(somafonts.subprocess, "run", side_effect=OSError("no canvas")):
            build.inline(f.name); build.inline(f.name)
        html = Path(f.name).read_text(encoding="utf-8")
        self.assertEqual(1, html.count('<style id="soma-fonts-extra">'))
        self.assertIn('font-family: "Noto Color Emoji"', html)
        self.assertNotIn('font-family: "Noto Sans CJK JP"', html)
        self.assertEqual([], somafonts.fallback(html, self.manifest)[0])

    def test_soma_look_reports_font_fallback_with_the_fix(self):
        look = load("soma_look", HERE / "soma-look")
        with patch.object(somafonts.subprocess, "run", side_effect=OSError("no canvas")):
            errors, warnings = look.font_checks(self.page("Done 🎉"))
            self.assertEqual(1, len(errors))
            self.assertTrue(errors[0].startswith("FONT FALLBACK: 1 character(s)"))
            self.assertIn("🎉 U+1F389", errors[0])
            self.assertIn("Noto Color Emoji", errors[0]); self.assertIn("build.py --inline", errors[0])
            self.assertEqual(([], []), look.font_checks(self.page("All fine →")))
            errors, _ = look.font_checks(self.page("Plane ✈"))
            self.assertIn("no font in the store draws them", errors[0])
            # A page with no font of the store at all (a generic web look): every letter is a device font.
            errors, _ = look.font_checks('<meta name="soma-pack" content="riso"><p>Hi</p>')
            self.assertIn("H U+0048", errors[0])
            self.assertIn("recopy soma.css", errors[0])
            # No store and no cache: said, not passed off as checked.
            self.assertEqual([], look.font_checks('<meta name="soma-pack" content="manga"><p>x</p>')[0])
            self.assertIn("wasn't checked", look.font_checks('<meta name="soma-pack" content="manga"><p>x</p>')[1][0])


if __name__ == "__main__":
    unittest.main()
