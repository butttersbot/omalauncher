 'use strict';
window.Hunch={rows:[],chapter:'today',selected:null,side:0,budget:20,book:null,query:'',category:'ALL',error:false,updated:0};
window.sendNative=data=>window.SomaHunch?.post(JSON.stringify(data));
window.notice=text=>{const n=document.getElementById('notice');n.textContent=text;n.hidden=false;clearTimeout(window.noticeTimer);window.noticeTimer=setTimeout(()=>n.hidden=true,5000)};
function event(el,type,fn){el._paperEvents ||= {};el._paperEvents[type]=fn;el.addEventListener(type,fn)}
function text(el,value){if(el)el.textContent=value}
function action(label,fn){const b=document.createElement('button');b.type='button';b.className='paper-action';b.textContent=label;event(b,'click',fn);return b}
function empty(panel,heading,copy,button){if(panel.querySelector(':scope > svg')&&panel.lastElementChild.localName!=='svg')panel=panel.lastElementChild;const spans=Array.from(panel.querySelectorAll('span,b')).filter(x=>!x.children.length);const titleStyle=spans[0]?.getAttribute('style')||"font:700 11px 'JetBrains Mono'";panel.replaceChildren();const h=document.createElement('span');h.setAttribute('style',titleStyle);h.textContent=heading;const d=document.createElement('span');d.className='empty-copy';d.textContent=copy;panel.append(h,d);if(button)panel.append(button);return panel}
let bookTimer;function requestBook(){clearTimeout(bookTimer);if(!Hunch.selected)return;Hunch.book=null;const request={type:'book',id:Hunch.selected.id,side:Hunch.side,budget:Hunch.budget};bookTimer=setTimeout(()=>sendNative(request),150)}
function choose(m){Hunch.selected=m;Hunch.side=0;requestBook();showLive()}
function liveRows(){return Hunch.rows.filter(m=>(Hunch.category==='ALL'||m.category.toUpperCase()===Hunch.category)&&(!Hunch.query||(m.title+' '+m.category+' '+m.venue).toLowerCase().includes(Hunch.query.toLowerCase())))}
function marketButton(m){const b=action(m.title,()=>choose(m));const sub=document.createElement('small');sub.textContent=m.venue+' · '+m.date;b.append(sub);return b}
function listInto(panel,rows,max=5){panel.replaceChildren();panel.classList.add('paper-search-results');if(!rows.length){const d=document.createElement('p');d.className='empty-copy';d.textContent=Hunch.error?'Markets could not be loaded. Check your connection.':Hunch.rows.length?'No matching markets.':'Loading public markets…';panel.append(d)}const limit=max===100?(Hunch.limit||100):max;rows.slice(0,limit).forEach(m=>panel.append(marketButton(m)));if(max===100&&rows.length>limit)panel.append(action('Show more markets',()=>{Hunch.limit=limit+100;Paper.schedule()}))}
function updateFeed(feed){
 const s=Array.from(feed.children);if(s.length<14)return;
 const search=s[0].querySelector('input');if(search){search.value=Hunch.query;search.setAttribute('aria-label','Search markets');event(search,'input',e=>{Hunch.query=e.target.value;Hunch.limit=100;Paper.schedule()})}
 const cats=['ALL',...new Set(Hunch.rows.map(m=>m.category.toUpperCase()))];s[1].replaceChildren();s[1].style.overflowX='auto';cats.forEach(cat=>{const b=action(cat+' '+Hunch.rows.filter(m=>cat==='ALL'||m.category.toUpperCase()===cat).length,()=>{Hunch.category=cat;Hunch.limit=100;Paper.schedule()});b.style.cssText='flex:none;height:30px;min-height:30px;padding:0 9px;font:700 10px "JetBrains Mono";letter-spacing:.04em;'+(cat===Hunch.category?'border:0;background:#111113;color:#fdfdfb':'border:1px solid #111113');s[1].append(b)});
 const rows=liveRows(),featured=rows[0];
 // Keep the original stadium drawing, caption frame, ink, type and screen tone.
 const cover=s[2],caption=cover.lastElementChild;const cap=caption?.children;
 text(cover.querySelector(':scope > span'),'PUBLIC MARKETS · '+Hunch.rows.length+' LISTED');
 if(cap?.length){text(cap[0].children[0],featured?.title||'Public markets');text(cap[0].children[1],featured?(Hunch.error?'CACHED':'LISTED'):(Hunch.error?'OFFLINE':'LOADING'));text(cap[1],featured?featured.venue+' · '+featured.date:Hunch.error?'Connection unavailable':'Loading the latest listings…')}
 if(featured){cover.setAttribute('role','button');cover.tabIndex=0;event(cover,'click',()=>choose(featured));event(cover,'keydown',e=>{if(e.key==='Enter')choose(featured)})}
 const gut=s[3].querySelector('span');if(gut){text(gut.children[0],'GUT CHECK');text(gut.children[1],rows[1]?.title||'Know something the market doesn’t?');text(gut.children[2],'Open a market to inspect its live order book.')};if(rows[1])event(s[3],'click',()=>choose(rows[1]));
 empty(s[4],'LOOKS MISPRICED · VS OUTSIDE PRICES','Outside-price comparisons are not connected yet. No edge is claimed.');
 // Reuse the original row typography and separators, with listing identity instead of invented prices.
 const trending=s[5];text(trending.firstElementChild,'MARKETS · '+rows.length+' MATCHING');const grid=trending.lastElementChild;
 if(grid!==trending.firstElementChild){grid.replaceChildren();rows.slice(0,5).forEach((m,i)=>{const n=document.createElement('span');n.textContent=String(i+1).padStart(2,'0');const t=document.createElement('span');const b=document.createElement('b');b.textContent=m.title;const sub=document.createElement('span');sub.style.cssText='display:block;font:9px "JetBrains Mono";color:#565964';sub.textContent=m.category;t.append(b,sub);event(t,'click',()=>choose(m));t.setAttribute('role','button');const price=document.createElement('span');price.textContent='→';grid.append(n,t,price)})}
 empty(s[6],'SETTLES IN MINUTES','Settlement countdowns appear only when the venue provides a verified deadline.');
 const upcoming=empty(s[7],'MORE MARKETS',rows.length?'Open a listing for its exact venue terms.':'Waiting for public listings.');upcoming.append(...rows.slice(5,8).map(marketButton));
 empty(s[8],'MOVING TODAY','Price history is not connected yet. No movement is estimated.');
 empty(s[9],'YOUR WATCHLIST','No watchlist has been saved.');
 text(s[10].firstElementChild,'BIG QUESTIONS');const questions=s[10].lastElementChild,card=questions.firstElementChild;questions.replaceChildren();rows.slice(8,12).forEach(m=>{const box=card.cloneNode(true);text(box.children[0],m.date);text(box.children[1],m.title);text(box.children[2],'—');box.setAttribute('role','button');box.tabIndex=0;event(box,'click',()=>choose(m));event(box,'keydown',e=>{if(e.key==='Enter')choose(m)});questions.append(box)});if(!questions.children.length){const copy=document.createElement('span');copy.className='empty-copy';copy.textContent=Hunch.error?'Listings are unavailable.':'No more listings in this issue.';questions.append(copy)}
 empty(s[11],'PUBLIC LISTINGS','Listing dates are not provided by every venue.');s[11].append(...rows.slice(-2).map(marketButton));
 empty(s[12],'YOUR TEAMS','Team following is not connected yet.');text(s[13].lastElementChild,Hunch.error?'OFFLINE · TRY REFRESH':'PUBLIC MARKETS · FULL LIST UNDER SEARCH');
 if(Hunch.query||Hunch.category!=='ALL'){s.slice(2).forEach(e=>e.style.display='none');const results=document.createElement('div');results.className='paper-search-results';listInto(results,rows,100);feed.append(results)}
 feed.append(action('Refresh markets',()=>sendNative({type:'markets'})));
}
function coverPrepare(root){
 const top=root.children[0];text(top?.lastElementChild,'CASH —');const body=root.children[1];
 if(Hunch.chapter==='today') {const feed=body.querySelector('.noscroll > div');if(feed)updateFeed(feed)}
 else if(Hunch.chapter==='bets') {const panels=Array.from(body.children);if(panels[0]){const art=panels[0];art.style.background='radial-gradient(circle, rgba(17,17,19,.18) .6px, transparent .95px) 0 0 / 4px 4px, #fdfdfb';art.style.display='flex';art.style.flexDirection='column';art.style.justifyContent='center';art.style.padding='12px';empty(art,'MY BETS','No trading account is connected.');art.querySelectorAll('span').forEach(el=>{el.style.position='static';el.style.transform='none';el.style.width='auto'})}if(panels[2]){const tabs=panels[2].firstElementChild;text(tabs.lastElementChild,'NOT CONNECTED');panels[2].replaceChildren(tabs);const p=document.createElement('p');p.className='empty-copy';p.textContent='Your real positions and settled bets will appear here once trading is connected.';panels[2].append(p)}}
 else {const panels=Array.from(body.children);cashPanel(panels[0]);empty(panels[1],'Daily limit','Betting permissions are not enabled.');recordPanel(panels[2])}
}
async function showLive(){
 const wide=window.innerWidth>=600;Hunch.layoutWide=wide;
 if(Hunch.selected)return wide?showMarket(true):showCoverMarket();
 if(wide)return showWide();
 await Paper.show('CoverBets',logic=>{
  logic.state.tab=Hunch.chapter;const a=Paper.active;
  a.values=v=>({...v,zoomed:false,notZoomed:true,rows:[],settled:[],tot:{stake:'—',worth:'—',pays:'—'},next:{left:'—',title:'No connected bets',sub:'Read-only markets',chance:'—',pick:()=>{}},segs:v.segs.map((s,i)=>({...s,label:i?'SETTLED':'OPEN'})),tabs:v.tabs.map((t,i)=>({...t,hasBadge:false,badge:'',aria:['Today','Bets','Money'][i],sub:i===0?'PUBLIC MARKETS':i===1?'NO ACCOUNT':'NOT CONNECTED',pick:()=>route(['today','bets','money'][i])}))});
  const height=()=>{const view=document.getElementById('viewport');return view.clientWidth>0?Math.max(300,view.clientHeight/(view.clientWidth/390)):844};
  a.prepare=root=>{coverPrepare(root);root.style.height=height()+'px';root.children[1].style.height=Math.max(100,height()-154)+'px'};
  a.dimensions=()=>({width:390,height:height()});
 });
}
function route(chapter){Hunch.chapter=chapter;Hunch.selected=null;sendNative({type:'route',chapter});showLive()}
async function showMarket(wide){
 const m=Hunch.selected;
 await Paper.show('Main',logic=>{
  const a=Paper.active;const ordered=[m,...Hunch.rows.filter(x=>x.id!==m.id)];const group=x=>x.category==='Sports'?'tonight':x.category==='Prices'?'sunday':'later';const bind=(rows,key)=>rows.map((r,i)=>({...r,pick:()=>choose(ordered.filter(x=>group(x)===key)[i])}));logic.state.sel=m.id;logic.state.amt=Hunch.budget;logic.state.side=Hunch.side;
  logic.markets=()=>ordered.map(x=>({id:x.id,group:group(x),title:x.title,sub:x.category+' · '+x.venue,label:x.venue+' · '+x.date,heading:x.title,question:x.sides.join(' / '),rules:Object.entries(x.terms).map(([k,v])=>k+': '+v).join(' · '),tag:'READ ONLY',lo:0,hi:0,sides:x.sides.map(name=>({name,price:1,chance:'—',ifText:'If '+name+' settles in your favor',fill:'Loading live order book…',venue:x.venue}))}));
  a.values=v=>{const received=Hunch.book;const b=received&&(!received.valid||Date.now()-received.time<=60000)?received:null;return {...v,tonight:bind(v.tonight,'tonight'),sunday:bind(v.sunday,'sunday'),later:bind(v.later,'later'),sides:v.sides.map((s,i)=>({...s,cents:i===Hunch.side&&b?.valid?(Number(b.ask)*100).toFixed(2)+'¢ ASK':'NO VERIFIED QUOTE',chance:'—',pick:()=>{Hunch.side=i;logic.state.side=i;requestBook();Paper.schedule()}})),amt:Hunch.budget,amtLabel:'$'+Hunch.budget,payout:b?.valid?b.payout:'—',profit:'—',ifText:'Depth preview · not an order',ctaVerb:'READ ONLY',side:{...v.side,fill:b?.valid?(b.complete?'Full preview at current depth.':'Partial fill at current depth.'):b?'No valid executable quote.':received?'Quote expired. Refresh preview.':'Loading live order book…',venue:m.venue},chips:v.chips.map((c,i)=>({...c,pick:()=>{Hunch.budget=[5,10,20,50,100][i];logic.state.amt=Hunch.budget;requestBook();Paper.schedule()}})),onRange:e=>{Hunch.budget=Number(e.target.value);logic.state.amt=Hunch.budget;requestBook();Paper.schedule()}}};
  a.prepare=root=>{const copy={'TONIGHT · SETTLES 11 PM PT':'SPORTS','SUNDAY · OCT 25':'PRICES','LATER':'EVENTS','EMPTY MARKETS HIDDEN · SHOW':'READ-ONLY PUBLIC DATA','EMPTY MARKETS HIDDEN ·':'READ-ONLY PUBLIC DATA','SHOW':'REFRESH'};root.querySelectorAll('span,button').forEach(el=>{if(!el.children.length&&copy[el.textContent.trim()]){el.textContent=copy[el.textContent.trim()];if(el.tagName==='BUTTON')el.disabled=true}});root.querySelectorAll('div[style*="left: 22px"][style*="height: 150px"],div[style*="left: 22px"][style*="height: 208px"]').forEach(p=>{const list=p.lastElementChild;list.style.bottom='10px';list.style.overflowY='auto'});root.querySelectorAll('div[style*="left: 22px"][style*="height: 236px"]').forEach(p=>p.style.overflowY='auto');root.querySelectorAll('div[style*="height: 292px"],div[style*="width: 236px"]').forEach(p=>p.style.overflowY='auto');root.querySelectorAll('a').forEach(el=>{el.removeAttribute('href');text(el.firstElementChild,'DEPTH PREVIEW');event(el,'click',()=>{requestBook();Paper.schedule()})});const header=root.querySelector('div[style*="height: 92px"]');if(header){header.querySelectorAll('span').forEach(el=>{if(el.textContent.startsWith('$'))text(el,'—')});const brand=header.firstElementChild;event(brand,'click',()=>{Hunch.selected=null;showLive()});brand.setAttribute('role','button');brand.setAttribute('aria-label','Back to Today');brand.tabIndex=0;event(brand,'keydown',e=>{if(e.key==='Enter'){Hunch.selected=null;showLive()}})} };
  a.dimensions=()=>({width:880,height:760});
 });
}

