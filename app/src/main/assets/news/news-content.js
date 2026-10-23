// Shared by the scrolling reader and the book, including articles cached by older versions.
window.NewsContent = (() => {
  const text = n => (n.textContent || '').replace(/\s+/g, ' ').trim();
  const imageKey = u => {
    try { const a = new URL(u); return a.origin + a.pathname.replace(/[-_]\d+x\d+(?=\.\w+$)/, ''); }
    catch { return ''; }
  };
  // A headline as read: soft hyphens, odd spaces, quotes, punctuation and case don't count.
  const plain = s => (s || '').replace(/[\u00AD\u200B]/g, '').replace(/[^\p{L}\p{N}]+/gu, ' ').trim().toLowerCase();
  /**
   * True when [t] only says the story's headline again, with or without the publisher's name after it: Google News
   * feeds give "<a>Headline</a>&nbsp;&nbsp;<font>Reuters</font>" as the whole story (the 135 walk: Reuters, The Hill).
   */
  function isHeadline(t, story = {}) {
    const title = plain(story.title), said = plain(t);
    if (!title || !said.startsWith(title)) return false;
    const rest = said.slice(title.length).trim();
    return !rest || rest === plain(story.source);
  }
  // Video players' promos left in the words (CNBC, the 135 walk: "watch now", "STREAM NOW", a clip's length).
  const VIDEO = /^(?:watch now|stream now|watch live|video(?:\s*\d{1,2}:\d{2}){0,2})$/i;
  function article(html, story = {}) {
    const doc = new DOMParser().parseFromString(html || '', 'text/html');
    // Prefer publisher-declared body boundaries to a whole Readability wrapper.
    const body = doc.querySelector('[itemprop="articleBody"], #article-body, #single-article-content') || doc.querySelector('article') || doc.body;
    const root = document.createElement('div'); root.innerHTML = body.innerHTML;
    root.querySelectorAll('script,style,iframe,form,noscript,svg,video,audio,object,embed,button,input,select,textarea,link,meta,nav,header,footer,aside,instaread-player,[hidden],[aria-hidden="true"],[data-nosnippet],[role="navigation"],[role="complementary"],.author-bio,.author-box,.article-author,.related-posts,.related-articles,.social-share,.newsletter,.advertisement').forEach(n => n.remove());
    root.querySelectorAll('[class*="InlineVideo"],[class*="VideoPlayer"],[class*="video-player"],[class*="videoPlayer"],[class*="PlayButton"],[class*="ProPromo"],[class*="ExclusiveContent"],[data-module*="Video"],[data-test*="Video"],[data-testid*="video"]').forEach(n => n.remove());
    // A promo the selectors missed: the box round its "watch now" goes, the clip's picture with it.
    const promos = [];
    const find = n => { for (const c of n.children) { if (VIDEO.test(text(c))) promos.push(c); else find(c); } };
    find(root);
    promos.forEach(n => {
      let box = n;
      while (box.parentElement && box.parentElement !== root && text(box.parentElement).length < 240 && !box.parentElement.querySelector('p')) box = box.parentElement;
      if (root.contains(box)) box.remove();
    });
    root.querySelectorAll('a').forEach(n => {
      const href = n.getAttribute('href') || '';
      if (/^skip to (?:main )?content$/i.test(text(n))) (n.closest('p') || n).remove();
      else if (/\/author\//i.test(href)) {
        // Old cached extracts retain author links but may have lost their class names.
        const section = n.closest('footer,aside,[data-nosnippet],.author-bio,.author-box');
        if (section) section.remove();
        else if (n.querySelector('img')) {
          let box = n.parentElement;
          while (box && box !== root && text(box).length < 1200) {
            if (box.firstElementChild?.contains(n) && /\bis (?:an?|the)\b.*\b(?:reporter|editor|writer|journalist)\b/i.test(text(box))) { box.remove(); break; }
            box = box.parentElement;
          }
        }
      }
      else if (/^\d+\s+comments$/i.test(text(n)) && /#comments$/.test(href)) (n.closest('p') || n).remove();
    });
    root.querySelectorAll('img').forEach(n => {
      let src = n.getAttribute('data-src') || n.getAttribute('data-lazy-src') || n.getAttribute('data-original') || n.getAttribute('src') || '';
      if (!src.startsWith('https://')) {
        const set = (n.getAttribute('srcset') || n.getAttribute('data-srcset') || '').split(',').map(s => s.trim().split(/\s+/)).filter(s => s[0].startsWith('https://'));
        src = set.sort((a, b) => (parseInt(b[1]) || 0) - (parseInt(a[1]) || 0))[0]?.[0] || '';
      }
      const w = Number(n.getAttribute('width')), h = Number(n.getAttribute('height'));
      const duplicate = story.image && imageKey(src) === imageKey(story.image);
      if (!src.startsWith('https://') || (w > 0 && w <= 2) || (h > 0 && h <= 2) || duplicate) {
        const figure = n.closest('figure'); (figure || n).remove(); return;
      }
      n.setAttribute('src', src);
    });
    // The headline is shown above the story: not again in it, as a heading or as the feed's only line.
    root.querySelectorAll('h1,h2,h3,p,li,div').forEach(n => { if (root.contains(n) && !n.querySelector('img') && isHeadline(text(n), story)) n.remove(); });
    if (isHeadline(text(root), story) && !root.querySelector('img')) root.replaceChildren();
    tidy(root);
    // A BR is a boundary even after the layout engine turns HTML into text.
    root.querySelectorAll('br').forEach(n => n.replaceWith('\n'));
    root.querySelectorAll('*').forEach(n => {
      for (const a of [...n.attributes]) {
        if (!((n.tagName === 'IMG' && ['src', 'alt'].includes(a.name)) || (n.tagName === 'A' && a.name === 'href'))) n.removeAttribute(a.name);
      }
      if (n.tagName === 'A' && !/^https?:\/\//.test(n.getAttribute('href') || '')) n.removeAttribute('href');
    });
    return root;
  }
  // What a site puts round its story rather than in it (Boss, 2026-10-22: "a bunch of bullet points that were empty",
  // "other links that you should try ... just remove all of that ... a very clean article reader").
  const ASIDE = /^(?:related(?: (?:articles?|stories|posts|coverage|reading))?|read (?:more|next|also)|more (?:from|on|stories|news|coverage|like this)\b.*|recommended\b.*|you (?:may|might) (?:also )?like|see also|also read|trending(?: now)?|popular(?: now| stories)?|most (?:read|popular)|top stories|latest(?: news| stories)?|up next|what to read next|keep reading|don'?t miss|editor'?s picks?|sponsored(?: content)?|advertisement|share this(?: article| story)?|follow us\b.*|sign up\b.*|subscribe\b.*|newsletter\b.*|comments?)[:.!]?$/i;
  // Screen readers' labels a site leaves round a list it filled by script ("list of 4 items", "end of list").
  const READER_LABEL = /^(?:list of \d+ items?|end of list|\d+ items?)$/i;
  const LEAD = /^(?:read more|related|see also|also read|more|recommended|watch|listen|read next|don'?t miss)\s*[:|–—-]/i;
  /** How much of [n]'s words are inside links: 1 for a list of headlines, near 0 for prose. */
  const linkShare = n => { const all = text(n).length; if (!all) return 0; return [...n.querySelectorAll('a')].reduce((t, a) => t + text(a).length, 0) / all; };
  const empty = n => !text(n) && !n.querySelector('img');
  function tidy(root) {
    // The article isn't in the page yet, so isConnected is always false: "still there" means still inside it.
    const there = n => root.contains(n);
    // Empty bullets and wrappers, from the deepest up.
    [...root.querySelectorAll('li, p, ul, ol, div, span, section, figure, blockquote, h2, h3, h4, h5, h6')].reverse().forEach(n => { if (there(n) && empty(n)) n.remove(); });
    // A heading that only names the site's extras goes, with the list or links under it.
    root.querySelectorAll('h2, h3, h4, h5, h6, p > strong:only-child, p > b:only-child').forEach(h => {
      const head = h.matches('strong, b') ? h.parentElement : h;
      if (!there(head) || !ASIDE.test(text(head))) return;
      let next = head.nextElementSibling;
      while (next && (next.matches('ul, ol') || linkShare(next) > 0.6)) { const after = next.nextElementSibling; next.remove(); next = after; }
      head.remove();
    });
    // Lists of headlines (mostly links) and lines that are only a link or a "Read more:" pointer.
    root.querySelectorAll('ul, ol').forEach(l => { const items = l.querySelectorAll('li'); if (there(l) && items.length >= 2 && linkShare(l) > 0.7) l.remove(); });
    root.querySelectorAll('p, li, div').forEach(n => {
      if (!there(n) || n.querySelector('img')) return;
      const t = text(n);
      if (t && (READER_LABEL.test(t) || LEAD.test(t) || (linkShare(n) > 0.9 && t.length < 160 && n.querySelector('a')) || ASIDE.test(t))) n.remove();
    });
    // Screen readers' list labels wherever they sit (a span, a bare text node, a div): matched on the text itself.
    const labels = [];
    const walk = n => { for (const c of [...n.childNodes]) { if (c.nodeType === 3) { if (READER_LABEL.test(c.textContent.trim())) labels.push(c); } else walk(c); } };
    walk(root);
    labels.forEach(t => {
      let n = t.parentNode;
      t.remove();
      while (n && n !== root && empty(n)) { const up = n.parentNode; n.remove(); n = up; }
    });
    // Whatever trails the story once its prose ends: empty wrappers, rows of links, the site's own extras. A short
    // closing line of the story itself ("Sincerely, / The team") stays, and the story is never emptied.
    let last = root.lastElementChild;
    while (last && last !== root.firstElementChild && !last.querySelector('img') && (empty(last) || (linkShare(last) > 0.5 && last.querySelector('a')) || ASIDE.test(text(last)))) {
      const before = last.previousElementSibling; last.remove(); last = before;
    }
    [...root.querySelectorAll('li, ul, ol')].reverse().forEach(n => { if (there(n) && empty(n)) n.remove(); });
  }
  function blocks(root) {
    const out = [];
    const addText = (kind, value) => {
      for (const part of value.split(/\n+/)) {
        const t = part.replace(/\s+/g, ' ').trim();
        if (t) out.push({ [kind]: t });
      }
    };
    function walk(n) {
      if (n.nodeType === Node.TEXT_NODE) { addText('p', n.textContent); return; }
      if (n.nodeType !== Node.ELEMENT_NODE) return;
      if (n.tagName === 'IMG') { out.push({ img: n.getAttribute('src'), cap: '' }); return; }
      if (n.tagName === 'FIGCAPTION') return;
      if (n.tagName === 'BLOCKQUOTE') {
        const paragraphs = [...n.querySelectorAll('p')];
        addText('quote', paragraphs.length ? paragraphs.map(p => p.textContent).join('\n') : n.textContent); return;
      }
      if (/^H[1-6]$/.test(n.tagName)) { addText('heading', n.textContent); return; }
      if (['P', 'LI', 'PRE'].includes(n.tagName)) {
        // Don't count the parent's entire text and then count its children again.
        let run = '';
        const flush = () => { if (run.trim()) addText('p', (n.tagName === 'LI' ? '• ' : '') + run); run = ''; };
        for (const child of n.childNodes) {
          if ((child.nodeType === Node.ELEMENT_NODE && child.querySelector('img')) || child.nodeName === 'IMG') {
            flush(); walk(child);
          } else run += child.textContent || '';
        }
        flush(); return;
      }
      for (const child of n.childNodes) walk(child);
    }
    for (const n of root.childNodes) walk(n);
    return out;
  }
  return { article, blocks, isHeadline };
})();
