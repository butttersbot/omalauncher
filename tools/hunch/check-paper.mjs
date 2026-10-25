import {Window} from 'happy-dom';
import fs from 'node:fs';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
const base=new URL('../../hunch/src/main/assets/paper/',import.meta.url).pathname;
const w=new Window({url:'https://appassets.androidplatform.net/hunch/paper/index.html'});
w.document.write(fs.readFileSync(base+'index.html','utf8').replace(/<script[^>]*><\/script>/g,''));
w.fetch=async url=>{const s=fs.readFileSync(base+url,'utf8');return {text:async()=>s,json:async()=>JSON.parse(s)}};
w.SomaHunch={post:()=>{}};w.console=console;
for(const f of ['runtime.js','live.js','wallet.js','app.js'])w.eval(fs.readFileSync(base+f,'utf8')+(f==='live.js'?'\nwindow.showLive=showLive;window.showAccount=showAccount;window.cropPhone=cropPhone;window.text=text;window.action=action;window.event=event;':f==='wallet.js'?'\nwindow.showWallet=showWallet;':''));
let errors=[];w.paperError=e=>{errors.push(e);console.error(e)};
for(const {name}of JSON.parse(fs.readFileSync(base+'pages.json','utf8'))){const manifest=JSON.parse(fs.readFileSync(base+'pages.json','utf8')).find(p=>p.name===name);assert.equal(createHash('sha256').update(fs.readFileSync(base+'mockup/'+name+'.dc.html')).digest('hex'),manifest.sha256);await w.Paper.show(name);if(!w.document.getElementById('scene'))throw Error(name);console.log(name,w.document.getElementById('scene').textContent.replace(/\s+/g,' ').slice(0,70))}
w.Hunch.rows=[{id:'123',title:'Test real market',category:'Sports',venue:'Outcome',date:'Read venue terms',sides:['Yes','No'],terms:{rule:'Official settlement'}}];
for(const width of [390,880]){w.innerWidth=width;for(const chapter of ['today','bets','money']){w.Hunch.chapter=chapter;await w.showLive();assert.doesNotMatch(w.document.getElementById('scene').textContent,/169\.30|Suns beat|Packers|250\.00/);console.log('LIVE',width,chapter,w.document.getElementById('scene').textContent.replace(/\s+/g,' ').slice(0,110))}}
w.Hunch.selected=w.Hunch.rows[0];for(const width of [390,880]){w.innerWidth=width;await w.showLive();console.log('MARKET',width,w.document.getElementById('scene').textContent.replace(/\s+/g,' ').slice(0,200))}
for(const state of [{signed:false,sent:false},{signed:false,sent:true},{signed:true,sent:false},{signed:true,passkey:true}])await w.showAccount({...state,email:'demo@example.invalid',demo:true});

w.innerWidth=390;w.Hunch.selected=w.Hunch.rows[0];
w.Hunch.selected.terms={rule:'<img src=x onerror=alert(1)>',resolutionDeadline:'20291024-1730'};
w.Hunch.book={valid:true,time:Date.now(),ask:'0.41',complete:true,payout:'$48.78',spent:'$20.00'};
await w.showLive();assert.match(w.document.getElementById('scene').textContent,/48\.78/);assert.match(w.document.getElementById('scene').textContent,/Resolution Deadline: 2029-10-24 · 17:30 \(venue time\)/);assert.equal(w.document.getElementById('scene').querySelector('img'),null);
w.Hunch.book.time=Date.now()-61000;w.Paper.render();assert.doesNotMatch(w.document.getElementById('scene').textContent,/48\.78/);
w.Hunch.book={error:true};w.Paper.render();assert.match(w.document.getElementById('scene').textContent,/No verified executable quote/);
w.Hunch.selected=null;w.Hunch.chapter='today';await w.showLive();const search=w.document.getElementById('scene').querySelector('input[type=search]');search.focus();search.value='nonexistent';search.dispatchEvent(new w.Event('input'));w.Paper.render();assert.equal(w.document.activeElement,search);assert.match(w.document.getElementById('scene').textContent,/No matching markets/);w.paperResize();await new Promise(resolve=>setTimeout(resolve,180));assert.ok(w.document.activeElement===search,'keyboard resize must preserve the focused search input');

