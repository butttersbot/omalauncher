 'use strict';
window.Hunch={rows:[],chapter:'today',selected:null,side:0,budget:20,book:null,query:'',category:'ALL',error:false,updated:0,books:{},volumes:{},volumesLoaded:false,volumeError:false,watchlist:[],discoveryTab:'markets',section:'ALL',priceType:'ALL',sort:'grouped'};
window.sendNative=data=>{if(data.type==='wallet'||data.type==='account')data={...data,panelTop:Math.ceil(document.querySelector('.hunch-two-pane .right')?.getBoundingClientRect().top||0)};window.SomaHunch?.post(JSON.stringify(data))};
window.notice=text=>{const n=document.getElementById('notice');n.textContent=text;n.hidden=false;clearTimeout(window.noticeTimer);window.noticeTimer=setTimeout(()=>n.hidden=true,5000)};
function event(el,type,fn){el._paperEvents ||= {};el._paperEvents[type]=fn;el.addEventListener(type,fn)}
function text(el,value){if(el)el.textContent=value}
function action(label,fn){const b=document.createElement('button');b.type='button';b.className='paper-action';b.textContent=label;event(b,'click',fn);return b}
function empty(panel,heading,copy,button){if(panel.querySelector(':scope > svg')&&panel.lastElementChild.localName!=='svg')panel=panel.lastElementChild;const spans=Array.from(panel.querySelectorAll('span,b')).filter(x=>!x.children.length);const titleStyle=spans[0]?.getAttribute('style')||"font:700 11px 'JetBrains Mono'";panel.replaceChildren();const h=document.createElement('span');h.setAttribute('style',titleStyle);h.style.position='static';h.style.transform='none';h.style.margin='0';h.textContent=heading;const d=document.createElement('span');d.className='empty-copy';d.textContent=copy;panel.append(h,d);if(button)panel.append(button);return panel}
function termEntries(terms){return Object.entries(terms).map(([key,value])=>{const label=key.replace(/([a-z])([A-Z])/g,'$1 $2').replace(/^./,c=>c.toUpperCase());const match=/^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})$/.exec(value);const display=match?`${match[1]}-${match[2]}-${match[3]} · ${match[4]}:${match[5]} (venue time)`:value;return [label,display]})}
let bookTimer;function requestBook(){clearTimeout(bookTimer);if(!Hunch.selected)return;Hunch.book=null;const id=Hunch.selected.id,budget=Hunch.budget;bookTimer=setTimeout(()=>{[0,1].forEach(side=>sendNative({type:'book',id,side,budget}))},150)}
function choose(m){Hunch.selected=m;Hunch.side=0;Hunch.books={};requestBook();showLive()}
function liveRows(){return Hunch.rows.filter(m=>(Hunch.discoveryTab!=='watchlist'||Hunch.watchlist.includes(m.id))&&(Hunch.category==='ALL'||m.category.toUpperCase()===Hunch.category)&&(Hunch.category!=='PRICES'||Hunch.priceType==='ALL'||m.priceType===Hunch.priceType)&&(Hunch.section==='ALL'||discoverySection(m)===Hunch.section)&&(!Hunch.query||(m.title+' '+m.category+' '+m.venue+' '+discoverySection(m)+' '+(m.eventTitle||'')).toLowerCase().includes(Hunch.query.toLowerCase()))).sort(compareMarkets)}
const listingQuotes=new Map();
function listingQuote(m,side=0){const snapshot=listingQuotes.get(m.id),q=snapshot?.quotes?.[side]||(side===0?snapshot:null);return q?.valid&&Number(q.ask)>0&&Number(q.ask)<1&&Date.now()-q.time<=60000&&Date.now()-q.time>=-5000?q:null}
function listingPercent(m){return quoteCopy(m,0)+' · '+quoteCopy(m,1)}
function listingPrice(m){return '24H VOL '+dollars(marketVolume(m))}
function listingIdentity(node,m){node.dataset.marketId=m.id;node.title='Buy asks for each outcome side, quoted in cents per share.';return node}
let quoteTimer;const quoteAsked=new Map();
window.requestListingQuotes=()=>{clearTimeout(quoteTimer);quoteTimer=setTimeout(()=>{
 if(accountMode||design)return;
 const now=Date.now(),ids=[];
 document.querySelectorAll('[data-market-id]').forEach(el=>{const r=el.getBoundingClientRect(),id=el.dataset.marketId;
  if(r.bottom>0&&r.top<innerHeight&&r.right>0&&r.left<innerWidth&&!ids.includes(id)&&now-(quoteAsked.get(id)||0)>12000)ids.push(id)
 });
 const batch=ids.slice(0,16);if(batch.length){batch.forEach(id=>quoteAsked.set(id,now));sendNative({type:'listingQuotes',ids:batch})}
 },180)};
