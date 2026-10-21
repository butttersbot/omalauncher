// The sideways reader as a magazine (Boss, 2026-10-21: "on landscape... almost made it like pages in a book... i wanna
// be able to basically turn pages... more inline images... pretext by chenglou"), then a polish pass after looking at
// what the best readers do ("lets do some research on the gold standard of digital book/magazine readers"):
//
//  - The page follows the finger (Apple Books): drag it over the spine, let go past halfway or with a flick and it
//    turns, otherwise it falls back. A tap at either side turns too. (The first cut lost the swipe to the browser.)
//  - Type set as a book is (Bringhurst): justified and hyphenated (no rivers), every line on one baseline grid so the
//    lines of facing pages line up, photos and quotes sized to whole lines, no first line of a paragraph left alone
//    at a page's foot, outer margins wider than the spine's.
//  - The chrome stays out of the way until the middle is tapped (Apple Books, Kindle): then a scrubber over the
//    pages, time left in the story, and Aa for the type size, which sets the pages again in place.
//  - The page edges show how much is left, a little thicker on the side still to read.
//
// Pretext lays the text out a line at a time, each line as wide as the page leaves it, which is what lets the photos
// sit in the column with the text going round them.
import { prepareWithSegments, layoutNextLineRange, materializeLineRange, layoutWithLines } from './vendor/pretext/layout.js'

const SERIF = '"Noto Serif", Georgia, "Times New Roman", serif'
const SIZES = [
  { size: 14.5, lh: 23 },
  { size: 16, lh: 25 },
  { size: 18, lh: 28 },
]
const MARGIN = { spine: 24, outer: 34, top: 44, bottom: 40 }
const GAP = 14
const WPM = 230

const font = (size, weight = 400, style = 'normal') => `${style} ${weight} ${size}px ${SERIF}`

// Hyphenation (Liang's patterns, as TeX's): soft hyphens Pretext may break at; a line that ends at one shows a hyphen.
let hyphenate = t => t
if (window.createHyphenator && window.hyphenationPatternsEnUs) hyphenate = window.createHyphenator(window.hyphenationPatternsEnUs, { hyphenChar: '­' })
const shown = t => (t.endsWith('­') ? t.slice(0, -1) + '-' : t).replace(/­/g, '')

