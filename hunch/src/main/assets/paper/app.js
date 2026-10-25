 'use strict';
const design=new URLSearchParams(location.search).get('design');let accountMode=(new URLSearchParams(location.search).has('account')||new URLSearchParams(location.search).has('wallet')),started=false;
window.paperError=e=>{console.error(e);notice('This page could not be drawn. Return to markets and try again.')};
async function reference(name){document.body.classList.add('reference');document.getElementById('reference').hidden=false;await Paper.show(name);Paper.active.autoHeight=name==='TodayFeed';Paper.active.fitHeight=name!=='TodayFeed';Paper.fit();if(!document.querySelector('.paper-preview-tools')){const bar=document.createElement('div');bar.className='paper-preview-tools';const select=document.createElement('select');select.setAttribute('aria-label','Design reference page');const pages=await(await fetch('pages.json')).json();pages.forEach(p=>{const o=document.createElement('option');o.value=p.name;o.textContent=p.name;o.selected=p.name===name;select.append(o)});select.onchange=()=>reference(select.value);bar.append(select);document.getElementById('reference').append(bar)}document.querySelector('.paper-preview-tools select').value=name;ribbonSize();Paper.fit()}
function ribbonSize(){const h=document.getElementById('reference').getBoundingClientRect().height;document.body.style.setProperty('--ribbon-height',h+'px')}
document.getElementById('leave-reference').onclick=()=>sendNative({type:design?'live':'close'});
document.addEventListener('click',e=>{const a=e.target.closest('a[href]');if(!a)return;e.preventDefault();const match=/([A-Za-z]+)\.dc\.html/.exec(a.getAttribute('href'));if(design&&match)reference(match[1]);else notice('This action is not connected yet.')});
window.receiveNative=async data=>{try{
 if(data.type==='config'){if(started)return;started=true;Hunch.chapter=data.chapter||'today';if(design)await reference(design);else{if(data.fixture){document.body.classList.add('reference');const ribbon=document.getElementById('reference');ribbon.hidden=false;ribbon.firstChild.nodeValue=data.fixture==='demo'?'FICTIONAL MARKET DEMO · NO REAL MONEY ':'OFFLINE TEST · NO REAL MONEY ';document.getElementById('leave-reference').textContent='CLOSE DEMO';ribbonSize()}await showLive()}}
 if(data.type==='markets'){Hunch.rows=data.rows;Hunch.updated=data.updated;Hunch.error=false;Paper.schedule()}
 if(data.type==='marketError'){Hunch.error=true;Paper.schedule()}
 if(data.type==='book'&&Hunch.selected?.id===data.id&&Hunch.side===data.side&&Hunch.budget===data.budget){Hunch.book=data;Paper.schedule()}
 if(data.type==='wallet'){accountMode=true;clearTimeout(resizeTimer);await showWallet(data)}
 if(data.type==='auth'){accountMode=true;clearTimeout(resizeTimer);await showAccount(data)}
 }catch(e){paperError(e)}};
let resizeTimer;window.paperResize=()=>{ribbonSize();Paper.fit();if(accountMode){const field=document.activeElement;if(field?.tagName==='INPUT')requestAnimationFrame(()=>field.scrollIntoView({block:'center'}));return}if(!design&&!accountMode){clearTimeout(resizeTimer);resizeTimer=setTimeout(()=>{if(!accountMode){if(Hunch.layoutWide===(window.innerWidth>=600))Paper.render();else showLive()}},150)}};
window.paperBack=()=>{if(design||accountMode)return false;if(Hunch.selected){Hunch.selected=null;showLive();return true}if(Hunch.chapter!=='today'){route('today');return true}return false};
sendNative({type:'ready'});

// Expired depth must disappear even while the owner leaves the page open.
setInterval(()=>{if(Hunch.selected&&Hunch.book?.time&&Date.now()-Hunch.book.time>60000)Paper.schedule()},1000);