document.addEventListener('scroll',()=>window.requestListingQuotes(),true);
function marketButton(m){return discoveryRow(m)}
function updateFeed(feed){const shared=buildDiscovery();const shell=paneNode('div','hunch-two-pane discovery-shell');shell.append(shared);feed.replaceChildren(shell)}
function marketVolume(m){const v=Hunch.volumes?.[m.id];return v&&Date.now()-v.checked>=-5000&&Date.now()-v.checked<300000&&Number.isFinite(Number(v.volume24h))&&v.volume24h!=null?Number(v.volume24h):null}
function dollars(value){return value==null?'—':new Intl.NumberFormat('en-US',{style:'currency',currency:'USD',maximumFractionDigits:value>=1000?0:2}).format(value)}
function priceFor(m,side){const q=listingQuote(m,side);if(q)return (Number(q.ask)*100).toLocaleString('en-US',{maximumFractionDigits:2})+'¢';const raw=listingQuotes.get(m.id)?.quotes?.[side];if(!raw)return 'Loading';if(raw.error)return 'Unavailable';if(raw.time&&Date.now()-raw.time>60000)return 'Stale';return raw.status==='noAsks'?'No asks':raw.status==='stale'?'Stale':'Unavailable'}
function quoteCopy(m,side){return (m.sides?.[side]||(side?'No':'Yes'))+' '+priceFor(m,side)}
function detailQuote(side){const q=Hunch.books?.[side];return q?.valid&&Date.now()-q.time<=60000&&Date.now()-q.time>=-5000?q:null}
function detailPrice(side){const q=detailQuote(side);return q?(Number(q.ask)*100).toLocaleString('en-US',{maximumFractionDigits:2})+'¢':'—'}
function detailStatus(side){const q=Hunch.books?.[side];return detailQuote(side)?'LIVE ASK':!q?'LOADING ASK…':q.error?'QUOTE UNAVAILABLE':q.stale?'QUOTE EXPIRED':q.crossed?'INVALID BOOK':'NO ASKS'}
function discoverySection(m){return m.section||m.terms?.competition||m.terms?.perp?.split(':').at(-1)||m.terms?.company||m.category}
function compareMarkets(a,b){
 const deadline=(a.deadline||a.terms?.resolutionDeadline||a.terms?.time||'99999999').localeCompare(b.deadline||b.terms?.resolutionDeadline||b.terms?.time||'99999999');
 if(Hunch.sort==='volume')return (marketVolume(b)??-1)-(marketVolume(a)??-1)||deadline||a.title.localeCompare(b.title);
 if(Hunch.sort==='soon')return deadline||a.title.localeCompare(b.title);
 const section=(a.category+' '+(a.priceType||'')+' '+discoverySection(a)).localeCompare(b.category+' '+(b.priceType||'')+' '+discoverySection(b),undefined,{numeric:true});
 const event=(a.eventTitle||discoverySection(a)).localeCompare(b.eventTitle||discoverySection(b));
 return section||event||deadline||(Number(a.threshold||a.terms?.threshold)||0)-(Number(b.threshold||b.terms?.threshold)||0)||a.title.localeCompare(b.title)||a.venue.localeCompare(b.venue);
}
function discoveryRow(m){
 const row=listingIdentity(paneButton('','mkt'+(Hunch.selected?.id===m.id?' sel':''),()=>choose(m)),m);
 const title=paneNode('span','t');title.append(paneNode('b','',m.title),paneNode('small','',m.venue+' · '+m.date),paneNode('small','volume','24H VOL '+dollars(marketVolume(m))));
 row.append(title);(m.sides||['Yes','No']).forEach((name,i)=>{const q=paneNode('span','quote-cell');q.append(paneNode('small','',name),paneNode('b','',priceFor(m,i)));q.title=listingQuote(m,i)?'Current buy ask for '+name:'No current executable ask for '+name;row.append(q)});return row;
}
function discoveryColumns(rows=[]){const named=rows.some(m=>(m.sides||['Yes','No']).some((name,i)=>name.toLowerCase()!==(i?'no':'yes')));const row=paneNode('div','quote-columns');row.append(paneNode('span','','MARKET'),paneNode('span','',named?'BUY SIDE 1':'BUY YES'),paneNode('span','',named?'BUY SIDE 2':'BUY NO'));return row}
function discoveryGroups(rows,target){
 let group='',event='';rows.forEach(m=>{
  const next=Hunch.sort==='grouped'?(Hunch.category==='ALL'?m.category+' · ':'')+(m.priceType?m.priceType+' · ':'')+discoverySection(m):'';
  if(next!==group){group=next;event='';target.append(paneHeading(group.toUpperCase()),discoveryColumns(rows))}
  const match=m.category==='Sports'?(m.eventTitle||''):'';
  if(match&&match!==event){event=match;target.append(paneNode('h4','event-title',match))}
  target.append(discoveryRow(m));
 });
}
function buildDiscovery(){
 const inner=paneNode('div','inner discovery-content');
 const tabs=paneNode('nav','discovery-tabs');tabs.setAttribute('aria-label','Market lists');
 [['markets','MARKETS'],['watchlist','WATCHLIST']].forEach(([key,label])=>{const b=paneButton(label+(key==='watchlist'?' '+Hunch.watchlist.length:''),'rtab'+(Hunch.discoveryTab===key?' on':''),()=>{Hunch.discoveryTab=key;if(key==='watchlist'){Hunch.category='ALL';Hunch.section='ALL';Hunch.priceType='ALL';Hunch.query=''}Hunch.limit=100;Paper.schedule()});b.setAttribute('aria-pressed',String(Hunch.discoveryTab===key));tabs.append(b)});inner.append(tabs);
 const search=paneNode('label','search'),input=paneNode('input');input.type='search';input.placeholder='Search teams, coins, people…';input.setAttribute('aria-label','Search markets');input.value=Hunch.query;event(input,'input',e=>{Hunch.query=e.target.value;Hunch.limit=100;Paper.schedule()});search.append(input);inner.append(search);
 const chips=paneNode('div','chips');chips.setAttribute('aria-label','Market categories');
 const categories=['ALL','PRICES','SPORTS','EVENTS','PRE-IPO','RATES'].filter(cat=>cat==='ALL'||Hunch.rows.some(m=>m.category.toUpperCase()===cat));
 categories.forEach(cat=>{const count=Hunch.rows.filter(m=>cat==='ALL'||m.category.toUpperCase()===cat).length;const b=paneButton(cat+' '+count,'chip'+(Hunch.category===cat?' on':''),()=>{Hunch.category=cat;Hunch.section='ALL';Hunch.priceType='ALL';Hunch.limit=100;Paper.schedule()});b.setAttribute('aria-pressed',String(Hunch.category===cat));chips.append(b)});inner.append(chips);
 let categoryRows=Hunch.rows.filter(m=>Hunch.category==='ALL'||m.category.toUpperCase()===Hunch.category);
 if(Hunch.category==='PRICES'){
  const types=paneNode('div','chips price-types');types.setAttribute('aria-label','Price market types');
  ['ALL',...new Set(categoryRows.map(m=>m.priceType).filter(Boolean))].forEach(type=>{const b=paneButton(type==='ALL'?'All assets':type,'chip'+(Hunch.priceType===type?' on':''),()=>{Hunch.priceType=type;Hunch.section='ALL';Hunch.limit=100;Paper.schedule()});b.setAttribute('aria-pressed',String(Hunch.priceType===type));types.append(b)});inner.append(types);
  if(Hunch.priceType!=='ALL')categoryRows=categoryRows.filter(m=>m.priceType===Hunch.priceType);
 }
 if(Hunch.category!=='ALL'){
  const filters=paneNode('div','chips subcategories');filters.setAttribute('aria-label',Hunch.category==='SPORTS'?'Competitions':'Assets and topics');
  ['ALL',...new Set(categoryRows.map(discoverySection).sort((a,b)=>a.localeCompare(b,undefined,{numeric:true})))].forEach(section=>{const b=paneButton(section==='ALL'?'All '+Hunch.category.toLowerCase():section,'chip'+(Hunch.section===section?' on':''),()=>{Hunch.section=section;Hunch.limit=100;Paper.schedule()});b.setAttribute('aria-pressed',String(Hunch.section===section));filters.append(b)});inner.append(filters);
 }
 const controls=paneNode('div','discovery-controls'),label=paneNode('label','','SORT '),sort=paneNode('select');sort.setAttribute('aria-label','Sort markets');
 [['grouped','Grouped'],['volume','24h volume'],['soon','Closing soon']].forEach(([value,name])=>{const o=paneNode('option','',name);o.value=value;o.selected=Hunch.sort===value;sort.append(o)});event(sort,'change',e=>{Hunch.sort=e.target.value;Hunch.limit=100;Paper.schedule()});label.append(sort);controls.append(label);inner.append(controls);
 const rows=liveRows();
 if(!rows.length){inner.append(paneNode('p','empty',Hunch.error?'Markets could not be loaded. Check your connection.':!Hunch.rows.length?'Loading listings…':Hunch.discoveryTab==='watchlist'?'No saved markets match. Open a market and tap Save to watchlist.':'No matching markets. Try another category or search.'))}
 else{
  const trending=Hunch.discoveryTab==='markets'&&!Hunch.query&&Hunch.sort==='grouped'?rows.filter(m=>(marketVolume(m)??0)>0).sort((a,b)=>marketVolume(b)-marketVolume(a)).slice(0,3):[];
  if(trending.length){const shelf=paneNode('section','trending');shelf.append(paneHeading('TRENDING · 24H VOLUME'),discoveryColumns(trending));trending.forEach(m=>shelf.append(discoveryRow(m)));inner.append(shelf)}
  if(!Hunch.volumesLoaded)inner.append(paneNode('p','data-status','Loading 24h volume…'));
  else if(Hunch.volumeError)inner.append(paneNode('p','data-status','24h volume unavailable. Refresh to retry.'));
  const rest=rows.filter(m=>!trending.includes(m)),list=paneNode('section','market-list');
  if(rest.length){list.append(paneHeading(Hunch.discoveryTab==='watchlist'?'YOUR WATCHLIST':Hunch.sort==='grouped'?'BROWSE BY '+(Hunch.category==='SPORTS'?'COMPETITION':Hunch.category==='PRICES'?'ASSET':'TOPIC'):'MARKETS · '+rest.length));
   if(Hunch.sort!=='grouped')list.append(discoveryColumns(rows));discoveryGroups(rest.slice(0,Hunch.limit||100),list);
   if(rest.length>(Hunch.limit||100))list.append(paneButton('Show more markets','rtab',()=>{Hunch.limit=(Hunch.limit||100)+100;Paper.schedule()}));inner.append(list);
  }
 }
 inner.append(paneButton('Refresh markets','rtab refresh-markets',()=>{Hunch.volumesLoaded=false;sendNative({type:'markets'});Paper.schedule()}));return inner;
}
function watchButton(m){const saved=Hunch.watchlist.includes(m.id);const b=paneButton(saved?'Saved to watchlist':'Save to watchlist','rtab save-market',()=>sendNative({type:'watchlist',id:m.id,saved:!Hunch.watchlist.includes(m.id)}));b.setAttribute('aria-pressed',String(saved));return b}