/** Lays [article] out on pages [w] × [h] in type [body]; returns each page's pieces, positioned. */
function paginate(article, w, h, body) {
  const LH = body.lh
  const pages = []
  let page, y, floats, col, left0

  const grid = v => Math.ceil(v / LH) * LH

  function newPage() {
    page = { items: [], words: 0 }
    pages.push(page)
    // Even pages are left pages: their outer margin is on the left.
    const isLeft = (pages.length - 1) % 2 === 0
    left0 = isLeft ? MARGIN.outer : MARGIN.spine
    col = w - MARGIN.outer - MARGIN.spine
    y = MARGIN.top
    floats = []
  }
  const bottom = () => MARGIN.top + Math.floor((h - MARGIN.top - MARGIN.bottom) / LH) * LH

  // The opener: the left page given to a photo, the headline set large across its foot.
  newPage()
  page.opener = true
  page.items.push(article.hero ? { kind: 'hero', src: article.hero, x: 0, y: 0, w, h } : { kind: 'plain', x: 0, y: 0, w, h })
  const tw = w - 2 * MARGIN.outer
  const title = layoutWithLines(prepareWithSegments(article.title, font(29, 700)), tw, 34)
  const dek = layoutWithLines(prepareWithSegments(article.dek, font(15, 400, 'italic')), tw, 21)
  let ty = h - MARGIN.bottom - 18 - dek.lines.length * 21 - 12 - title.lines.length * 34 - 26
  page.items.push({ kind: 'kicker', text: article.kicker, x: MARGIN.outer, y: ty })
  ty += 26
  for (const l of title.lines) {
    page.items.push({ kind: 'title', text: l.text, x: MARGIN.outer, y: ty, w: tw })
    ty += 34
  }
  ty += 12
  for (const l of dek.lines) {
    page.items.push({ kind: 'dek', text: l.text, x: MARGIN.outer, y: ty, w: tw })
    ty += 21
  }
  page.items.push({ kind: 'byline', text: article.by, x: MARGIN.outer, y: ty + 8 })

  // The body begins on the right page.
  newPage()
  let first = true

  function measure(top) {
    let l = 0
    let r = col
    for (const f of floats) {
      if (top + LH > f.y0 && top < f.y1) {
        if (f.side === 'left') l = Math.max(l, f.x1 + GAP)
        else r = Math.min(r, f.x0 - GAP)
      }
    }
    return [l, r]
  }

  function caption(text, width) {
    return layoutWithLines(prepareWithSegments(text, font(12, 400, 'italic')), width, 16).lines.map(l => l.text)
  }

  for (const b of article.blocks) {
    if (b.img && b.full) {
      const cap = caption(b.cap, col)
      // Photo and caption take whole lines, so the text after it is back on the grid.
      const ih = grid(col / b.ratio) - LH
      const total = ih + LH
      if (y + total > bottom()) newPage()
      page.items.push({ kind: 'photo', src: b.img, x: left0, y, w: col, h: ih - 8, cap })
      y += total
      continue
    }
    if (b.img) {
      const fw = Math.round(col * b.frac)
      const cap = caption(b.cap, fw)
      const ih = grid(fw / b.ratio)
      const total = ih + Math.max(1, Math.ceil((cap.length * 16 + 6) / LH)) * LH
      if (y + total > bottom()) newPage()
      const x0 = b.side === 'left' ? 0 : col - fw
      floats.push({ side: b.side, x0, x1: x0 + fw, y0: y, y1: y + total })
      page.items.push({ kind: 'photo', src: b.img, x: left0 + x0, y: y + 3, w: fw, h: ih - 6, cap })
      continue
    }
    if (b.quote) {
      const q = layoutWithLines(prepareWithSegments(b.quote, font(Math.round(body.size * 1.3), 400, 'italic')), col - 24, Math.round(LH * 1.15))
      const total = grid(q.lines.length * Math.round(LH * 1.15) + 22) + LH
      for (const f of floats) if (f.y1 > y) y = grid(f.y1 - MARGIN.top) + MARGIN.top
      if (y + total > bottom()) newPage()
      page.items.push({ kind: 'quote', lines: q.lines.map(l => l.text), x: left0, y: y + LH / 2, w: col, size: Math.round(body.size * 1.3), lh: Math.round(LH * 1.15) })
      y += total
      continue
    }
    // A paragraph: never its first line alone at the foot of a page.
    if (y + 2 * LH > bottom()) newPage()
    let text = hyphenate(b.p)
    page.words += b.p.split(/\s+/).length
    if (first) {
      const dropSize = LH * 3 - 4
      page.items.push({ kind: 'drop', text: text[0], x: left0, y: y + 2, size: dropSize * 1.05 })
      floats.push({ side: 'left', x0: 0, x1: Math.round(dropSize * 0.72), y0: y, y1: y + 3 * LH - 1 })
      text = text.slice(1)
      first = false
    }
    const prepared = prepareWithSegments(text, font(body.size))
    let cursor = { segmentIndex: 0, graphemeIndex: 0 }
    for (;;) {
      if (y + LH > bottom()) newPage()
      const [l, r] = measure(y)
      if (r - l < 70) {
        y += LH
        continue
      }
      const range = layoutNextLineRange(prepared, r - l, cursor)
      if (range === null) break
      const line = materializeLineRange(prepared, range)
      const last = layoutNextLineRange(prepared, 1e6, range.end) === null
      page.items.push({ kind: 'line', text: shown(line.text), x: left0 + l, y, w: r - l, last })
      cursor = range.end
      y += LH
    }
  }
  page.items.push({ kind: 'end', x: left0, y: Math.min(y + 6, bottom() - 76), w: col, next: article.next })
  return pages
}

