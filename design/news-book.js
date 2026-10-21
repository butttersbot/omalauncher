// The sideways reader as a magazine (Boss, 2026-10-21: "on landscape... almost made it like pages in a book. lets
// follow that thread more. i wanna be able to basically turn pages... more inline images with the text. there is a
// github repo called pretext by chenglou that does this very well").
//
// Pretext lays the article out a line at a time, each line as wide as the page leaves it: photos sit in the column
// and the lines beside them go narrower, as a magazine sets type around a picture; a pull quote takes the whole
// measure; a page fills exactly before the next begins. Two pages make a spread; a swipe or a tap at the edge turns
// the page, the leaf folding over the spine.
import { prepareWithSegments, layoutNextLineRange, materializeLineRange, layoutWithLines } from './vendor/pretext/layout.js'

const SERIF = '"Noto Serif", Georgia, "Times New Roman", serif'
const BODY = { size: 16, lh: 25 }
const QUOTE = { size: 21, lh: 29 }
const PAD = { side: 26, top: 40, bottom: 36 }
const GAP = 14

const font = (size, weight = 400, style = 'normal') => `${style} ${weight} ${size}px ${SERIF}`

/** Lays [article] out on pages [w] × [h]; returns each page's pieces, positioned. */
function paginate(article, w, h) {
  const col = w - 2 * PAD.side
  const bottom = h - PAD.bottom
  const pages = []
  let page, y, floats

  function newPage() {
    page = { items: [] }
    pages.push(page)
    y = PAD.top
    floats = []
  }

  // The opener: the left page, a photo across its top, then the headline set large.
  newPage()
  const heroH = Math.round(h * 0.52)
  page.items.push({ kind: 'hero', src: article.hero, x: 0, y: 0, w, h: heroH })
  page.opener = true
  y = heroH + 18
  page.items.push({ kind: 'kicker', text: article.kicker, x: PAD.side, y })
  y += 20
  const title = layoutWithLines(prepareWithSegments(article.title, font(27, 700)), col, 32)
  for (const l of title.lines) {
    page.items.push({ kind: 'title', text: l.text, x: PAD.side, y, w: col })
    y += 32
  }
  y += 6
  const dek = layoutWithLines(prepareWithSegments(article.dek, font(15.5, 400, 'italic')), col, 22)
  for (const l of dek.lines) {
    page.items.push({ kind: 'dek', text: l.text, x: PAD.side, y, w: col })
    y += 22
  }
  page.items.push({ kind: 'byline', text: article.by, x: PAD.side, y: y + 8 })

  // The body begins on the right page.
  newPage()
  let first = true

  /** How much of the measure a line at [y] has, beside the photos on this page. */
  function measure(top) {
    let left = 0
    let right = col
    for (const f of floats) {
      if (top + BODY.lh > f.y0 && top < f.y1) {
        if (f.side === 'left') left = Math.max(left, f.x1 + GAP)
        else right = Math.min(right, f.x0 - GAP)
      }
    }
    return [left, right]
  }

  function placeFloat(b) {
    const fw = Math.round(col * b.frac)
    const ih = Math.round(fw / b.ratio)
    const capLines = layoutWithLines(prepareWithSegments(b.cap, font(12, 400, 'italic')), fw, 16).lines
    const fh = ih + 6 + capLines.length * 16
    if (y + fh > bottom) newPage()
    const x0 = b.side === 'left' ? 0 : col - fw
    floats.push({ side: b.side, x0, x1: x0 + fw, y0: y, y1: y + fh + 4 })
    page.items.push({ kind: 'photo', src: b.img, x: PAD.side + x0, y, w: fw, h: ih, cap: capLines.map(l => l.text) })
  }

  for (const b of article.blocks) {
    if (b.img && b.full) {
      const ih = Math.round(col / b.ratio)
      const capLines = layoutWithLines(prepareWithSegments(b.cap, font(12, 400, 'italic')), col, 16).lines
      if (y + ih + 6 + capLines.length * 16 > bottom) newPage()
      page.items.push({ kind: 'photo', src: b.img, x: PAD.side, y, w: col, h: ih, cap: capLines.map(l => l.text) })
      y += ih + 10 + capLines.length * 16 + 10
      continue
    }
    if (b.img) {
      placeFloat(b)
      continue
    }
    if (b.quote) {
      const q = layoutWithLines(prepareWithSegments(b.quote, font(QUOTE.size, 400, 'italic')), col - 22, QUOTE.lh)
      const qh = q.lines.length * QUOTE.lh + 18
      if (y + qh > bottom) newPage()
      // Clear of any photo still beside the column.
      for (const f of floats) if (f.y1 > y) y = Math.max(y, f.y1)
      if (y + qh > bottom) newPage()
      page.items.push({ kind: 'quote', lines: q.lines.map(l => l.text), x: PAD.side, y: y + 6, w: col })
      y += qh + 12
      continue
    }
    // A paragraph, a line at a time.
    let text = b.p
    if (first) {
      // The drop cap: three lines deep, the letter set apart and the text flowing beside it.
      page.items.push({ kind: 'drop', text: text[0], x: PAD.side, y: y - 4 })
      floats.push({ side: 'left', x0: 0, x1: 46, y0: y, y1: y + 3 * BODY.lh - 2 })
      text = text.slice(1)
      first = false
    }
    const prepared = prepareWithSegments(text, font(BODY.size))
    let cursor = { segmentIndex: 0, graphemeIndex: 0 }
    for (;;) {
      if (y + BODY.lh > bottom) newPage()
      const [l, r] = measure(y)
      if (r - l < 70) {
        y += BODY.lh
        continue
      }
      const range = layoutNextLineRange(prepared, r - l, cursor)
      if (range === null) break
      const line = materializeLineRange(prepared, range)
      const last = layoutNextLineRange(prepared, 1e6, range.end) === null
      page.items.push({ kind: 'line', text: line.text, x: PAD.side + l, y, w: r - l, last })
      cursor = range.end
      y += BODY.lh
    }
    y += 8
  }
  // The end mark, and what's next.
  page.items.push({ kind: 'end', x: PAD.side, y: Math.min(y + 4, bottom - 70), w: col, next: article.next })
  return pages
}

