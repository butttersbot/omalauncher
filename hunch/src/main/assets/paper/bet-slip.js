'use strict';
// Inert drafts live in native preferences; this component never signs or submits an order.
const BetSlip={open:false,mode:'compose',amount:'20.00',drafts:[],pending:null,error:'',requestedAt:0};
function slipCents(value,up=false){
 const match=/^(\d+)(?:\.(\d+))?$/.exec(String(value??''));if(!match)return null;
 const fraction=match[2]||'';return BigInt(match[1])*100n+BigInt((fraction+'00').slice(0,2))+(up&&/[1-9]/.test(fraction.slice(2))?1n:0n);
}
function slipMoney(value,up=false){const cents=slipCents(value,up);return cents===null?'—':formatSlipCents(cents)}
function formatSlipCents(cents){const negative=cents<0n,n=negative?-cents:cents;return (negative?'−':'')+'$'+(n/100n).toLocaleString('en-US')+'.'+String(n%100n).padStart(2,'0')}
function slipTotals(rows,risk='budget',payout='estimatedPayout',up=false){
 let risks=0n,pays=0n;for(const row of rows){const a=slipCents(row[risk],up),b=slipCents(row[payout]);if(a===null||b===null)return null;risks+=a;pays+=b}return {risk:risks,payout:pays,profit:pays-risks};
}
function slipNode(tag,cls,copy){return paneNode(tag,cls,copy)}
function slipButton(copy,fn,primary=false){const b=paneButton(copy,'slip-button'+(primary?' primary':''),fn);return b}
function betQuote(){if(!/^\d{1,12}(?:\.\d{0,2})?$/.test(BetSlip.amount)||Number(BetSlip.amount)<=0||Number(BetSlip.amount)!==Hunch.budget)return null;const q=Hunch.books?.[Hunch.side];return q?.valid&&q.complete&&q.budget===Hunch.budget&&Date.now()-q.time<=60000&&Date.now()-q.time>=-5000?q:null}
function openBet(side){Hunch.side=side;BetSlip.open=true;BetSlip.mode='compose';BetSlip.error='';Hunch.budget=Number(BetSlip.amount);BetSlip.requestedAt=Date.now();requestBook();Paper.schedule();renderBetOverlay()}
function closeBetSheet(){BetSlip.open=false;BetSlip.error='';renderBetOverlay()}
function browseMoreBets(){closeBetSheet();Hunch.selected=null;showLive()}
function setBetAmount(value){BetSlip.amount=value;BetSlip.error='';if(/^\d{1,12}(?:\.\d{0,2})?$/.test(value)&&Number(value)>0){Hunch.budget=Number(value);BetSlip.requestedAt=Date.now();requestBook()}else Hunch.book=null;Paper.schedule();renderBetOverlay()}
function saveBet(place){
 const m=Hunch.selected,q=betQuote();if(!m||!q||BetSlip.pending)return;
 if(slipCents(BetSlip.amount)===null||Number(BetSlip.amount)<=0){BetSlip.error='Enter a positive amount.';renderBetOverlay();return}
 BetSlip.pending={key:m.id+':'+Hunch.side,place};BetSlip.error='';renderBetOverlay();sendNative({type:'draftAction',action:'upsert',id:m.id,side:Hunch.side,budget:BetSlip.amount});
}
function receiveBetDrafts(data){
 BetSlip.drafts=data.rows||[];BetSlip.error=data.error||'';
 const pending=BetSlip.pending;BetSlip.pending=null;
 if(pending&&!data.error){const draft=BetSlip.drafts.find(d=>d.key===pending.key);if(pending.place&&draft)placeSlip([draft.draftId]);else browseMoreBets()}
 renderBetOverlay();
}
function placeSlip(ids){if(!ids.length)return;BetSlip.open=false;renderBetOverlay();sendNative({type:'placeBets',ids})}
function editSlipBet(draft){const m=Hunch.rows.find(m=>m.id===draft.marketId);if(!m){BetSlip.error='This market is no longer listed. Remove this bet from your slip.';renderBetOverlay();return}Hunch.selected=m;Hunch.side=draft.side;Hunch.books={};BetSlip.amount=draft.budget;Hunch.budget=Number(draft.budget);BetSlip.mode='compose';BetSlip.open=true;requestBook();showLive()}
function sheetHeader(title,close){const header=slipNode('header','slip-header');header.append(slipNode('h2','',title),slipButton('Close',close));return header}
function riskReturn(risk,payout,caption='Estimated payout if right · includes your stake'){
 const block=slipNode('div','risk-return');const a=slipNode('div'),b=slipNode('div');a.append(slipNode('small','','RISK UP TO'),slipNode('b','',risk));b.append(slipNode('small','','PAYOUT IF RIGHT'),slipNode('b','',payout));block.append(a,slipNode('span','risk-arrow','→'),b);return caption?[block,slipNode('p','slip-note',caption)]:[block]
}
function composeBet(){
 const m=Hunch.selected,body=slipNode('div','slip-content');if(!m)return body;
 body.append(sheetHeader('Your bet',closeBetSheet),slipNode('p','slip-market',m.title),slipNode('p','slip-side',m.sides[Hunch.side]));
 const field=slipNode('label','stake-label','Amount to risk'),input=slipNode('input');input.type='text';input.inputMode='decimal';input.setAttribute('aria-label','Amount to risk');input.value=BetSlip.amount;event(input,'input',e=>setBetAmount(e.target.value));field.append(input);body.append(field);
 const presets=slipNode('div','stake-presets');[5,10,20,50,100].forEach(amount=>{const b=slipButton('$'+amount,()=>setBetAmount(amount.toFixed(2)));b.setAttribute('aria-pressed',String(Number(BetSlip.amount)===amount));presets.append(b)});body.append(presets);
 const q=betQuote();body.append(...riskReturn(slipMoney(BetSlip.amount),q?q.payout:'—'));
 const valid=q&&Number(q.payout?.replace('$',''))>=1;
 body.append(slipNode('p','slip-note',q?'Estimate before fees and whole-share rounding. Exact risk is checked before confirmation.':Hunch.books?.[Hunch.side]?.valid?'Not enough depth for this amount. Try a smaller stake.':Hunch.books?.[Hunch.side]?.empty?'There are no executable asks for this outcome.':Hunch.books?.[Hunch.side]?.error?'The quote could not be loaded. Try refreshing.':'Refreshing the selected outcome quote…'));
 if(!q)body.append(slipButton('Refresh quote',()=>setBetAmount(BetSlip.amount)));
 if(BetSlip.error)body.append(slipNode('p','slip-error',BetSlip.error));
 const actions=slipNode('div','slip-actions'),add=slipButton('Add to slip',()=>saveBet(false)),place=slipButton('Place bet',()=>saveBet(true),true);add.disabled=place.disabled=!valid||!!BetSlip.pending;actions.append(add,place);body.append(actions);
 if(BetSlip.drafts.length)body.append(slipButton('View slip · '+BetSlip.drafts.length,()=>{BetSlip.mode='queue';renderBetOverlay()}));
 body.append(slipButton('Advanced order',()=>{closeBetSheet();sendNative({type:'trade',id:m.id,side:Hunch.side})}));return body;
}
function slipTable(rows,review=false,editable=true){
 const table=slipNode('table','slip-table'),head=slipNode('thead'),tr=slipNode('tr');['Bet',review?'Max risk':'Risk',review?'Payout if right':'Est. payout'].forEach(x=>tr.append(slipNode('th','',x)));head.append(tr);table.append(head);
 const tbody=slipNode('tbody');rows.forEach(row=>{const r=slipNode('tr'),name=slipNode('td');name.append(slipNode('b','',row.title),slipNode('small','',row.label));if(review)name.append(slipNode('small','',row.shares+' shares · fee ceiling '+slipMoney(row.fees,true)));else if(editable){const buttons=slipNode('div','draft-actions');buttons.append(slipButton('Edit',()=>editSlipBet(row)),slipButton('Remove',()=>sendNative({type:'draftAction',action:'remove',draftId:row.draftId})));name.append(buttons)}r.append(name,slipNode('td','',slipMoney(review?row.risk:row.budget,review)),slipNode('td','',slipMoney(review?row.payout:row.estimatedPayout)));tbody.append(r)});table.append(tbody);return table;
}
function queuedBets(){
 const body=slipNode('div','slip-content slip-queue'),rows=BetSlip.drafts;const handle=slipButton('',closeBetSheet);handle.className='slip-grab';handle.setAttribute('aria-label','Dismiss bet slip');body.append(handle);
 if(!rows.length){body.append(slipNode('p','','Your slip is empty. Choose Yes or No on a market.'),slipButton('Find a bet',browseMoreBets));return body}
 if(rows.length>1){const list=slipNode('div','slip-table-scroll');list.append(slipTable(rows));body.append(list)}else{const row=rows[0];body.append(slipNode('p','slip-market',row.title),slipNode('p','slip-side',row.label),...riskReturn(slipMoney(row.budget),slipMoney(row.estimatedPayout),null));const acts=slipNode('div','draft-actions');acts.append(slipButton('Edit',()=>editSlipBet(row)),slipButton('Remove',()=>sendNative({type:'draftAction',action:'remove',draftId:row.draftId})));body.append(acts)}
 const totals=slipTotals(rows);if(rows.length>1&&totals){body.append(...riskReturn(formatSlipCents(totals.risk),formatSlipCents(totals.payout),null))}
 if(BetSlip.error)body.append(slipNode('p','slip-error',BetSlip.error));
 const actions=slipNode('div','slip-actions');actions.append(slipButton('Add another bet',browseMoreBets),slipButton(rows.length>1?'Place all · '+rows.length+' bets':'Place bet',()=>placeSlip(rows.map(d=>d.draftId)),true));body.append(actions);return body;
}
function slipRegion(){const pane=document.querySelector('.hunch-two-pane .right');return pane?.getBoundingClientRect()||document.getElementById('viewport').getBoundingClientRect()}
window.renderBetOverlay=()=>{
 const overlay=document.getElementById('bet-overlay'),launcher=document.getElementById('bet-launcher');if(!overlay||!launcher)return;
 if(accountMode||design){overlay.hidden=launcher.hidden=true;return}
 const region=slipRegion();overlay.style.left=region.left+'px';overlay.style.top=region.top+'px';overlay.style.width=region.width+'px';overlay.style.height=region.height+'px';overlay.hidden=!BetSlip.open;
 if(BetSlip.open){const fresh=slipNode('section','slip-sheet'+(BetSlip.mode==='queue'?' slip-queue-sheet':''));fresh.setAttribute('role','dialog');fresh.setAttribute('aria-label',BetSlip.mode==='compose'?'Bet controls':'Bet slip');fresh._paperKey=BetSlip.mode;fresh.append(BetSlip.mode==='compose'?composeBet():queuedBets());if(overlay.firstChild)Paper.morph(overlay.firstChild,fresh);else overlay.append(fresh)}else overlay.replaceChildren();
 launcher.hidden=!BetSlip.drafts.length||BetSlip.open;const totals=slipTotals(BetSlip.drafts),tabs=document.querySelector('nav[aria-label="Hunch pages"]');const bottom=tabs?.getBoundingClientRect().top||region.bottom;
 launcher.style.left=(region.left+18)+'px';launcher.style.width=Math.max(100,region.width-36)+'px';launcher.style.bottom=Math.max(8,innerHeight-bottom+8)+'px';
 const fresh=slipButton((BetSlip.drafts.length>1?'Bet slip · '+BetSlip.drafts.length+' bets':'Your bet')+(totals?' · Risk '+formatSlipCents(totals.risk):''),()=>{BetSlip.open=true;BetSlip.mode='queue';renderBetOverlay()},true);
 if(launcher.firstChild)Paper.morph(launcher.firstChild,fresh);else launcher.append(fresh);
};
let batchState=null;
async function showBatch(data){batchState=data;if(Paper.active?.name==='BatchSheet'){Paper.schedule();return}Paper.custom('BatchSheet',buildBatchSheet,()=>({width:innerWidth,height:innerHeight}));}
function buildBatchSheet(){
 const state=batchState,root=slipNode('div','native-batch'),body=slipNode('div','slip-content');root.style.width=innerWidth+'px';root.style.height=innerHeight+'px';
 const send=(action,extra={})=>sendNative({type:'batchAction',action,...extra});
 body.append(sheetHeader(state.phase==='results'?'Bet results':state.phase==='sending'?'Placing your bets':state.drafts.length>1?'Review your bets':'Review your bet',()=>send('close')));
 if(state.demo)body.append(slipNode('p','slip-demo','FICTIONAL BET DEMO · NO REAL MONEY'));
 if(state.phase==='review'){
  body.append(slipNode('p','slip-side',state.network===1?'TEST FUNDS':'REAL FUNDS'));
  if(state.orders.length>1)body.append(slipTable(state.orders,true));else if(state.orders[0]){const order=state.orders[0];body.append(slipNode('p','slip-market',order.title),slipNode('p','slip-side',order.label),...riskReturn(slipMoney(order.risk,true),slipMoney(order.payout),'Gross payout includes your stake, before settlement fees. Risk includes a fee ceiling of '+slipMoney(order.fees,true)+'.'))}
  if(state.orders.length>1){const total=slipTotals(state.orders,'risk','payout',true);if(total)body.append(...riskReturn(formatSlipCents(total.risk),formatSlipCents(total.payout),'Gross payout before settlement fees assumes every selected outcome wins. These are separate bets.'))}
  body.append(slipNode('p','slip-note','Wallet '+state.wallet),slipNode('p','slip-note',state.message));
 }else if(state.phase==='sending'){body.append(slipNode('p','slip-message',state.message),slipNode('p','slip-note','Wait for each venue result. Closing or retrying does not undo an order.'))}
 else if(state.phase==='results'){
  for(const row of state.results){const card=slipNode('section','bet-result');card.append(slipNode('b','',row.title),slipNode('span','',row.label+' · '+row.stage));if(row.filled)card.append(slipNode('span','',row.filled+' of '+row.shares+' shares filled; unfilled remainder cancelled.'));if(row.error)card.append(slipNode('span','slip-error',row.error));if(row.stage==='Unknown')card.append(slipNode('small','','Check status; do not place this order again.'));body.append(card)}
  body.append(slipNode('p','slip-message',state.message),slipButton('Check order status',()=>send('status')),slipButton('Back to markets',()=>send('close'),true));
 }else{
  if(state.drafts.length>1)body.append(slipTable(state.drafts,false,false));else if(state.drafts[0]){const row=state.drafts[0];body.append(slipNode('p','slip-market',row.title),slipNode('p','slip-side',row.label),...riskReturn(slipMoney(row.budget),slipMoney(row.estimatedPayout)))}
  const label=slipNode('label','network-label','Funds'),select=slipNode('select');select.setAttribute('aria-label','Bet funds');[['0','Choose funds'],['1','Test funds'],['2','Real funds']].forEach(([value,copy])=>{const o=slipNode('option','',copy);o.value=value;o.selected=Number(value)===state.network;select.append(o)});select.disabled=state.busy||state.demo;event(select,'change',e=>send('network',{network:Number(e.target.value)}));label.append(select);body.append(label);
  body.append(slipNode('p','slip-note',state.message||'Review refreshes executable prices and fees. Nothing is signed or sent yet.'));
  const button=slipButton(state.busy?'Checking…':state.drafts.length>1?'Review all bets':'Review bet',()=>send('review'),true);button.disabled=state.busy||!state.sdkReady||!state.drafts.length||state.blocked;body.append(button,slipButton('Check previous order status',()=>send('status')));
 }
 if(state.busy)body.querySelectorAll('button,select').forEach(b=>b.disabled=true);
 if(state.phase==='review')body.style.paddingBottom='88px';root.append(body);return root;
}

