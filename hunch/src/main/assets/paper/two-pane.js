'use strict';
// Approved directly by Boss, 2026-10-26: hunch-today / hunch-market /
// hunch-bets (landscape v5) / hunch-today-portrait (upright v6). No order execution or demo funds.
const paneScroll=new Map();let paneShown=null;
function paneNode(tag,cls,copy){const el=document.createElement(tag);if(cls)el.className=cls;if(copy!=null)el.textContent=copy;return el}
function paneButton(copy,cls,fn){const b=paneNode('button',cls,copy);b.type='button';event(b,'click',fn);return b}
function paneHeading(copy){return paneNode('div','listhead',copy)}
function paneBox(title,copy){const box=paneNode('section','box');box.append(paneNode('h4','',title),paneNode('p','',copy));return box}
function paneSize(){const v=document.getElementById('viewport');return {width:v.clientWidth||window.innerWidth,height:v.clientHeight||window.innerHeight||704}}
function paneIsUpright(){const orientation=window.screen.orientation?.type;return orientation?orientation.startsWith('portrait'):paneSize().height>paneSize().width}
function paneStateKey(){return Hunch.selected?.id||(paneIsUpright()?Hunch.chapter:'bets')}
function showTwoPane(){if(Paper.active?.name==='TwoPane'){Paper.render();return}paneShown=null;Paper.custom('TwoPane',buildTwoPane,paneSize,root=>{const key=paneStateKey();if(key!==paneShown){root.querySelector('.right,.cvbody').scrollTop=paneScroll.get(key)||0;paneShown=key}})}
function paneChoose(m){choose(m)}
function paneMarkets(){return buildDiscovery()}
function paneBets(){
 const inner=paneNode('div','inner'),heading=paneNode('div','bets-heading');
 heading.append(paneNode('h2','h1','My bets'),paneButton('OPEN WALLET','rtab',()=>sendNative({type:'wallet'})));inner.append(heading,paneBox('NO TRADING ACCOUNT','No trading account is connected. Real bets and trading balances are not connected yet.'));
 inner.append(paneHeading('SETTLES NEXT'),paneNode('p','empty','No connected bets to show.'));
 inner.append(paneHeading('OPEN BETS'),paneNode('p','empty','Choose a market to inspect its live order book and review an order.'));
 inner.append(paneHeading('SETTLED'),paneNode('p','empty','No settled bets are connected.'));
 return inner;
}
function paneSlip(upright=false){
 const m=Hunch.selected,fragment=document.createDocumentFragment(),tabs=paneNode('div','rtabs');
 tabs.append(paneButton(upright?'← BACK':'← MY BETS','rtab back',()=>{Hunch.selected=null;clearTimeout(bookTimer);showLive()}));
 const label=paneNode('span','rtab on mk');label.append(paneNode('span','',m.title));tabs.append(label);if(upright){const art=paneNode('div','cvart');art.innerHTML=HUNCH_STADIUM;art.append(paneNode('span','tag',m.category+' · PUBLIC MARKET'));fragment.append(tabs,art)}else fragment.append(tabs);tabs.append(watchButton(m));
 const inner=paneNode('div','inner');inner.append(paneNode('div','kicker',m.category+' · '+m.venue+' · '+m.date),paneNode('h2','h1',m.title));
 const received=Hunch.book,b=received&&(!received.valid||Date.now()-received.time<=60000)?received:null;
 const sides=paneNode('div','sides');m.sides.forEach((name,i)=>{const quoted=i===Hunch.side&&b?.valid;const button=paneButton('','sidebtn'+(Hunch.side===i?' on':''),()=>{Hunch.side=i;requestBook();Paper.schedule()});button.setAttribute('aria-pressed',String(Hunch.side===i));button.append(paneNode('b','',name),paneNode('span','c',detailPrice(i)),paneNode('small','',detailStatus(i)));sides.append(button)});inner.append(sides);
 const amounts=paneNode('div','putin');amounts.append(paneNode('span','','PUT IN'));[5,10,20,50].forEach(amt=>{const b=paneButton('$'+amt,'amt'+(amt===Hunch.budget?' on':''),()=>{Hunch.budget=amt;requestBook();Paper.schedule()});b.setAttribute('aria-pressed',String(amt===Hunch.budget));amounts.append(b)});inner.append(amounts);
 inner.append(paneNode('div','payout','$'+Hunch.budget+' → '+(b?.valid?b.payout:'—')));
 const status=b?.valid?(b.complete?'Full preview at current depth.':'Partial fill at current depth.'):b?'No verified executable quote.':received?'Quote expired. Refresh preview.':'Loading live order book…';
 inner.append(paneNode('p','cap',status+' Depth preview only; this does not place an order.'));
 inner.append(paneButton('REVIEW AN ORDER','place',()=>sendNative({type:'trade',id:Hunch.selected.id,side:Hunch.side})),paneButton('READ ONLY · REFRESH','refresh',()=>{requestBook();Paper.schedule()}),paneNode('p','after','Opening the order ticket sends nothing. Review your exact size, then Confirm to sign.'));
 inner.append(paneNode('p','market-volume','24H VOLUME '+dollars(marketVolume(m))+' · ASK DEPTH '+m.sides.map((name,i)=>name+' '+dollars(detailQuote(i)?.depth==null?null:Number(detailQuote(i).depth))).join(' / ')));
 const rules=paneNode('section','box');rules.append(paneNode('h4','','HOW IT SETTLES'));termEntries(m.terms).forEach(([k,v])=>rules.append(paneNode('p','',k+': '+v)));inner.append(rules);fragment.append(inner);return fragment;
}
function paneMetrics(){const strip=paneNode('div','mstrip header-metrics');strip.setAttribute('aria-label','Trading summary');['CASH TO BET','IN PLAY','COULD WIN','P&L'].forEach(title=>{const cell=paneNode('div');cell.append(paneNode('small','',title),paneNode('b','','—'));strip.append(cell)});return strip}
function buildTwoPane(){
 const old=document.querySelector('.hunch-two-pane .right,.hunch-two-pane .cvbody');if(old&&paneShown!==null)paneScroll.set(paneShown,old.scrollTop);
 const size=paneSize();if(paneIsUpright())return buildUpright(size);
 const root=paneNode('div','hunch-two-pane');root.style.width=size.width+'px';root.style.height=size.height+'px';
 const top=paneNode('header','top');top.append(paneNode('h1','brand','HUNCH'),paneMetrics());root.append(top);
 const panes=paneNode('div','panes side');
 const left=paneNode('section','pane left');left.setAttribute('aria-label','Markets');left.append(paneMarkets());
 const right=paneNode('section','pane right');right.setAttribute('aria-label',Hunch.selected?'Market details':'My bets');right.append(Hunch.selected?paneSlip():paneBets());panes.append(left,right);root.append(panes);return root;
}