let authState=null;
async function showAccount(data){
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
   content.replaceChildren(card);if(art){art.style.flex='none';art.style.height=state.signed?'120px':'164px';content.append(art)}
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
  a.autoHeight=true;a.maxScale=1;a.dimensions=()=>({width:390,height:844});
 });
}
async function showWide(){
 const name=Hunch.chapter==='today'?'Discover':Hunch.chapter==='bets'?'Bets':'Money';
 await Paper.show(name,logic=>{
  const a=Paper.active;
  a.values=v=>({...v,watch:[],edges:[],card:{...v.card,q:liveRows()[1]?.title||liveRows()[0]?.title||'Know something the market doesn’t?',when:liveRows()[1]?.date||'PUBLIC MARKETS'},num:1,total:Hunch.rows.length,found:'—',hidden:true,revealed:false,reveal:()=>{const m=liveRows()[1]||liveRows()[0];if(m)choose(m)},rows:[],settled:[],tot:{stake:'—',worth:'—',pays:'—'},b:Object.fromEntries(Object.keys(v.b||{}).map(k=>[k,k==='title'?'No connected bets':k==='poly'?'':'—'])),tabs:v.tabs?.map((t,i)=>({...t,label:i?'SETTLED':'OPEN'}))});
  a.prepare=root=>{
   const panels=Array.from(root.children).filter(x=>x.style.position==='absolute'&&parseFloat(x.style.width)>100);
   if(name==='Discover'){
    const search=root.querySelector('input[type=search]');search.value=Hunch.query;event(search,'input',e=>{Hunch.query=e.target.value;Hunch.limit=100;Paper.schedule()});text(search.parentElement.lastElementChild,Hunch.rows.length+' MARKETS');
    const cover=panels.find(p=>p.style.top==='82px');const rows=liveRows();
    if(cover){text(cover.firstElementChild,'PUBLIC MARKETS');const title=cover.querySelector('div[style*="rotate"]');text(title?.children[0],rows[0]?.venue||'HUNCH');text(title?.children[1],rows[0]?.title||'Public markets');if(title)title.style.whiteSpace='normal';const link=cover.querySelector('a');text(link?.firstElementChild,rows[0]?.date||'Loading listings…');if(link){link.removeAttribute('href');event(link,'click',()=>rows[0]&&choose(rows[0]))}const surprise=cover.querySelector('button');if(surprise)event(surprise,'click',()=>rows.length&&choose(rows[Math.floor(Math.random()*rows.length)]))}
    const watch=panels.find(p=>p.style.top==='286px');if(watch)empty(watch.lastElementChild,'FROM YOUR SOMA WATCHLIST','No watchlist has been connected.');
    const edge=panels.find(p=>p.style.top==='466px');if(edge)empty(edge.lastElementChild,'LOOKS MISPRICED · VS OUTSIDE PRICES','Outside-price comparisons are not connected yet. No edge is claimed.');
    const gut=panels.find(p=>p.style.left==='462px'&&p.style.top==='18px');if(gut){text(gut.children[3],'Guess first. Inspect the live order book next.');gut.querySelectorAll('span').forEach(el=>{if(el.textContent==='THE PRICES ARE UNDER HERE')text(el,'CHECK THE LIVE ORDER BOOK')})}
    const listings=panels.find(p=>p.style.left==='462px'&&p!==gut);if(listings){listings.style.overflowY='auto';listInto(listings,rows,100)}
   }else{
    panels.forEach((panel,i)=>{if(name==='Money'&&i===0){cashPanel(panel);return}if(name==='Money'&&i===3){recordPanel(panel);return}if(name==='Bets'&&i===0){text(panel.children[0].lastElementChild,'NOT CONNECTED');return}const inner=panel.querySelector(':scope > svg')?panel.lastElementChild:panel;const headings=name==='Money'?['CASH YOU CAN BET OR TAKE OUT','Daily limit','YOUR WALLET','YOUR RECORD','ACTIVITY']:['MY BETS','YOUR POSITIONS','SETTLED BETS','POSITION DETAILS'];empty(inner,headings[i]||'MY BETS',name==='Money'?'No wallet or trading account is connected.':'No real positions or settled bets are connected.',name==='Money'&&i===2?action('Sign in to Hunch',()=>sendNative({type:'account'})):null)})
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
   const art=p[1];text(art.querySelector(':scope > span'),m.venue+' · PUBLIC MARKET');const back=art.querySelector('button');back._paperEvents={};back.replaceWith(back.cloneNode(true));const backButton=art.querySelector('button');event(backButton,'click',()=>{Hunch.selected=null;showLive()});
   text(p[2].children[0],m.category+' · '+m.date);text(p[2].children[1],m.title);
   Array.from(p[3].children).forEach((el,i)=>{text(el.children[0],m.sides[i]);text(el.children[1],'—');text(el.children[2],i===Hunch.side&&b?.valid?(Number(b.ask)*100).toFixed(2)+'¢ ASK':'NO VERIFIED QUOTE');el.style.background=i===Hunch.side?'#111113':'#fdfdfb';el.style.color=i===Hunch.side?'#fdfdfb':'#111113';el.setAttribute('role','button');el.tabIndex=0;el.setAttribute('aria-pressed',String(i===Hunch.side));const chooseSide=()=>{Hunch.side=i;requestBook();Paper.schedule()};event(el,'click',chooseSide);event(el,'keydown',e=>{if(e.key==='Enter')chooseSide()})});
   Array.from(p[4].children).slice(1).forEach((el,i)=>{const budget=[10,20,50][i];el.style.background=Hunch.budget===budget?'#111113':'#fdfdfb';el.style.color=Hunch.budget===budget?'#fdfdfb':'#111113';el.setAttribute('role','button');el.tabIndex=0;event(el,'click',()=>{Hunch.budget=budget;requestBook();Paper.schedule()})});
   text(p[5].children[0],'$'+Hunch.budget+' → '+(b?.valid?b.payout:'—'));text(p[5].children[1],b?.valid?(b.complete?'Full depth preview.':'Partial depth preview.')+' Spend preview: '+b.spent+'. Fees and execution can change.':b?'No verified executable quote.':Hunch.book?'Quote expired. Refresh preview.':'Loading live order book…');
   const cta=p[6];cta.replaceWith(cta.cloneNode(true));const button=body.lastElementChild;text(button,'READ ONLY · REFRESH');event(button,'click',()=>{requestBook();Paper.schedule()});
   const terms=document.createElement('div');terms.style.cssText='border:1.5px solid #111113;padding:12px;font-size:12px;line-height:1.4';const title=document.createElement('b');title.textContent='HOW IT SETTLES';terms.append(title);Object.entries(m.terms).forEach(([key,value])=>{const row=document.createElement('p');row.textContent=key+': '+value;terms.append(row)});body.append(terms);
  };
 });
}

function cashPanel(panel){
 panel.querySelectorAll('span').forEach(el=>{if(el.children.length)return;const value=el.textContent.trim();if(value==='$169.30')text(el,'—');else if(value.startsWith('IN 5 OPEN BETS'))text(el,'No wallet is connected.');else if(value==='SAMPLE')text(el,'READ ONLY')});
 const buttons=panel.querySelectorAll('button');if(buttons[0]){text(buttons[0],'Sign in to Hunch');event(buttons[0],'click',()=>sendNative({type:'account'}))}if(buttons[1]){buttons[1].disabled=true;buttons[1].title='Withdrawals are not enabled'}
}
function recordPanel(panel){
 panel.querySelectorAll('polyline').forEach(el=>el.remove());
 panel.querySelectorAll('span').forEach(el=>{if(el.children.length)return;const t=el.textContent.trim();if(t.includes('SINCE OCT'))text(el,'YOUR RECORD');else if(/\$|\d/.test(t))text(el,'—');else if(t.startsWith('1 won'))text(el,'No trading history is connected.')});
 if(panel.children.length===3)text(panel.lastElementChild,'No trading history is connected.');
}
