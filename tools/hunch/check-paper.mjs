import {Window} from 'happy-dom';
import fs from 'node:fs';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
const base=new URL('../../hunch/src/main/assets/paper/',import.meta.url).pathname;
const w=new Window({url:'https://appassets.androidplatform.net/hunch/paper/index.html'});
w.document.write(fs.readFileSync(base+'index.html','utf8').replace(/<script[^>]*><\/script>/g,''));
w.fetch=async url=>{const s=fs.readFileSync(base+url,'utf8');return {text:async()=>s,json:async()=>JSON.parse(s)}};
w.SomaHunch={post:()=>{}};w.console=console;
for(const f of ['runtime.js','live.js','app.js'])w.eval(fs.readFileSync(base+f,'utf8')+(f==='live.js'?'\nwindow.showLive=showLive;window.showAccount=showAccount;':''));
let errors=[];w.paperError=e=>{errors.push(e);console.error(e)};
for(const {name}of JSON.parse(fs.readFileSync(base+'pages.json','utf8'))){const manifest=JSON.parse(fs.readFileSync(base+'pages.json','utf8')).find(p=>p.name===name);assert.equal(createHash('sha256').update(fs.readFileSync(base+'mockup/'+name+'.dc.html')).digest('hex'),manifest.sha256);await w.Paper.show(name);if(!w.document.getElementById('scene'))throw Error(name);console.log(name,w.document.getElementById('scene').textContent.replace(/\s+/g,' ').slice(0,70))}
w.Hunch.rows=[{id:'123',title:'Test real market',category:'Sports',venue:'Outcome',date:'Read venue terms',sides:['Yes','No'],terms:{rule:'Official settlement'}}];
for(const width of [390,880]){w.innerWidth=width;for(const chapter of ['today','bets','money']){w.Hunch.chapter=chapter;await w.showLive();assert.doesNotMatch(w.document.getElementById('scene').textContent,/169\.30|Suns beat|Packers|250\.00/);console.log('LIVE',width,chapter,w.document.getElementById('scene').textContent.replace(/\s+/g,' ').slice(0,110))}}
w.Hunch.selected=w.Hunch.rows[0];for(const width of [390,880]){w.innerWidth=width;await w.showLive();console.log('MARKET',width,w.document.getElementById('scene').textContent.replace(/\s+/g,' ').slice(0,200))}
for(const state of [{signed:false,sent:false},{signed:false,sent:true},{signed:true,sent:false},{signed:true,passkey:true}])await w.showAccount({...state,email:'demo@example.invalid',demo:true});

w.innerWidth=390;w.Hunch.selected=w.Hunch.rows[0];
w.Hunch.selected.terms={rule:'<img src=x onerror=alert(1)>'};
w.Hunch.book={valid:true,time:Date.now(),ask:'0.41',complete:true,payout:'$48.78',spent:'$20.00'};
await w.showLive();assert.match(w.document.getElementById('scene').textContent,/48\.78/);assert.equal(w.document.getElementById('scene').querySelector('img'),null);
w.Hunch.book.time=Date.now()-61000;w.Paper.render();assert.doesNotMatch(w.document.getElementById('scene').textContent,/48\.78/);
w.Hunch.book={error:true};w.Paper.render();assert.match(w.document.getElementById('scene').textContent,/No verified executable quote/);
w.Hunch.selected=null;w.Hunch.chapter='today';await w.showLive();const search=w.document.getElementById('scene').querySelector('input[type=search]');search.focus();search.value='nonexistent';search.dispatchEvent(new w.Event('input'));w.Paper.render();assert.equal(w.document.activeElement,search);assert.match(w.document.getElementById('scene').textContent,/No matching markets/);

const first=w.Hunch.rows[0];w.Hunch.rows=Array.from({length:250},(_,i)=>({...first,id:String(i),title:'Listing '+i,category:i%2?'Prices':'Sports'}));w.Hunch.query='';w.Hunch.chapter='today';w.innerWidth=880;w.Hunch.selected=null;await w.showLive();let listing=w.document.querySelector('.paper-search-results');assert.equal(listing.querySelectorAll('button').length,101);Array.from(listing.querySelectorAll('button')).find(x=>x.textContent==='Show more markets').click();w.Paper.render();listing=w.document.querySelector('.paper-search-results');assert.equal(listing.querySelectorAll('button').length,201);w.Hunch.selected=w.Hunch.rows[0];await w.showLive();assert.ok(Array.from(w.document.querySelectorAll('div')).some(x=>x.style.overflowY==='auto'&&x.style.bottom==='10px'));w.Hunch.selected=null;
w.paperResize();await w.receiveNative({type:'auth',demo:true,signed:false,sent:false,email:'',message:'Enter a valid email address.'});await new Promise(resolve=>setTimeout(resolve,180));w.Paper.render();assert.match(w.document.getElementById('scene').textContent,/FICTIONAL SIGN-IN DEMO/);assert.equal(w.document.querySelector('[aria-label="Sign-in email"]').tagName,'INPUT');
assert.deepEqual(errors,[]);console.log('PASS: all 15 original hashes and templates; six live layouts; cover and unfolded detail; account transitions. Visual review still required.');
await w.happyDOM.abort();