function pageHtml(p, i, n, article) {
  const head = p.opener ? '' : `<div class="pg-head mono">${i % 2 ? article.kicker : 'SOMA'}</div>`
  const folio = `<div class="pg-folio mono ${i % 2 ? 'r' : 'l'}">${i + 1}</div>`
  const parts = p.items
    .map(it => {
      const at = `left:${it.x}px;top:${it.y}px`
      switch (it.kind) {
        case 'hero':
          return `<img class="pg-hero" src="${it.src}" style="${at};width:${it.w}px;height:${it.h}px">`
        case 'kicker':
          return `<div class="pg-kicker" style="${at}">${it.text}</div>`
        case 'title':
          return `<div class="pg-title" style="${at};width:${it.w}px">${it.text}</div>`
        case 'dek':
          return `<div class="pg-dek" style="${at};width:${it.w}px">${it.text}</div>`
        case 'byline':
          return `<div class="pg-by mono" style="${at}">${it.text}</div>`
        case 'drop':
          return `<div class="pg-drop" style="${at}">${it.text}</div>`
        case 'line':
          return `<div class="pg-line ${it.last ? '' : 'j'}" style="${at};width:${it.w}px">${it.text}</div>`
        case 'photo':
          return `<figure class="pg-photo" style="${at};width:${it.w}px"><img src="${it.src}" style="height:${it.h}px">${it.cap.map(c => `<div>${c}</div>`).join('')}</figure>`
        case 'quote':
          return `<div class="pg-quote" style="${at};width:${it.w}px">${it.lines.map(l => `<div>${l}</div>`).join('')}</div>`
        case 'end':
          return `<div class="pg-end" style="${at};width:${it.w}px"><span class="pg-mark">■</span><div class="pg-next"><span class="mono">Next</span><b>${it.next.title}</b><img src="${it.next.img}"></div></div>`
      }
      return ''
    })
    .join('')
  return `<div class="pg">${head}${parts}${folio}</div>`
}

const CSS = `
.bk { position: relative; flex: 1; display: flex; flex-direction: column; background: #e9e6df; }
.bk-stage { position: relative; flex: 1; margin: 10px 14px 0; perspective: 2200px; }
.bk-left, .bk-right { position: absolute; top: 0; bottom: 0; width: 50%; overflow: hidden; background: #fbfaf6; }
.bk-left { left: 0; box-shadow: inset -18px 0 22px -18px rgba(0,0,0,.28); }
.bk-right { right: 0; box-shadow: inset 18px 0 22px -18px rgba(0,0,0,.28); }
.bk-leaf { position: absolute; top: 0; bottom: 0; width: 50%; transform-style: preserve-3d; transition: transform .62s cubic-bezier(.3,.62,.25,1); z-index: 5; }
.bk-leaf.fwd { left: 50%; transform-origin: left center; }
.bk-leaf.back { left: 0; transform-origin: right center; }
.bk-face { position: absolute; inset: 0; backface-visibility: hidden; overflow: hidden; background: #fbfaf6; }
.bk-face.b { transform: rotateY(180deg); }
.bk-leaf .bk-face::after { content: ""; position: absolute; inset: 0; background: linear-gradient(90deg, rgba(0,0,0,.18), transparent 30%); pointer-events: none; }
.bk-foot { display: flex; justify-content: space-between; align-items: center; padding: 8px 22px 12px; }
.bk-dots { display: flex; gap: 5px; }
.bk-dots i { width: 6px; height: 6px; border-radius: 50%; background: #c9c5bc; }
.bk-dots i.on { background: #111113; }
.pg { position: absolute; inset: 0; font-family: ${SERIF}; color: #18181b; }
.pg > * { position: absolute; margin: 0; }
.pg-head { top: 16px; left: ${PAD.side}px; right: ${PAD.side}px; border-bottom: 1px solid #d9d6cf; padding-bottom: 4px; color: #74737a; }
.pg-folio { bottom: 12px; color: #74737a; }
.pg-folio.l { left: ${PAD.side}px; } .pg-folio.r { right: ${PAD.side}px; }
.pg-hero { object-fit: cover; }
.pg-kicker { font: 600 10.5px "M Mono", monospace; letter-spacing: .12em; text-transform: uppercase; color: #d7262d; }
.pg-title { font: 700 27px/32px ${SERIF}; white-space: nowrap; letter-spacing: -.01em; }
.pg-dek { font: italic 15.5px/22px ${SERIF}; color: #444; white-space: nowrap; }
.pg-by { color: #74737a; }
.pg-drop { font: 400 74px/1 "M Display", sans-serif; color: #d7262d; }
.pg-line { font: 16px/25px ${SERIF}; white-space: nowrap; }
.pg-line.j { text-align: justify; text-align-last: justify; white-space: normal; height: 25px; overflow: hidden; }
.pg-photo img { width: 100%; object-fit: cover; display: block; margin-bottom: 6px; }
.pg-photo div { font: italic 12px/16px ${SERIF}; color: #555; white-space: nowrap; }
.pg-quote { border-top: 2px solid #111113; border-bottom: 2px solid #111113; padding: 8px 0 8px 22px; font: italic ${QUOTE.size}px/${QUOTE.lh}px ${SERIF}; color: #111113; }
.pg-quote::before { content: "“"; position: absolute; left: -2px; top: 2px; font: 700 40px/1 ${SERIF}; color: #d7262d; }
.pg-quote div { white-space: nowrap; }
.pg-end .pg-mark { color: #d7262d; font-size: 12px; }
.pg-next { margin-top: 12px; display: grid; grid-template-columns: 1fr 64px; gap: 10px; align-items: center; border-top: 1px solid #d9d6cf; padding-top: 10px; cursor: pointer; }
.pg-next span { grid-column: 1; color: #74737a; font-size: 10px; }
.pg-next b { grid-column: 1; font: 700 15px/1.2 ${SERIF}; }
.pg-next img { grid-column: 2; grid-row: 1 / span 2; width: 64px; height: 64px; object-fit: cover; }
`

