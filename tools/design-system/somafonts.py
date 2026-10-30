"""The theme's fonts as Soma Canvas keeps them (the font store; Boss, 2026-10-29: Canvas is the one store of theme
fonts, so the phone, the Macs and MG1 draw the same). Shared by build.py and soma-look.

A theme's manifest (`soma-canvas fonts THEME --out DIR`) gives each voice (display, body, hand, mono) an ordered stack:
the theme's face, then Noto Sans Symbols 2, then Noto Color Emoji and Noto Sans CJK JP. Faces are whole files, never
cut. Faces with bundle: true (the theme's and the symbols font) go into every design; emoji and CJK (bundle: false,
25 MB and 16 MB) are inlined into a page only when it has characters that need them (Boss, 2026-10-29). A character
none of the page's faces covers is drawn by a device font: FONT FALLBACK.
"""
import base64, hashlib, json, os, re, subprocess
from html.parser import HTMLParser

def cache_dir():
    return os.path.expanduser(os.environ.get("SOMA_FONTS_CACHE", "~/.cache/soma-fonts"))
VOICES = ("display", "body", "hand", "mono")
FORMATS = {"ttf": "truetype", "otf": "opentype", "woff2": "woff2", "woff": "woff"}
MIME = {"ttf": "font/ttf", "otf": "font/otf", "woff2": "font/woff2", "woff": "font/woff"}


def fetch(theme, cache=None, run=None):
    """The theme's manifest and the folder its files are in: fresh from Canvas, else the last copy fetched (offline),
    else (None, folder). The CLI skips files it already has, so this is cheap once warm."""
    folder = os.path.join(cache or cache_dir(), theme)
    try:
        (run or subprocess.run)(["soma-canvas", "fonts", theme, "--out", folder], capture_output=True, text=True, timeout=600, check=True)
    except Exception:
        pass
    path = os.path.join(folder, "manifest.json")
    if not os.path.isfile(path):
        return None, folder
    return json.load(open(path)), folder


def faces(manifest, voices=VOICES):
    """Every face in the voices' stacks, each once (by file), in stack order."""
    seen, out = set(), []
    for v in voices:
        for f in manifest.get("voices", {}).get(v, []):
            if f["file"] not in seen:
                seen.add(f["file"])
                out.append(f)
    return out


def stack(manifest, voice):
    """The CSS font-family list for a voice: the store's stack in order, with no generic family after it, so a missing
    character shows as a device fallback rather than quietly drawing in some other font."""
    names = []
    for f in manifest["voices"][voice]:
        if f["family"] not in names:
            names.append(f["family"])
    return ", ".join(f'"{n}"' for n in names)


def face_css(face, folder):
    """One @font-face for a face, the whole file as a data URL (mockups are offline single files)."""
    ext = face["file"].rsplit(".", 1)[-1].lower()
    data = base64.b64encode(open(os.path.join(folder, face["file"]), "rb").read()).decode()
    w = face.get("weight") or {}
    weight = f'{w.get("min", 400)} {w.get("max", 400)}' if w.get("min") != w.get("max") else str(w.get("min", 400))
    return (f'@font-face {{ font-family: "{face["family"]}"; src: url(data:{MIME.get(ext, "font/" + ext)};base64,{data}) '
            f'format("{FORMATS.get(ext, ext)}"); font-weight: {weight}; font-style: {face.get("style", "normal")}; '
            f'font-display: block; }} /* soma-font {face["sha256"]} {face["role"]} */')


def covered(face, cp):
    return any(lo <= cp <= hi for lo, hi in face.get("coverage", []))


class _Text(HTMLParser):
    """What a page shows as text: its text nodes outside script/style, the attributes that draw text, and CSS
    content strings."""
    ATTRS = ("placeholder", "alt", "title", "value", "aria-label")

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts, self.skip = [], 0

    def handle_starttag(self, tag, attrs):
        if tag in ("script", "style", "template"):
            self.skip += 1
        for k, v in attrs:
            if k in self.ATTRS and v:
                self.parts.append(v)

    def handle_endtag(self, tag):
        if tag in ("script", "style", "template") and self.skip:
            self.skip -= 1

    def handle_data(self, data):
        if not self.skip:
            self.parts.append(data)