const first=w.Hunch.rows[0];w.Hunch.rows=Array.from({length:250},(_,i)=>({...first,id:String(i),title:'Listing '+i,category:i%2?'Prices':'Sports'}));w.Hunch.query='';w.Hunch.chapter='today';w.innerWidth=880;w.Hunch.selected=null;await w.showLive();let listing=w.document.querySelector('.paper-search-results');assert.equal(listing.querySelectorAll('button').length,101);Array.from(listing.querySelectorAll('button')).find(x=>x.textContent==='Show more markets').click();w.Paper.render();listing=w.document.querySelector('.paper-search-results');assert.equal(listing.querySelectorAll('button').length,201);w.Hunch.selected=w.Hunch.rows[0];await w.showLive();assert.ok(Array.from(w.document.querySelectorAll('div')).some(x=>x.style.overflowY==='auto'&&x.style.bottom==='10px'));w.Hunch.selected=null;
w.paperResize();await w.receiveNative({type:'auth',demo:true,signed:false,sent:false,email:'',message:'Enter a valid email address.'});await new Promise(resolve=>setTimeout(resolve,180));w.Paper.render();assert.match(w.document.getElementById('scene').textContent,/FICTIONAL SIGN-IN DEMO/);assert.equal(w.document.querySelector('[aria-label="Sign-in email"]').tagName,'INPUT');
// Submission handlers must read the mounted input after a status re-render,
// not the temporary input used to build the next morph tree.
const posts=[];w.SomaHunch.post=raw=>posts.push(JSON.parse(raw));
const emailField=w.document.querySelector('[aria-label="Sign-in email"]');emailField.value='demo@example.invalid';emailField.focus();w.Paper.render();
Array.from(w.document.querySelectorAll('button')).find(b=>b.textContent==='Send email code').click();
assert.equal(posts.at(-1).email,'demo@example.invalid');
await w.receiveNative({type:'auth',demo:true,signed:false,sent:true,email:'demo@example.invalid'});w.Paper.render();
let codeField=w.document.querySelector('[aria-label="Email code"]');codeField.value='123456';codeField.focus();w.Paper.render();
Array.from(w.document.querySelectorAll('button')).find(b=>b.textContent==='Verify email code').click();
assert.equal(posts.at(-1).code,'123456');assert.equal(codeField.value,'');
w.document.getElementById('viewport').scrollTop=300;await w.receiveNative({type:'auth',demo:true,signed:true,email:'demo@example.invalid'});w.Paper.render();assert.equal(w.document.getElementById('viewport').scrollTop,0);assert.equal(w.document.querySelector('#scene svg').parentElement.style.height,'164px');
w.document.getElementById('viewport').scrollTop=250;await w.receiveNative({type:'auth',demo:true,signed:false,sent:false,email:''});w.Paper.render();assert.equal(w.document.getElementById('viewport').scrollTop,0);
await w.receiveNative({type:'wallet',demo:true,signed:false,busy:false,message:''});w.Paper.render();assert.match(w.document.querySelector('#scene').textContent,/Sign in to Hunch/);
await w.receiveNative({type:'wallet',demo:true,signed:true,address:'',busy:false});w.Paper.render();assert.match(w.document.querySelector('#scene').textContent,/Create Hunch wallet/);
await w.receiveNative({type:'wallet',demo:true,signed:true,address:'0x'+'1'.repeat(40),qr:'AAAA',balances:{arbitrumUsdc:'12.50',arbitrumEth:'0.001',hyperliquidTotal:'0',hyperliquidAvailable:'0'}});w.Paper.render();assert.match(w.document.querySelector('#scene').textContent,/Arbitrum USDC: 12.50 USDC/);assert.match(w.document.querySelector('#scene').textContent,/not trading cash/);assert.ok(w.document.querySelector('#scene img').src.startsWith('data:image/png;base64,'));
await w.receiveNative({type:'wallet',demo:true,signed:true,address:'0x'+'1'.repeat(40),qr:'AAAA',balances:{arbitrumError:true}});w.Paper.render();assert.match(w.document.querySelector('#scene').textContent,/Arbitrum USDC: Unavailable/);assert.doesNotMatch(w.document.querySelector('#scene').textContent,/12.50/);
w.innerWidth=880;w.Hunch.chapter='bets';w.Hunch.selected=null;await w.showLive();
const betsArt=Array.from(w.document.querySelector('#scene').children).find(x=>x.style.left==='462px'&&x.style.top==='22px');
assert.equal(betsArt.children[0].textContent,'NO BET SELECTED');assert.equal(betsArt.children[1].style.bottom,'0px');
assert.equal(w.document.querySelector('#scene').querySelector('button:disabled').textContent,'CASH OUT —');
assert.deepEqual(errors,[]);console.log('PASS: all 15 manifest hashes and templates; six live layouts; cover and unfolded detail; account transitions. Visual review still required.');
await w.happyDOM.abort();