function pageHtml(p, i, total, article, body) {
  const isLeft = i % 2 === 0
  const head = p.opener ? '' : `<div class="pg-head mono" style="${isLeft ? `left:${MARGIN.outer}px;right:${MARGIN.spine}px` : `left:${MARGIN.spine}px;right:${MARGIN.outer}px;text-align:right`}">${isLeft ? 'SOMA' : article.kicker}</div>`
  const folio = p.opener ? '' : `<div class="pg-folio mono" style="${isLeft ? `left:${MARGIN.outer}px` : `right:${MARGIN.outer}px`}">${i + 1}</div>`
  const parts = p.items
    .map(it => {
      const at = `left:${it.x}px;top:${it.y}px`
      switch (it.kind) {
        case 'plain':
          return `<div class="pg-plain" style="${at};width:${it.w}px;height:${it.h}px"></div>`
        case 'hero':
          return `<img class="pg-hero" src="${it.src}" style="${at};width:${it.w}px;height:${it.h}px"><div class="pg-shade"></div>`
        case 'kicker':
          return `<div class="pg-kicker" style="${at}">${it.text}</div>`
        case 'title':
          return `<div class="pg-title" style="${at};width:${it.w}px">${it.text}</div>`
        case 'dek':
          return `<div class="pg-dek" style="${at};width:${it.w}px">${it.text}</div>`
        case 'byline':
          return `<div class="pg-by mono" style="${at}">${it.text}</div>`
        case 'drop':
          return `<div class="pg-drop" style="${at};font-size:${it.size}px">${it.text}</div>`
        case 'line':
          return `<div class="pg-line ${it.last ? '' : 'j'}" style="${at};width:${it.w}px;font-size:${body.size}px;line-height:${body.lh}px;height:${body.lh}px">${it.text}</div>`
        case 'photo':
          return `<figure class="pg-photo" style="${at};width:${it.w}px"><img src="${it.src}" style="height:${it.h}px">${it.cap.map(c => `<div>${c}</div>`).join('')}</figure>`
        case 'quote':
          return `<div class="pg-quote" style="${at};width:${it.w}px;font-size:${it.size}px;line-height:${it.lh}px">${it.lines.map(l => `<div>${l}</div>`).join('')}</div>`
        case 'end':
          if (!it.next) return `<div class="pg-end" style="${at};width:${it.w}px"><span class="pg-mark">■</span></div>`
          return `<div class="pg-end" style="${at};width:${it.w}px"><span class="pg-mark">■</span><div class="pg-next" onclick="window.somaNext && window.somaNext()"><span class="mono">Next story</span><b>${it.next.title}</b><img src="${it.next.img}"></div></div>`
      }
      return ''
    })
    .join('')
  // The page edges: thicker on the side still to read.
  const left = isLeft ? i : total - i
  const edge = Math.min(6, Math.ceil(left / 2))
  const edges = `<div class="pg-edges ${isLeft ? 'l' : 'r'}" style="width:${edge * 1.5}px"></div>`
  return `<div class="pg ${p.opener ? 'opener' : ''}">${head}${parts}${folio}${edges}</div>`
}

