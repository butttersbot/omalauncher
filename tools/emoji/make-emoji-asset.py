"""Makes app/src/main/assets/emoji/emoji.txt, the keyboard's emoji picker's list, from Unicode's emoji-test.txt and
CLDR's English keywords (common/annotations/en.xml). Run with both files' paths:

    python3 tools/emoji/make-emoji-asset.py emoji-test.txt en.xml > app/src/main/assets/emoji/emoji.txt

Apple's sections in Apple's order (Smileys & People are one, Component is left out). Each emoji is one line:
the emoji as fully qualified, its five skin tones (space-separated, or nothing), and the words a search finds it by
(its name, then CLDR's keywords), tab-separated. A line starting with "== " begins a section."""
import re
import sys
import xml.etree.ElementTree as ET

TONES = ["1F3FB", "1F3FC", "1F3FD", "1F3FE", "1F3FF"]
SECTIONS = [
    ("Smileys & People", ["Smileys & Emotion", "People & Body"]),
    ("Animals & Nature", ["Animals & Nature"]),
    ("Food & Drink", ["Food & Drink"]),
    ("Activity", ["Activities"]),
    ("Travel & Places", ["Travel & Places"]),
    ("Objects", ["Objects"]),
    ("Symbols", ["Symbols"]),
    ("Flags", ["Flags"]),
]


def main(test_path, cldr_path):
    words = {}
    for a in ET.parse(cldr_path).getroot().iter("annotation"):
        if a.get("type") != "tts":
            words[a.get("cp").replace("️", "")] = [w.strip() for w in a.text.split("|")]
    groups = {}
    group = None
    line_re = re.compile(r"^([0-9A-F ]+?)\s*;\s*fully-qualified\s*#\s*(\S+)\s+E[\d.]+\s+(.*)$")
    for line in open(test_path, encoding="utf-8"):
        if line.startswith("# group:"):
            group = line.split(":", 1)[1].strip()
            groups[group] = []
            continue
        m = line_re.match(line.strip())
        if m and group:
            groups[group].append((m.group(1).split(), m.group(2), m.group(3)))
    out = []
    for title, parts in SECTIONS:
        out.append("== " + title)
        items = {}
        order = []
        for part in parts:
            for cps, emoji, name in groups[part]:
                toned = [c for c in cps if c in TONES]
                if toned:
                    # A skin-tone variant: kept with its base when it has one tone throughout, as Apple offers.
                    if len(set(toned)) != 1:
                        continue
                    base = "".join(chr(int(c, 16)) for c in cps if c not in TONES and c != "FE0F")
                    if base in items:
                        items[base][1][TONES.index(toned[0])] = emoji
                    continue
                key = emoji.replace("️", "")
                items[key] = [emoji, [None] * 5, name]
                order.append(key)
        for key in order:
            emoji, tones, name = items[key]
            kw = [w for w in words.get(key, []) if w.lower() != name.lower()]
            tone_text = " ".join(tones) if all(tones) else ""
            out.append("\t".join([emoji, tone_text, " | ".join([name] + kw)]))
    sys.stdout.write("\n".join(out) + "\n")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
