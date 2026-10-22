// No dependencies or installs. Run on the Mac with its existing Chrome and Node.
import { createServer } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { resolve, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { tmpdir } from 'node:os';
import { mkdtemp, rm } from 'node:fs/promises';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const chrome = [process.env.CHROME_BIN, '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', '/usr/bin/chromium', '/usr/bin/google-chrome'].find(p => p && existsSync(p));
if (!chrome) { console.error('News UI checks need an existing Chrome (set CHROME_BIN). Nothing was installed.'); process.exit(2); }
const out = resolve(process.env.NEWS_UI_EVIDENCE || `${root}/app/build/news-ui`);
await mkdir(out, { recursive: true });
let finish;
const result = new Promise(r => { finish = r; });
const paragraph = 'Samsung announced a reader update that preserves every word at the end of each line. Photos belong beside the text without covering it, and the reader should remember the sentence when its type size changes. ';
const stories = Array.from({ length: 18 }, (_, i) => ({ id: String(i), title: `Reader regression story ${i + 1}`, source: 'Test source', link: `https://example.test/${i}`, published: Date.now() / 1000, html: `<p>${paragraph.repeat(3)}</p>` }));
const full = `<article><header><p>Follow</p></header><div id="article-body"><p>${paragraph.repeat(4)}</p><h2>A meaningful heading</h2><blockquote><p>${paragraph.repeat(5)}</p></blockquote><p>Sincerely,<br>The editorial team</p></div><aside>Author biography</aside></article>`;
const longWord = 'Unbreakable'.repeat(24);
const longUrl = 'https://example.test/' + 'long-path-component'.repeat(20);
const awkwardBody = `<p>${longWord} ${longUrl}</p><img src="https://example.test/wide.jpg" width="1200" height="600"><iframe width="1200" src="https://example.test/embed"></iframe><table><tr>${Array.from({length:20},()=>`<td>${longWord}</td>`).join('')}</tr></table><pre>${longUrl}</pre><video width="1200"></video><p>${paragraph}</p>`;
const awkwardStories = stories.map((s, i) => ({ ...s, title: i % 2 ? longUrl : longWord, html: awkwardBody, ...(i % 3 ? {image:'/photo.svg'} : {}) }));
const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    const path = url.pathname;
    if (path === '/results' && req.method === 'POST') {
      let body = ''; for await (const chunk of req) body += chunk;
      const parsed = JSON.parse(body); await writeFile(`${out}/results.json`, JSON.stringify(parsed, null, 2));
      res.end('ok'); finish(parsed); return;
    }
    if (path === '/reader') {
      const html = await readFile(`${root}/app/src/main/assets/news/index.html`, 'utf8');
      const awkward = url.searchParams.has('awkward');
      const native = `<base href="/assets/news/"><script>window.SomaNews={stream:()=>${JSON.stringify(JSON.stringify({ updated: Date.now() / 1000, stories: awkward ? awkwardStories : stories }))},full:()=>${JSON.stringify(awkward ? awkwardBody : '')},page:link=>setTimeout(()=>window.somaPage(link,${JSON.stringify(full)}),250),keep(){},refresh(){},original(){},share(){},toast(){}};</script>`;
      res.setHeader('Content-Type', 'text/html'); res.end(html.replace('<head>', '<head>' + native)); return;
    }
    if (path === '/photo.svg') {
      res.setHeader('Content-Type', 'image/svg+xml'); res.end('<svg xmlns="http://www.w3.org/2000/svg" width="400" height="500"><rect width="400" height="500" fill="#d8b898"/><circle cx="200" cy="170" r="70" fill="#667d65"/><path d="M60 500V330Q200 230 340 330V500" fill="#667d65"/></svg>'); return;
    }
    const file = path === '/' ? `${root}/tools/news-ui/test.html` : resolve(root, path.startsWith('/assets/') ? `app/src/main${path}` : `tools/news-ui/${path.slice(1)}`);
    if (!file.startsWith(root + '/')) { res.writeHead(403).end(); return; }
    res.setHeader('Content-Type', ({ '.js': 'text/javascript', '.html': 'text/html', '.ttf': 'font/ttf' })[extname(file)] || 'application/octet-stream');
    res.end(await readFile(file));
  } catch (e) { res.writeHead(404).end(String(e)); }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const profile = await mkdtemp(`${tmpdir()}/soma-news-ui-`);
const proc = spawn(chrome, ['--headless', '--disable-gpu', '--no-first-run', '--no-default-browser-check', `--user-data-dir=${profile}`, '--window-size=1100,950', '--hide-scrollbars', '--virtual-time-budget=15000', `--screenshot=${out}/book.png`, `http://127.0.0.1:${server.address().port}/`], { stdio: ['ignore', 'ignore', 'pipe'] });
let errors = ''; proc.stderr.on('data', d => { errors += d; });
const timer = setTimeout(() => finish({ failures: ['Browser timed out'], passed: [] }), 45000);
const checked = await result;
clearTimeout(timer);
console.log(JSON.stringify(checked, null, 2));
// Let Chrome finish its screenshot before ending the server.
await Promise.race([new Promise(r => proc.once('exit', r)), new Promise(r => setTimeout(r, 5000))]);
proc.kill(); server.close(); await rm(profile, { recursive: true, force: true });
if (checked.failures.length) { console.error(errors.slice(-2000)); process.exitCode = 1; }