const CSS = `
.bk { position: relative; flex: 1; display: flex; flex-direction: column; background: #2a2926; }
.bk-stage { position: relative; flex: 1; margin: 12px 16px 16px; perspective: 2400px; touch-action: none; user-select: none; -webkit-user-select: none; }
.bk-left, .bk-right { position: absolute; top: 0; bottom: 0; width: 50%; overflow: hidden; background: #fbf9f3; }
.bk-left { left: 0; border-radius: 3px 0 0 3px; }
.bk-right { right: 0; border-radius: 0 3px 3px 0; }
.bk-left::after, .bk-right::after { content: ""; position: absolute; top: 0; bottom: 0; width: 34px; pointer-events: none; z-index: 4; }
.bk-left::after { right: 0; background: linear-gradient(90deg, transparent, rgba(0,0,0,.07) 60%, rgba(0,0,0,.2)); }
.bk-right::after { left: 0; background: linear-gradient(270deg, transparent, rgba(0,0,0,.07) 60%, rgba(0,0,0,.2)); }
.bk-leaf { position: absolute; top: 0; bottom: 0; width: 50%; transform-style: preserve-3d; z-index: 6; }
.bk-leaf.fwd { left: 50%; transform-origin: left center; }
.bk-leaf.back { left: 0; transform-origin: right center; }
.bk-leaf.go { transition: transform .45s cubic-bezier(.25,.75,.3,1); }
.bk-face { position: absolute; inset: 0; backface-visibility: hidden; overflow: hidden; background: #fbf9f3; }
.bk-face.b { transform: rotateY(180deg); }
.bk-face .shade { position: absolute; inset: 0; pointer-events: none; z-index: 8; }
.bk-chrome { position: absolute; left: 0; right: 0; z-index: 20; transition: opacity .2s, transform .2s; }
.bk-chrome.off { opacity: 0; pointer-events: none; }
.bk-top { top: 0; display: flex; align-items: center; gap: 12px; padding: 10px 18px; background: rgba(251,249,243,.96); border-bottom: 1px solid #d9d6cf; }
.bk-top .t { flex: 1; font: italic 14px ${SERIF}; color: #333; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.bk-top button { font: 15px ${SERIF}; padding: 4px 8px; border: 1px solid #111; background: #fbf9f3; }
.bk-top button.on { background: #111; color: #fbf9f3; }
.bk-bottom { bottom: 0; padding: 10px 22px 12px; background: rgba(251,249,243,.96); border-top: 1px solid #d9d6cf; }
.bk-bottom input { width: 100%; accent-color: #d7262d; }
.bk-bottom .row { display: flex; justify-content: space-between; margin-top: 4px; color: #74737a; }
.pg { position: absolute; inset: 0; font-family: ${SERIF}; color: #1b1a17; }
.pg > * { position: absolute; margin: 0; }
.pg-head { top: 18px; border-bottom: .5px solid #cfcac0; padding-bottom: 5px; color: #8a877f; font-size: 9.5px !important; }
.pg-folio { bottom: 14px; color: #8a877f; }
.pg-hero { object-fit: cover; }
.pg-plain { background: radial-gradient(rgba(255,255,255,.06) 1px, transparent 1.3px) 0 0/6px 6px, linear-gradient(160deg, #2b2a27, #111113); }
.pg-shade { inset: 0; background: linear-gradient(transparent 38%, rgba(0,0,0,.78)); }
.opener .pg-kicker { color: #ffd166; border-top: 1px solid rgba(255,255,255,.5); padding-top: 6px; }
.opener .pg-title, .opener .pg-dek { color: #fff; }
.opener .pg-dek { color: rgba(255,255,255,.88); }
.opener .pg-by { color: rgba(255,255,255,.7); }
.pg-kicker { font: 600 10.5px/1 "M Mono", monospace; letter-spacing: .14em; text-transform: uppercase; color: #d7262d; }
.pg-title { font: 700 29px/34px ${SERIF}; white-space: nowrap; letter-spacing: -.01em; }
.pg-dek { font: italic 15px/21px ${SERIF}; white-space: nowrap; }
.pg-by { font-size: 9.5px !important; }
.pg-drop { font-family: "M Display", sans-serif; line-height: .86; color: #d7262d; }
.pg-line { font-family: ${SERIF}; white-space: nowrap; hyphens: none; }
.pg-line.j { text-align: justify; text-align-last: justify; white-space: normal; overflow: hidden; }
.pg-photo img { width: 100%; object-fit: cover; display: block; margin-bottom: 5px; }
.pg-photo div { font: italic 12px/16px ${SERIF}; color: #6b685f; white-space: nowrap; }
.pg-quote { border-top: 1.5px solid #1b1a17; border-bottom: 1.5px solid #1b1a17; padding: 8px 0 8px 24px; font-family: ${SERIF}; font-style: italic; color: #1b1a17; }
.pg-quote::before { content: "“"; position: absolute; left: -3px; top: 4px; font: 700 44px/1 ${SERIF}; color: #d7262d; }
.pg-quote div { white-space: nowrap; }
.pg-end .pg-mark { color: #d7262d; font-size: 11px; }
.pg-next { margin-top: 14px; display: grid; grid-template-columns: 1fr 64px; gap: 4px 12px; align-items: center; border-top: .5px solid #cfcac0; padding-top: 10px; cursor: pointer; }
.pg-next span { grid-column: 1; color: #8a877f; font-size: 9.5px !important; }
.pg-next b { grid-column: 1; font: 700 15px/1.25 ${SERIF}; }
.pg-next img { grid-column: 2; grid-row: 1 / span 2; width: 64px; height: 64px; object-fit: cover; }
.pg-edges { top: 2px; bottom: 2px; background: repeating-linear-gradient(90deg, #e8e3d7 0 1px, #fbf9f3 1px 1.5px); z-index: 9; }
.pg-edges.l { left: 0; } .pg-edges.r { right: 0; }
`