function coverPrepare(root){
 const top=root.children[0];text(top?.lastElementChild,'CASH —');const body=root.children[1];
 if(Hunch.chapter==='today') {const feed=body.querySelector('.noscroll > div');if(feed)updateFeed(feed)}
 else if(Hunch.chapter==='bets') {const panels=Array.from(body.children);if(panels[0]){const art=panels[0];art.style.background='radial-gradient(circle, rgba(17,17,19,.18) .6px, transparent .95px) 0 0 / 4px 4px, #fdfdfb';art.style.display='flex';art.style.flexDirection='column';art.style.justifyContent='center';art.style.padding='12px';empty(art,'MY BETS','No trading account is connected.');art.querySelectorAll('span').forEach(el=>{el.style.position='static';el.style.transform='none';el.style.width='auto'})}if(panels[2]){const tabs=panels[2].firstElementChild;text(tabs.lastElementChild,'NOT CONNECTED');panels[2].replaceChildren(tabs);const p=document.createElement('p');p.className='empty-copy';p.textContent='Your real positions and settled bets will appear here once trading is connected.';panels[2].append(p)}}
 else {const panels=Array.from(body.children);cashPanel(panels[0]);empty(panels[1],'Order confirmation','No app spending caps. Every order needs your review and signature.');recordPanel(panels[2])}
}
async function showLive(){
 const wide=window.innerWidth>=600;Hunch.layoutWide=wide;
 if(wide)return showTwoPane();
 const oldPane=document.querySelector('.hunch-two-pane .right,.hunch-two-pane .cvbody');if(oldPane&&paneShown!==null)paneScroll.set(paneShown,oldPane.scrollTop);
 if(Hunch.selected)return showCoverMarket();
 await Paper.show('CoverBets',logic=>{
  logic.state.tab=Hunch.chapter;const a=Paper.active;
  a.values=v=>({...v,zoomed:false,notZoomed:true,rows:[],settled:[],tot:{stake:'—',worth:'—',pays:'—'},next:{left:'—',title:'No connected bets',sub:'Read-only markets',chance:'—',pick:()=>{}},segs:v.segs.map((s,i)=>({...s,label:i?'SETTLED':'OPEN'})),tabs:v.tabs.map((t,i)=>({...t,hasBadge:false,badge:'',aria:['Today','Bets','Money'][i],sub:i===0?'PUBLIC MARKETS':i===1?'NO ACCOUNT':'NOT CONNECTED',pick:()=>route(['today','bets','money'][i])}))});
  const height=()=>{const view=document.getElementById('viewport');return view.clientWidth>0?Math.max(300,view.clientHeight/(view.clientWidth/390)):844};
  // The source reserves86px for58px tabs. Use the full safe viewport instead
  // of leaving28px of blank paper below them; native insets protect gestures.
  a.prepare=root=>{coverPrepare(root);root.style.height=height()+'px';root.children[1].style.height=Math.max(100,height()-126)+'px';root.querySelector('nav[aria-label="Hunch pages"]').style.height='58px'};
  a.dimensions=()=>({width:390,height:height()});
 });
}
function route(chapter){Hunch.chapter=chapter;Hunch.selected=null;sendNative({type:'route',chapter});showLive()}
async function showMarket(wide){
 const m=Hunch.selected;
 await Paper.show('Main',logic=>{
  const a=Paper.active;const ordered=[m,...Hunch.rows.filter(x=>x.id!==m.id)];const group=x=>x.category==='Sports'?'tonight':x.category==='Prices'?'sunday':'later';const bind=(rows,key)=>rows.map((r,i)=>({...r,pick:()=>choose(ordered.filter(x=>group(x)===key)[i])}));logic.state.sel=m.id;logic.state.amt=Hunch.budget;logic.state.side=Hunch.side;
  logic.markets=()=>ordered.map(x=>({id:x.id,group:group(x),title:x.title,sub:x.category+' · '+x.venue,label:x.venue+' · '+x.date,heading:x.title,question:x.sides.join(' / '),rules:termEntries(x.terms).map(([k,v])=>k+': '+v).join(' · '),tag:'READ ONLY',lo:0,hi:0,sides:x.sides.map(name=>({name,price:1,chance:'—',ifText:'If '+name+' settles in your favor',fill:'Loading live order book…',venue:x.venue}))}));
  a.values=v=>{const received=Hunch.book;const b=received&&(!received.valid||Date.now()-received.time<=60000)?received:null;return {...v,tonight:bind(v.tonight,'tonight'),sunday:bind(v.sunday,'sunday'),later:bind(v.later,'later'),sides:v.sides.map((s,i)=>({...s,cents:i===Hunch.side&&b?.valid?(Number(b.ask)*100).toFixed(2)+'¢ ASK':'NO VERIFIED QUOTE',chance:'—',pick:()=>{Hunch.side=i;logic.state.side=i;requestBook();Paper.schedule()}})),amt:Hunch.budget,amtLabel:'$'+Hunch.budget,payout:b?.valid?b.payout:'—',profit:'—',ifText:'Depth preview · not an order',ctaVerb:'READ ONLY',side:{...v.side,fill:b?.valid?(b.complete?'Full preview at current depth.':'Partial fill at current depth.'):b?'No valid executable quote.':received?'Quote expired. Refresh preview.':'Loading live order book…',venue:m.venue},chips:v.chips.map((c,i)=>({...c,pick:()=>{Hunch.budget=[5,10,20,50,100][i];logic.state.amt=Hunch.budget;requestBook();Paper.schedule()}})),onRange:e=>{Hunch.budget=Number(e.target.value);logic.state.amt=Hunch.budget;requestBook();Paper.schedule()}}};
  a.prepare=root=>{const copy={'TONIGHT · SETTLES 11 PM PT':'SPORTS','SUNDAY · OCT 25':'PRICES','LATER':'EVENTS','EMPTY MARKETS HIDDEN · SHOW':'READ-ONLY PUBLIC DATA','EMPTY MARKETS HIDDEN ·':'READ-ONLY PUBLIC DATA','SHOW':'REFRESH'};root.querySelectorAll('span,button').forEach(el=>{if(!el.children.length&&copy[el.textContent.trim()]){el.textContent=copy[el.textContent.trim()];if(el.tagName==='BUTTON')el.disabled=true}});root.querySelectorAll('div[style*="left: 22px"][style*="height: 150px"],div[style*="left: 22px"][style*="height: 208px"]').forEach(p=>{const list=p.lastElementChild;list.style.bottom='10px';list.style.overflowY='auto'});root.querySelectorAll('div[style*="left: 22px"][style*="height: 236px"]').forEach(p=>p.style.overflowY='auto');root.querySelectorAll('div[style*="height: 292px"],div[style*="width: 236px"]').forEach(p=>p.style.overflowY='auto');root.querySelectorAll('a').forEach(el=>{el.removeAttribute('href');text(el.firstElementChild,'DEPTH PREVIEW');event(el,'click',()=>{requestBook();Paper.schedule()})});const header=root.querySelector('div[style*="height: 92px"]');if(header){header.querySelectorAll('span').forEach(el=>{if(el.textContent.startsWith('$'))text(el,'—')});const brand=header.firstElementChild;event(brand,'click',()=>{Hunch.selected=null;showLive()});brand.setAttribute('role','button');brand.setAttribute('aria-label','Back to Today');brand.tabIndex=0;event(brand,'keydown',e=>{if(e.key==='Enter'){Hunch.selected=null;showLive()}})} };
  a.dimensions=()=>({width:880,height:760});
 });
}