const HUNCH_STADIUM='<svg viewBox="0 0 400 220" preserveAspectRatio="xMidYMid slice"><defs><pattern id="d" width="7" height="7" patternUnits="userSpaceOnUse"><rect width="7" height="7" fill="#d9dadd"/><circle cx="3.5" cy="3.5" r="1.2" fill="#7d8089"/></pattern></defs><rect width="400" height="220" fill="url(#d)"/><polygon points="62,32 30,170 100,170" fill="#fdfdfb" opacity=".55"/><polygon points="338,32 300,170 370,170" fill="#fdfdfb" opacity=".55"/><rect x="60" y="22" width="4" height="150" fill="#111113"/><rect x="336" y="22" width="4" height="150" fill="#111113"/><rect x="48" y="14" width="28" height="14" fill="#fdfdfb" stroke="#111113" stroke-width="2.5"/><rect x="324" y="14" width="28" height="14" fill="#fdfdfb" stroke="#111113" stroke-width="2.5"/><path d="M0 160 Q200 90 400 160 V220 H0Z" fill="#111113"/><path d="M20 156 Q200 100 380 156" fill="none" stroke="#fdfdfb" stroke-width="1.5" stroke-dasharray="5 5"/><polygon points="130,220 160,196 240,196 270,220" fill="#fdfdfb"/><line x1="200" y1="196" x2="200" y2="220" stroke="#111113" stroke-width="1.5"/></svg>';

function uprightMoney(){
 const inner=paneNode('div','inner');inner.append(paneNode('h2','h1','Money'));
 const cash=paneBox('CASH YOU CAN BET OR TAKE OUT','—');cash.append(paneNode('p','cap','Trading cash is not connected. Open your wallet for verified balances.'));
 const actions=paneNode('div','mlinks');actions.append(paneButton('Open your wallet','rtab',()=>sendNative({type:'wallet'})));const withdraw=paneButton('TAKE OUT','rtab',()=>{});withdraw.disabled=true;actions.append(withdraw);cash.append(actions);inner.append(cash,paneBox('ORDER CONFIRMATION','No app spending caps. Every order needs your review and signature.'),paneBox('YOUR RECORD','No trading history is connected.'),paneBox('ACTIVITY','No trading activity is connected.'));return inner;
}
function buildUpright(size){
 const root=paneNode('div','hunch-two-pane upright');root.style.width=size.width+'px';root.style.height=size.height+'px';
 const frame=paneNode('div','cvz'),top=paneNode('header','cvtop');top.append(paneNode('h1','brand','HUNCH'),paneNode('span','cvcash','CASH —'));frame.append(top);
 const body=paneNode('section','pane cvbody');body.setAttribute('aria-label',Hunch.selected?'Market details':Hunch.chapter==='today'?'Markets':Hunch.chapter==='money'?'Money':'My bets');
 body.append(Hunch.selected?paneSlip(true):Hunch.chapter==='today'?paneMarkets(true):Hunch.chapter==='money'?uprightMoney():paneBets());frame.append(body);
 if(!Hunch.selected){const tabs=paneNode('nav','cvtabs');tabs.setAttribute('aria-label','Hunch pages');[['today','TODAY','PUBLIC MARKETS'],['bets','BETS','NOT CONNECTED'],['money','MONEY','YOUR WALLET']].forEach(([key,title,sub])=>{const b=paneButton('','cvtab'+(Hunch.chapter===key?' on':''),()=>route(key));b.setAttribute('aria-label',title[0]+title.slice(1).toLowerCase());b.setAttribute('aria-current',Hunch.chapter===key?'page':'false');b.append(paneNode('b','',title),paneNode('small','',sub));tabs.append(b)});frame.append(tabs)}root.append(frame);return root;
}