let style
export function mount(el, article) {
  if (!style) {
    style = document.createElement('style')
    style.textContent = CSS
    document.head.appendChild(style)
  }
  el.className = 'bk'
  el.innerHTML = `<div class="bk-stage"><div class="bk-left"></div><div class="bk-right"></div></div><div class="bk-foot"><span class="mono" style="color:#74737a">Swipe or tap a side to turn</span><span class="bk-dots"></span><span class="mono bk-count" style="color:#74737a"></span></div>`
  const stage = el.querySelector('.bk-stage')
  const leftEl = el.querySelector('.bk-left')
  const rightEl = el.querySelector('.bk-right')
  const pw = Math.floor(stage.clientWidth / 2)
  const ph = stage.clientHeight
  const pages = paginate(article, pw, ph)
  const html = pages.map((p, i) => pageHtml(p, i, pages.length, article))
  const spreads = Math.ceil(pages.length / 2)
  let at = 0
  let busy = false

  function show() {
    leftEl.innerHTML = html[at * 2] || ''
    rightEl.innerHTML = html[at * 2 + 1] || ''
    el.querySelector('.bk-dots').innerHTML = Array.from({ length: spreads }, (_, i) => `<i class="${i === at ? 'on' : ''}"></i>`).join('')
    el.querySelector('.bk-count').textContent = `${at * 2 + 1}–${Math.min(at * 2 + 2, pages.length)} of ${pages.length}`
  }

  // A turn: the leaf folds over the spine, its back the next spread's left page.
  function turn(dir) {
    const to = at + dir
    if (busy || to < 0 || to >= spreads) return
    busy = true
    const leaf = document.createElement('div')
    leaf.className = 'bk-leaf ' + (dir > 0 ? 'fwd' : 'back')
    if (dir > 0) {
      leaf.innerHTML = `<div class="bk-face">${html[at * 2 + 1] || ''}</div><div class="bk-face b">${html[to * 2] || ''}</div>`
      rightEl.innerHTML = html[to * 2 + 1] || ''
    } else {
      leaf.innerHTML = `<div class="bk-face">${html[at * 2] || ''}</div><div class="bk-face b">${html[to * 2 + 1] || ''}</div>`
      leftEl.innerHTML = html[to * 2] || ''
    }
    stage.appendChild(leaf)
    requestAnimationFrame(() => requestAnimationFrame(() => (leaf.style.transform = `rotateY(${dir > 0 ? -180 : 180}deg)`)))
    leaf.addEventListener(
      'transitionend',
      () => {
        at = to
        leaf.remove()
        show()
        busy = false
      },
      { once: true },
    )
  }

  let x0 = null
  stage.addEventListener('pointerdown', e => (x0 = e.clientX))
  stage.addEventListener('pointerup', e => {
    if (x0 === null) return
    const dx = e.clientX - x0
    x0 = null
    if (Math.abs(dx) > 30) return turn(dx < 0 ? 1 : -1)
    const r = stage.getBoundingClientRect()
    const fx = (e.clientX - r.left) / r.width
    if (fx > 0.78) turn(1)
    else if (fx < 0.22) turn(-1)
  })
  show()
}