let authState=null,authScreen=null,resetAuthScroll=false;
async function showAccount(data){
 const screen=data.signed?'signed':data.sent?'code':'email';if(screen!==authScreen){authScreen=screen;resetAuthScroll=true;document.activeElement?.blur()}
 authState=data;
 if(Paper.active?.account){Paper.schedule();return}
 await Paper.show('Flow',logic=>{
  logic.state.step=1;const a=Paper.active;a.account=true;
  a.prepare=root=>{
   // The sign-in panel is the original Flow/step-1 drawing, not a native approximation.
   const phone=root.querySelector('div[style*="width: 390px"]');
   if(!phone)throw Error('Missing original sign-in frame');
   const content=phone.firstElementChild;
   const state=authState;root.replaceChildren(phone);root.style.cssText="position:relative;width:390px;min-height:844px;background:#fdfdfb;color:#111113;font-family:'Zen Kaku Gothic New',sans-serif";
   phone.style.cssText='position:relative;width:390px;min-height:844px;background:#fdfdfb;box-sizing:border-box';
   content.style.cssText='padding:18px;display:flex;flex-direction:column;gap:14px;min-height:808px;box-sizing:border-box';
   const card=content.querySelector('div[style*="padding: 16px"]');
   if(!card)throw Error('Missing original sign-in panel');
   text(card.children[0],state.demo?'HUNCH / FICTIONAL SIGN-IN DEMO':'HUNCH / YOUR ACCOUNT');
   text(card.children[1],state.signed?'Secure your sign-in':'Sign in to Hunch');
   text(card.children[2],state.signed?'Your email login stays available for recovery.':'Start with email. Then secure your sign-in with a passkey; keep email for recovery.');
   const art=card.nextElementSibling;const primary=content.querySelector('button');const secondary=primary?.nextElementSibling;
   const primaryStyle=primary?.getAttribute('style');const secondaryStyle=secondary?.getAttribute('style');
   content.replaceChildren(card);if(art){art.style.flex='none';art.style.height='164px';content.append(art)}
   const msg=document.createElement('p');msg.style.cssText='margin:0;font-size:14px;line-height:1.5';msg.setAttribute('role','status');msg.textContent=state.message||'Sign-in only. No wallet or payment is created here.';content.append(msg);
   function field(label,type,value){const input=document.createElement('input');input.type=type;input.placeholder=label;input.setAttribute('aria-label',label==='Email address'?'Sign-in email':label);input.autocomplete='off';input.value=value||'';input.style.cssText="height:46px;box-sizing:border-box;padding:10px;border:1.5px solid #111113;background:#fdfdfb;color:#111113;font:14px 'Zen Kaku Gothic New';width:100%";input.disabled=state.busy;event(input,'focus',()=>setTimeout(()=>{if(document.activeElement===input)input.scrollIntoView({block:'center'})},300));if(type==='password'){input.inputMode='numeric';input.maxLength=6}content.append(input);return input}
   function button(label,fn,main=false){const b=action(label,fn);b.setAttribute('style',main?primaryStyle:secondaryStyle);b.style.flexShrink='0';b.disabled=state.busy;content.append(b);return b}
   const post=(action,extra={})=>{if(!state.busy)sendNative({type:'authAction',action,...extra})};
   if(!state.signed){
    if(!state.sent){const email=field('Email address','email',state.email);const send=()=>post('send',{email:document.querySelector('#scene [aria-label="Sign-in email"]')?.value||''});event(email,'keydown',e=>{if(e.key==='Enter'){e.preventDefault();send()}});button('Send email code',send,true);button('Sign in with an existing passkey',()=>post('login'))}
    else {const line=document.createElement('span');line.textContent='Code sent to '+state.email;content.append(line);const code=field('Email code','password','');code.enterKeyHint='done';const verify=()=>{const mounted=document.querySelector('#scene [aria-label="Email code"]');post('verify',{code:mounted?.value||''});if(mounted){mounted.value='';mounted.blur()}};event(code,'keydown',e=>{if(e.key==='Enter'){e.preventDefault();verify()}});button('Verify email code',verify,true);button('Resend email code',()=>post('resend'));button('Use a different email',()=>post('different'))}
   }else{
    const status=document.createElement('span');status.textContent='Signed in';content.append(status);const email=document.createElement('span');email.textContent=state.email?'Recovery email: '+state.email:'No recovery email is linked.';content.append(email);
    if(state.passkey){const linked=document.createElement('span');linked.textContent='Passkey added';content.append(linked)}else if(state.email)button('Create a passkey',()=>post('link'),true);
    button('Sign out',()=>post('logout'));
   }
   button('Check sign-in again',()=>post('check'));button('Back to markets',()=>sendNative({type:'close'}));
  };
  a.after=()=>{if(resetAuthScroll){resetAuthScroll=false;document.getElementById('viewport').scrollTop=0}};
  a.autoHeight=true;a.maxScale=1;a.dimensions=()=>({width:390,height:844});
 });
}
function wideDiscoverHeight(){const view=document.getElementById('viewport');return view.clientWidth>0?Math.max(760,view.clientHeight*880/view.clientWidth):760}
async function showWide(){
 const name=Hunch.chapter==='today'?'Discover':Hunch.chapter==='bets'?'Bets':'Money';
 await Paper.show(name,logic=>{
  const a=Paper.active;
  if(name==='Discover')a.dimensions=()=>({width:880,height:wideDiscoverHeight()});
  a.values=v=>({...v,watch:[],edges:[],card:{...v.card,q:liveRows()[1]?.title||liveRows()[0]?.title||'Know something the market doesn’t?',when:liveRows()[1]?.date||'PUBLIC MARKETS'},num:1,total:Hunch.rows.length,found:'—',hidden:true,revealed:false,reveal:()=>{const m=liveRows()[1]||liveRows()[0];if(m)choose(m)},rows:[],settled:[],tot:{stake:'—',worth:'—',pays:'—'},b:Object.fromEntries(Object.keys(v.b||{}).map(k=>[k,k==='title'?'No connected bets':k==='poly'?'':'—'])),tabs:v.tabs?.map((t,i)=>({...t,label:i?'SETTLED':'OPEN'}))});
  a.prepare=root=>{
   const panels=Array.from(root.children).filter(x=>x.style.position==='absolute'&&parseFloat(x.style.width)>100);
   if(name==='Discover'){
    const search=root.querySelector('input[type=search]');search.value=Hunch.query;event(search,'input',e=>{Hunch.query=e.target.value;Hunch.limit=100;Paper.schedule()});text(search.parentElement.lastElementChild,Hunch.rows.length+' MARKETS');
    const cover=panels.find(p=>p.style.top==='82px');const rows=liveRows();
    if(cover){text(cover.firstElementChild,'PUBLIC MARKETS');const title=cover.querySelector('div[style*="rotate"]');text(title?.children[0],rows[0]?.venue||'HUNCH');text(title?.children[1],rows[0]?.title||'Public markets');if(title)title.style.whiteSpace='normal';const link=cover.querySelector('a');text(link?.firstElementChild,rows[0]?.date||(Hunch.error?'Markets could not be loaded.':'Loading listings…'));if(link){link.removeAttribute('href');event(link,'click',()=>rows[0]&&choose(rows[0]))}if(link&&!rows.length){text(link.lastElementChild,'');link.setAttribute('aria-disabled','true')}const surprise=cover.querySelector('button');if(surprise)surprise.disabled=!rows.length;if(surprise)event(surprise,'click',()=>rows.length&&choose(rows[Math.floor(Math.random()*rows.length)]))}
    const watch=panels.find(p=>p.style.top==='286px');if(watch)empty(watch.lastElementChild,'FROM YOUR SOMA WATCHLIST','No watchlist has been connected.');
    const edge=panels.find(p=>p.style.top==='466px');if(edge)empty(edge.lastElementChild,'LOOKS MISPRICED · VS OUTSIDE PRICES','Outside-price comparisons are not connected yet. No edge is claimed.');
    const gut=panels.find(p=>p.style.left==='462px'&&p.style.top==='18px');if(gut){text(gut.children[3],'Guess first. Inspect the live order book next.');gut.querySelectorAll('span').forEach(el=>{if(el.textContent==='THE PRICES ARE UNDER HERE')text(el,'CHECK THE LIVE ORDER BOOK')})}
    if(gut&&!rows.length)empty(gut,'GUT CHECK',Hunch.error?'Markets are unavailable. Refresh markets to try again.':'Waiting for public listings.');
    const listings=panels.find(p=>p.style.left==='462px'&&p!==gut);if(listings){listings.style.overflowY='auto';listInto(listings,rows,100)}
   }else{
    panels.forEach((panel,i)=>{if(name==='Money'&&i===0){cashPanel(panel);return}if(name==='Money'&&i===3){recordPanel(panel);return}if(name==='Bets'){
     if(i===0){text(panel.children[0].lastElementChild,'NOT CONNECTED');return}
     if(i===1){const tabs=panel.firstElementChild;panel.replaceChildren(tabs);const copy=document.createElement('p');copy.className='empty-copy';copy.textContent='No real positions or settled bets are connected.';panel.append(copy);return}
     if(i===2){text(panel.children[0],'NO BET SELECTED');text(panel.children[1],'Connect a trading account to see your positions.');return}
     if(i===4||i===5){text(panel.lastElementChild,'—');return}
     if(i===6){panel.querySelectorAll('button').forEach(b=>{b.disabled=true;if(b.textContent.startsWith('CASH OUT'))text(b,'CASH OUT —')});text(panel.lastElementChild,'No trading account is connected.');return}
    }const inner=panel.querySelector(':scope > svg')?panel.lastElementChild:panel;const headings=name==='Money'?['CASH YOU CAN BET OR TAKE OUT','Daily limit','YOUR WALLET','YOUR RECORD','ACTIVITY']:['MY BETS','YOUR POSITIONS','SETTLED BETS','POSITION DETAILS'];empty(inner,headings[i]||'MY BETS',name==='Money'?'Trading is not enabled yet. Open your wallet for current balances.':'No real positions or settled bets are connected.',name==='Money'&&i===2?action('Open your wallet',()=>sendNative({type:'wallet'})):null)})
   }
   if(name==='Discover'){
    // Keep the original two-page grid, but give its panels the available height.
    // Scale panel geometry only: type and controls retain their original size.
    const height=wideDiscoverHeight(),stretch=height/760;
    root.style.height=height+'px';
    for(const panel of root.children){
     if(panel.style.position!=='absolute'||panel.tagName==='NAV'||panel.tagName==='LABEL')continue;
     if(panel.style.top.endsWith('px'))panel.style.top=(parseFloat(panel.style.top)*stretch)+'px';
     if(panel.style.height.endsWith('px')){
      panel.style.height=(parseFloat(panel.style.height)*stretch)+'px';
      const frame=panel.querySelector(':scope > svg');
      if(frame){frame.style.height='100%';frame.setAttribute('preserveAspectRatio','none')}
     }
    }
    const hero=panels.find(p=>p.style.left==='0px');
    const title=hero?.querySelector('div[style*="rotate"]');
    if(title){
     // Reserve separate bands for header controls, the title and the footer.
     // Long live titles must scroll inside their band, never cover the controls.
     Object.assign(title.style,{left:'22px',right:'14px',top:'52px',bottom:'60px',transform:'rotate(-3deg)',overflowY:'auto',boxSizing:'border-box',display:'flex',flexDirection:'column',justifyContent:'safe center'});
     title.children[1].style.fontSize='20px';
    }
   }
   root.querySelectorAll('nav a').forEach((el,i)=>{text(el,['TODAY','BETS','MONEY'][i]);el.removeAttribute('href');event(el,'click',()=>route(['today','bets','money'][i]))});
  };
 });
}