document.getElementById('bet-overlay')?.addEventListener('click',e=>{if(e.target===e.currentTarget)closeBetSheet()});
document.addEventListener('keydown',e=>{if(e.key==='Escape'&&BetSlip.open){e.preventDefault();closeBetSheet()}});
window.refreshBetQuote=()=>{if(BetSlip.open&&BetSlip.mode==='compose'&&Hunch.selected&&!BetSlip.pending&&Date.now()-BetSlip.requestedAt>15000){BetSlip.requestedAt=Date.now();requestBook()}};

// Capture on the stable overlay so quote refreshes cannot interrupt a handle drag.
let slipDrag=null,slipDragged=false;
const slipOverlay=document.getElementById('bet-overlay');
slipOverlay?.addEventListener('pointerdown',e=>{
 if(!e.target.closest('.slip-grab')||!e.isPrimary||e.button!==0)return;
 slipDrag={id:e.pointerId,y:e.clientY,time:performance.now(),distance:0};slipDragged=false;
 slipOverlay.setPointerCapture(e.pointerId);e.preventDefault();
});
slipOverlay?.addEventListener('pointermove',e=>{
 if(!slipDrag||e.pointerId!==slipDrag.id)return;
 slipDrag.distance=Math.max(0,e.clientY-slipDrag.y);slipDragged=slipDragged||Math.abs(e.clientY-slipDrag.y)>8;
 const sheet=slipOverlay.firstElementChild;if(sheet){sheet.style.transition='none';sheet.style.transform='translateY('+slipDrag.distance+'px)'}
});
function finishSlipDrag(e,cancelled=false){
 if(!slipDrag||e.pointerId!==slipDrag.id)return;
 const {distance,time}=slipDrag;slipDrag=null;
 if(slipOverlay.hasPointerCapture(e.pointerId))slipOverlay.releasePointerCapture(e.pointerId);
 if(!cancelled&&(distance>=64||(distance>24&&distance/Math.max(1,performance.now()-time)>.5)))closeBetSheet();
 else{const sheet=slipOverlay.firstElementChild;if(sheet){sheet.style.transition=matchMedia('(prefers-reduced-motion: reduce)').matches?'none':'transform .18s ease-out';sheet.style.transform=''}}
}
slipOverlay?.addEventListener('pointerup',e=>finishSlipDrag(e));
slipOverlay?.addEventListener('pointercancel',e=>finishSlipDrag(e,true));
slipOverlay?.addEventListener('click',e=>{if(slipDragged){e.preventDefault();e.stopImmediatePropagation();slipDragged=false}},true);