let style
export function mount(el, article, onBack) {
  if (!style) {
    style = document.createElement('style')
    style.textContent = CSS
    document.head.appendChild(style)
  }
  el.className = 'bk'
  el.innerHTML = `<div class="bk-stage"><div class="bk-left"></div><div class="bk-right"></div></div>
    <div class="bk-chrome bk-top off"><button class="back" onclick="window.somaBack && window.somaBack()">‹</button><span class="t">${article.title}</span><button data-size="0" style="font-size:12px">Aa</button><button data-size="1">Aa</button><button data-size="2" style="font-size:18px">Aa</button></div>
    <div class="bk-chrome bk-bottom off"><input type="range" min="0" value="0"><div class="row mono"><span class="pages"></span><span class="left"></span></div></div>`
  const stage = el.querySelector('.bk-stage')
  const leftEl = el.querySelector('.bk-left')
  const rightEl = el.querySelector('.bk-right')
  const chrome = [...el.querySelectorAll('.bk-chrome')]
  const scrub = el.querySelector('input')
  let sizeAt = 1
  let pages, html, spreads
  let at = 0
  let leaf = null
  let busy = false

  function lay(keepFraction) {
    const pw = Math.floor(stage.clientWidth / 2)
    const ph = stage.clientHeight
    const body = SIZES[sizeAt]
    pages = paginate(article, pw, ph, body)
    html = pages.map((p, i) => pageHtml(p, i, pages.length, article, body))
    spreads = Math.ceil(pages.length / 2)
    at = Math.min(spreads - 1, Math.round(keepFraction * (spreads - 1)))
    scrub.max = String(spreads - 1)
    el.querySelectorAll('.bk-top button').forEach(b => b.classList.toggle('on', Number(b.dataset.size) === sizeAt))
    show()
  }

  function show() {
    leftEl.innerHTML = html[at * 2] || ''
    rightEl.innerHTML = html[at * 2 + 1] || ''
    scrub.value = String(at)
    const words = pages.slice(at * 2).reduce((n, p) => n + p.words, 0)
    el.querySelector('.pages').textContent = `${at * 2 + 1}–${Math.min(at * 2 + 2, pages.length)} of ${pages.length}`
    el.querySelector('.left').textContent = words ? `${Math.max(1, Math.round(words / WPM))} min left in the story` : 'The end'
  }

  // The leaf: the page lifted over the spine, its back the next spread's facing page.
  function lift(dir) {
    const to = at + dir
    if (to < 0 || to >= spreads) return null
    const l = document.createElement('div')
    l.className = 'bk-leaf ' + (dir > 0 ? 'fwd' : 'back')
    if (dir > 0) {
      l.innerHTML = `<div class="bk-face">${html[at * 2 + 1] || ''}<div class="shade"></div></div><div class="bk-face b">${html[to * 2] || ''}<div class="shade"></div></div>`
      rightEl.innerHTML = html[to * 2 + 1] || ''
    } else {
      l.innerHTML = `<div class="bk-face">${html[at * 2] || ''}<div class="shade"></div></div><div class="bk-face b">${html[to * 2 + 1] || ''}<div class="shade"></div></div>`
      leftEl.innerHTML = html[to * 2] || ''
    }
    stage.appendChild(l)
    return { el: l, dir, to }
  }

  /** The leaf at [t] of the way over (0 flat where it started, 1 turned): its angle and the light on it. */
  function bend(t, lf = leaf) {
    const a = t * 180 * (lf.dir > 0 ? -1 : 1)
    lf.el.style.transform = `rotateY(${a}deg)`
    const [front, back] = lf.el.querySelectorAll('.shade')
    const side = lf.dir > 0 ? 90 : 270
    front.style.background = `linear-gradient(${side}deg, rgba(0,0,0,${0.35 * t}), transparent 70%)`
    back.style.background = `linear-gradient(${side + 180}deg, rgba(0,0,0,${0.35 * (1 - t)}), transparent 70%)`
  }

  function settle(commit) {
    const l = leaf
    busy = true
    l.el.classList.add('go')
    requestAnimationFrame(() => requestAnimationFrame(() => bend(commit ? 1 : 0, l)))
    l.el.addEventListener(
      'transitionend',
      () => {
        if (commit) at = l.to
        l.el.remove()
        show()
        busy = false
      },
      { once: true },
    )
  }

  function turn(dir) {
    if (leaf || busy) return
    leaf = lift(dir)
    if (!leaf) return
    bend(0)
    settle(true)
    leaf = null
  }

  // Touch: a drag carries the page with the finger; a flick or past halfway turns it; a tap at a side turns; a tap in
  // the middle shows or hides the chrome.
  let down = null
  stage.addEventListener('pointerdown', e => {
    if (leaf || busy) return
    stage.setPointerCapture(e.pointerId)
    down = { x: e.clientX, y: e.clientY, t: performance.now(), dir: 0, last: e.clientX, lastT: performance.now(), v: 0 }
  })
  stage.addEventListener('pointermove', e => {
    if (!down) return
    const dx = e.clientX - down.x
    const r = stage.getBoundingClientRect()
    if (!down.dir && Math.abs(dx) > 8) {
      down.dir = dx < 0 ? 1 : -1
      leaf = lift(down.dir)
      if (!leaf) {
        down.dir = 0
        return
      }
    }
    if (leaf) {
      const now = performance.now()
      down.v = (e.clientX - down.last) / Math.max(1, now - down.lastT)
      down.last = e.clientX
      down.lastT = now
      bend(Math.min(1, Math.max(0, (-dx * down.dir) / (r.width / 2))))
    }
  })
  const up = e => {
    if (!down) return
    const d = down
    down = null
    if (leaf) {
      const r = stage.getBoundingClientRect()
      const t = (-(e.clientX - d.x) * d.dir) / (r.width / 2)
      const flick = -d.v * d.dir > 0.4
      settle(t > 0.5 || flick)
      leaf = null
      return
    }
    if (performance.now() - d.t > 400) return
    // The stage holds the pointer, so a tap on the next story's card is found where it landed.
    if (document.elementFromPoint(e.clientX, e.clientY)?.closest('.pg-next')) return window.somaNext && window.somaNext()
    const r = stage.getBoundingClientRect()
    const fx = (e.clientX - r.left) / r.width
    if (fx > 0.8) turn(1)
    else if (fx < 0.2) turn(-1)
    else chrome.forEach(c => c.classList.toggle('off'))
  }
  stage.addEventListener('pointerup', up)
  stage.addEventListener('pointercancel', up)

  scrub.addEventListener('input', () => {
    at = Number(scrub.value)
    show()
  })
  el.querySelectorAll('.bk-top button').forEach(b =>
    b.addEventListener('click', () => {
      const keep = spreads > 1 ? at / (spreads - 1) : 0
      sizeAt = Number(b.dataset.size)
      lay(keep)
    }),
  )
  lay(0)
}
