package dev.omalauncher.notes.engine

/**
 * The reader's own page for a Markdown note, in the Manga look (design/soma-notes-mockup.html's .md). Narrow, a
 * table's rows become little cards. As a [book] (open and sideways), two pages a spread, turned by a tap at either
 * side, with the page numbers below. Its script is ours only: the note itself is escaped HTML, never run.
 */
object ReaderPage {
    fun of(
        md: String,
        book: Boolean,
        narrow: Boolean,
    ): String =
        """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<style>
@font-face { font-family: D; src: url(fonts/DelaGothicOne-Regular.ttf); }
@font-face { font-family: B; src: url(fonts/SchibstedGrotesk.ttf); }
@font-face { font-family: M; src: url(fonts/JetBrainsMono.ttf); }
:root { --paper: #fdfdfb; --ink: #111113; --muted: #77777e; --red: #d7262d; --tone: #e9e9ea; }
* { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
html, body { margin: 0; background: var(--paper); color: var(--ink); }
body { font: 16.5px/1.58 B, sans-serif; padding: 6px 20px 90px; overflow-wrap: break-word; }
h1 { font-family: D; font-weight: normal; font-size: 27px; line-height: 1.15; margin: 4px 0 12px; }
h2 { font-size: 19px; margin: 26px 0 8px; padding-top: 8px; border-top: 2px solid var(--ink); break-after: avoid; }
h3, h4, h5, h6 { font-size: 16.5px; margin: 18px 0 6px; break-after: avoid; }
p { margin: 0 0 12px; }
a { color: var(--red); }
hr { border: 0; border-top: 1.15px solid var(--ink); margin: 18px 0; }
code { font-family: M; font-size: .84em; background: var(--tone); padding: 1px 4px; }
pre { position: relative; background: var(--ink); color: #eee; padding: 12px; overflow-x: auto; font: 12.5px/1.5 M; margin: 0 0 14px; break-inside: avoid; white-space: pre; }
pre code { background: none; padding: 0; color: inherit; font-size: inherit; }
pre .copy { position: absolute; top: 6px; right: 6px; font: 10px M; color: #aaa; border: 1px solid #555; padding: 2px 6px; background: none; }
blockquote { margin: 0 0 14px; padding: 10px 14px; border-left: 4px solid var(--red); background: #fbf1f1; break-inside: avoid; }
blockquote p:last-child { margin: 0; }
ul, ol { margin: 0 0 12px; padding-left: 22px; }
li { margin: 3px 0; }
li.task { list-style: none; margin-left: -22px; display: flex; gap: 10px; }
li.task .box { width: 20px; height: 20px; border: 2px solid var(--ink); flex: none; margin-top: 3px; display: grid; place-items: center; font-size: 13px; }
li.task.done .box { background: var(--ink); color: var(--paper); }
li.task.done span:last-child { color: var(--muted); text-decoration: line-through; }
table { border-collapse: collapse; width: 100%; margin: 0 0 14px; font-size: 14.5px; break-inside: avoid; }
th, td { border: 1.15px solid var(--ink); padding: 6px 8px; text-align: left; vertical-align: top; }
th { background: var(--ink); color: var(--paper); font-weight: 600; }
.narrow table, .narrow tbody, .narrow tr, .narrow td { display: block; border: 0; }
.narrow thead { display: none; }
.narrow tr { border: 1.15px solid var(--ink); margin-bottom: 8px; padding: 6px 10px; }
.narrow td { padding: 2px 0; }
.narrow td::before { content: attr(data-h); display: block; font: 9.5px M; letter-spacing: .08em; text-transform: uppercase; color: var(--muted); }
.narrow td:first-child { font-weight: 700; font-size: 16px; }
.narrow td:first-child::before { display: none; }
.book { height: 100vh; overflow: hidden; padding: 0; }
/* Two columns a spread in the CSS itself, so the book stands even before (or without) the script; with the height
   fixed, the rest of the note runs on in more columns to the right, which a turn slides into view. */
.book #wrap { margin: 0 26px; }
.book #flow { height: calc(100vh - 56px); margin-top: 16px; column-count: 2; column-gap: 52px; column-fill: auto; transition: transform .35s cubic-bezier(.3,.7,.3,1); }
.book #flow > *:first-child { margin-top: 0; }
.book #folio { position: fixed; left: 26px; right: 26px; bottom: 10px; display: flex; justify-content: space-between; font: 10.5px M; letter-spacing: .08em; color: var(--muted); }
.book #spine { position: fixed; top: 16px; bottom: 40px; left: 50%; width: 1px; background: var(--tone); }
#folio, #spine { display: none; }
.book #folio, .book #spine { display: flex; }
</style></head><body class="${if (book) "book" else ""} ${if (narrow) "narrow" else ""}">
<div id="wrap"><div id="flow">
${Markdown.html(titled(md))}
</div></div><div id="spine"></div><div id="folio"><span id="pl"></span><span id="pr"></span></div>
<script>
document.addEventListener('click', function (e) {
  var copy = e.target.closest('.copy');
  if (copy) { Soma.copy(copy.parentNode.querySelector('code').textContent); copy.textContent = 'Copied'; return; }
  var task = e.target.closest('li.task');
  if (task) { Soma.tick(parseInt(task.dataset.line)); return; }
  if (e.target.closest('a')) return;
  if (!document.body.classList.contains('book')) return;
  var x = e.clientX / innerWidth;
  if (x < 0.22) turn(-1); else if (x > 0.78) turn(1);
});
var spread = 0, spreads = 1;
/* One spread: the two columns and the gap after them. */
function step() { return document.getElementById('flow').clientWidth + 52; }
function layout() {
  if (!document.body.classList.contains('book')) return;
  var flow = document.getElementById('flow');
  if (flow.clientWidth <= 0) return;
  spreads = Math.max(1, Math.ceil(flow.scrollWidth / step() - 0.01));
  turn(0);
}
function turn(d) {
  spread = Math.max(0, Math.min(spreads - 1, spread + d));
  document.getElementById('flow').style.transform = 'translateX(' + (-spread * step()) + 'px)';
  document.getElementById('pl').textContent = (spread * 2 + 1);
  document.getElementById('pr').textContent = (spread * 2 + 2) + ' / ' + (spreads * 2);
}
addEventListener('load', layout);
addEventListener('resize', layout);
document.fonts && document.fonts.ready.then(layout);
layout();
setTimeout(layout, 300);
</script></body></html>"""

    /**
     * A note whose first line isn't a heading (Boss's own: the first line is the title) read with that line as one, as
     * big as the page shows it (the Mac Pro's walk, 2026-10-24: the title read small).
     */
    fun titled(md: String): String {
        val lines = md.split('\n').toMutableList()
        val i = lines.indexOfFirst { it.isNotBlank() }
        if (i < 0) return md
        val first = lines[i].trim()
        if (first.startsWith("#") || first.startsWith("|") || first.startsWith("```") || Regex("^([-*+]|\\d+[.)]) ").containsMatchIn(first)) return md
        lines[i] = "# $first"
        return lines.joinToString("\n")
    }
}