def page_text(html):
    p = _Text()
    p.feed(html)
    css = " ".join(re.findall(r"<style[^>]*>(.*?)</style>", html, re.S | re.I))
    p.parts += re.findall(r"""content\s*:\s*["']([^"']*)["']""", css)
    return "".join(p.parts)


def characters(text):
    """The characters that need a glyph: not spaces, controls or joiners."""
    return {ch for ch in text if not ch.isspace() and ord(ch) >= 0x20 and ord(ch) not in (0x200d, 0xfe0e, 0xfe0f)}


def inlined(html):
    """The store files a page carries, by sha256 of the data URLs' bytes (a renamed family still counts)."""
    shas = set()
    for m in re.finditer(r"url\(\s*['\"]?data:[^;,]+;base64,([A-Za-z0-9+/=\s]+)['\"]?\s*\)", html):
        try:
            shas.add(hashlib.sha256(base64.b64decode(re.sub(r"\s", "", m.group(1)))).hexdigest())
        except Exception:
            pass
    return shas


def fallback(html, manifest):
    """FONT FALLBACK check: (chars no carried face draws, faces from the store that would draw some of them).
    Each char is [char, codepoint]; the faces are the store's emoji/CJK (or others) the page could inline."""
    have = inlined(html)
    store = faces(manifest)
    carried = [f for f in store if f["sha256"] in have]
    missing = sorted((c for c in characters(page_text(html)) if not any(covered(f, ord(c)) for f in carried)), key=ord)
    could = [f for f in store if f["sha256"] not in have and any(covered(f, ord(c)) for c in missing)]
    return [[c, f"U+{ord(c):04X}"] for c in missing], could


def extra_css(html, manifest, folder):
    """@font-face rules for the store's faces this page needs and doesn't carry (emoji, CJK), only those."""
    _, could = fallback(html, manifest)
    return "\n".join(face_css(f, folder) for f in could)


def app_assets(root, run=None):
    """The apps' share of the store (Boss, 2026-10-29): the symbols font bundled (assets/fonts/), and a pin list,
    assets/fonts/store.json, of the faces each device fetches once from Canvas and caches (emoji, CJK: bundle false).
    Theme faces are the packs' own files, already bundled. Returns what was written."""
    import shutil
    out = os.path.join(root, "app/src/main/assets/fonts")
    os.makedirs(out, exist_ok=True)
    r = (run or subprocess.run)(["soma-canvas", "fonts"], capture_output=True, text=True, timeout=120, check=True)
    themes = list(json.loads(r.stdout)["themes"])
    # The shared faces (symbols, emoji, CJK) end every theme's stacks; read them from a theme whose files are all there.
    shared, folder = [], None
    for theme in themes:
        manifest, folder = fetch(theme)
        if manifest:
            shared = [f for f in faces(manifest) if f["role"] in ("symbols", "emoji", "cjk")]
            if len({f["sha256"] for f in shared}) == 3 and all(os.path.isfile(os.path.join(folder, f["file"])) for f in shared if f.get("bundle")):
                break
    else:
        raise SystemExit("no theme in the store has its three shared faces (symbols, emoji, CJK) whole")
    pins, written = {"bundled": [], "fetched": []}, []
    for f in shared:
        entry = {k: f[k] for k in ("role", "family", "file", "sha256", "size")}
        if f.get("bundle"):
            src = os.path.join(folder, f["file"])
            data = open(src, "rb").read()
            if hashlib.sha256(data).hexdigest() != f["sha256"]:
                raise SystemExit(f"{f['file']}: not the file the store pins")
            name = re.sub(r"\W+", "", f["family"]) + "." + f["file"].rsplit(".", 1)[-1]
            shutil.copyfile(src, os.path.join(out, name))
            entry["asset"] = f"fonts/{name}"
            pins["bundled"].append(entry); written.append(name)
        else:
            pins["fetched"].append(entry)
    json.dump(pins, open(os.path.join(out, "store.json"), "w"), indent=1)
    return written + ["store.json"]