function cropPhone(root){
 const phone=root.querySelector('div[style*="width: 390px"]');
 const content=phone.firstElementChild;
 root.replaceChildren(phone);root.style.cssText="position:relative;width:390px;min-height:844px;background:#fdfdfb;color:#111113;font-family:'Zen Kaku Gothic New',sans-serif";
 phone.style.cssText='position:relative;width:390px;min-height:844px;background:#fdfdfb;box-sizing:border-box';
 content.style.cssText='padding:18px;display:flex;flex-direction:column;gap:14px;min-height:844px;box-sizing:border-box';
 return content;
}
async function showCoverMarket(){
 await Paper.show('Flow',logic=>{
  logic.state.step=6;const a=Paper.active;a.autoHeight=true;a.dimensions=()=>({width:390,height:844});
  a.prepare=root=>{
   const m=Hunch.selected,b=Hunch.book&&(!Hunch.book.valid||Date.now()-Hunch.book.time<=60000)?Hunch.book:null;
   const body=cropPhone(root),p=Array.from(body.children);
   text(p[0].lastElementChild,'CASH —');
   const art=p[1];text(art.querySelector(':scope > span'),m.venue+' · PUBLIC MARKET');const back=art.querySelector('button');back._paperEvents={};back.replaceWith(back.cloneNode(true));const backButton=art.querySelector('button');event(backButton,'click',()=>{Hunch.selected=null;showLive()});const nav=document.createElement('div');nav.className='cover-market-nav';backButton.style.cssText='position:static;border:1.5px solid #111113;background:#fdfdfb;padding:8px 12px;font:700 12px JetBrains Mono';nav.append(backButton,watchButton(m));body.insertBefore(nav,art);
   text(p[2].children[0],m.category+' · '+m.date);text(p[2].children[1],m.title);
   Array.from(p[3].children).forEach((el,i)=>{text(el.children[0],m.sides[i]);text(el.children[1],detailPrice(i));text(el.children[2],detailStatus(i));el.style.background=i===Hunch.side?'#111113':'#fdfdfb';el.style.color=i===Hunch.side?'#fdfdfb':'#111113';el.setAttribute('role','button');el.tabIndex=0;el.setAttribute('aria-pressed',String(i===Hunch.side));const chooseSide=()=>{Hunch.side=i;requestBook();Paper.schedule()};event(el,'click',chooseSide);event(el,'keydown',e=>{if(e.key==='Enter')chooseSide()})});
   Array.from(p[4].children).slice(1).forEach((el,i)=>{const budget=[10,20,50][i];el.style.background=Hunch.budget===budget?'#111113':'#fdfdfb';el.style.color=Hunch.budget===budget?'#fdfdfb':'#111113';el.setAttribute('role','button');el.tabIndex=0;event(el,'click',()=>{Hunch.budget=budget;requestBook();Paper.schedule()})});
   text(p[5].children[0],'$'+Hunch.budget+' → '+(b?.valid?b.payout:'—'));text(p[5].children[1],b?.valid?(b.complete?'Full depth preview.':'Partial depth preview.')+' Spend preview: '+b.spent+'. Fees and execution can change.':b?'No verified executable quote.':Hunch.book?'Quote expired. Refresh preview.':'Loading live order book…');
   const cta=p[6];cta.replaceWith(cta.cloneNode(true));const button=body.lastElementChild;text(button,'REVIEW AN ORDER');event(button,'click',()=>sendNative({type:'trade',id:m.id,side:Hunch.side}));const refresh=document.createElement('button');refresh.className='paper-action';refresh.textContent='READ ONLY · REFRESH';event(refresh,'click',()=>{requestBook();Paper.schedule()});body.append(refresh);
   const terms=document.createElement('div');terms.style.cssText='border:1.5px solid #111113;padding:12px;font-size:12px;line-height:1.4';const title=document.createElement('b');title.textContent='HOW IT SETTLES';terms.append(title);termEntries(m.terms).forEach(([key,value])=>{const row=document.createElement('p');row.textContent=key+': '+value;terms.append(row)});const volume=document.createElement('p');volume.className='market-volume';volume.textContent='24H VOLUME '+dollars(marketVolume(m))+' · ASK DEPTH '+m.sides.map((name,i)=>name+' '+dollars(detailQuote(i)?.depth==null?null:Number(detailQuote(i).depth))).join(' / ');body.append(volume,terms);
  };
 });
}

function cashPanel(panel){
 panel.querySelectorAll('span').forEach(el=>{if(el.children.length)return;const value=el.textContent.trim();if(value==='$169.30')text(el,'—');else if(value.startsWith('IN 5 OPEN BETS'))text(el,'Open your wallet to view verified balances.');else if(value==='SAMPLE')text(el,'READ ONLY')});
 const buttons=panel.querySelectorAll('button');if(buttons[0]){text(buttons[0],'Add money');event(buttons[0],'click',()=>sendNative({type:'wallet'}))}if(buttons[1]){buttons[1].disabled=true;buttons[1].title='Withdrawals are not enabled'}
}
function recordPanel(panel){
 panel.querySelectorAll('polyline').forEach(el=>el.remove());
 panel.querySelectorAll('span').forEach(el=>{if(el.children.length)return;const t=el.textContent.trim();if(t.includes('SINCE OCT'))text(el,'YOUR RECORD');else if(/\$|\d/.test(t))text(el,'—');else if(t.startsWith('1 won'))text(el,'No trading history is connected.')});
 if(panel.children.length===3)text(panel.lastElementChild,'No trading history is connected.');
}
