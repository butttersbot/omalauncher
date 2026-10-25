'use strict';
let walletState=null;
async function showWallet(data){
 walletState=data;
 if(Paper.active?.wallet){Paper.schedule();return}
 await Paper.show('Flow',logic=>{
  logic.state.step=2;const a=Paper.active;a.wallet=true;a.autoHeight=true;a.maxScale=1;
  a.dimensions=()=>({width:390,height:844});
  a.prepare=root=>{
   const state=walletState,body=cropPhone(root),parts=Array.from(body.children),card=parts[0],deposit=parts[1];
   const send=action=>sendNative({type:'walletAction',action});
   text(card.children[0],state.demo?'FICTIONAL WALLET DEMO · NO REAL MONEY':'YOUR HUNCH WALLET');
   text(card.children[1],state.address?'Your wallet':state.signed?'Create your wallet':'Start with sign-in');
   const intro=card.lastElementChild;intro.replaceChildren();const copy=document.createElement('span');copy.style.cssText='font-size:14px;line-height:1.5';copy.textContent=state.address?'Your Hunch wallet is ready.':state.signed?'Create one Ethereum wallet for Hunch. Existing wallets are reused.':'Sign in with your email first. Your passkey and email recovery stay with the same account.';intro.append(copy);
   const qr=deposit.firstElementChild;qr.style.background='#fdfdfb';qr.replaceChildren();qr.style.display='grid';qr.style.placeItems='center';
   if(state.address&&/^[A-Za-z0-9+/]+=*$/.test(state.qr||'')){const img=document.createElement('img');img.src='data:image/png;base64,'+state.qr;img.alt='QR code for your Hunch wallet address';img.style.cssText='width:96px;height:96px;image-rendering:pixelated';qr.append(img)}else{qr.textContent='NO WALLET';qr.style.font="700 10px 'JetBrains Mono'"}
   const info=deposit.lastElementChild;text(info.children[0],'Receive native USDC on Arbitrum');text(info.children[1],'Network: Arbitrum One · chain 42161. This is your wallet address, not the Hyperliquid bridge.');
   const address=info.lastElementChild;text(address,state.address||'No wallet address yet.');address.style.overflowWrap='anywhere';address.style.wordBreak='break-all';address.setAttribute('aria-label','Hunch wallet address: '+(state.address||'No wallet address yet.'));
   body.replaceChildren(card,deposit);
   const status=document.createElement('p');status.setAttribute('role','status');status.style.cssText='font-size:14px;line-height:1.5;margin:0';status.textContent=state.message||'Wallet setup only. No order or transfer is sent here.';body.append(status);
   function button(label,action,primary=false){const b=document.createElement('button');b.type='button';b.setAttribute('style',parts[primary?4:2].getAttribute('style'));b.style.flexShrink='0';b.disabled=state.busy;b.textContent=label;event(b,'click',()=>send(action));body.append(b)}
   if(!state.signed)button('Sign in to Hunch','login',true);
   else if(!state.address)button('Create Hunch wallet','create',true);
   else{
    button('Copy wallet address','copy',true);
    const panel=document.createElement('div');panel.style.cssText='border:1.5px solid #111113;padding:14px 16px;display:flex;flex-direction:column;gap:10px';
    const heading=document.createElement('b');heading.textContent='Your verified balances';heading.style.font="700 11px 'JetBrains Mono'";panel.append(heading);
    const b=state.balances||{};
    for(const [label,key,unit]of [['Arbitrum USDC','arbitrumUsdc','USDC'],['Arbitrum gas','arbitrumEth','ETH'],['Hyperliquid spot total','hyperliquidTotal','USDC'],['Hyperliquid spot available','hyperliquidAvailable','USDC']]){const line=document.createElement('span');line.style.fontSize='13px';line.textContent=label+': '+(state.busy?'Checking…':b[key]===undefined?'Unavailable':b[key]+' '+unit);panel.append(line)}
    if(b.arbitrumError||b.hyperliquidError){const error=document.createElement('span');error.textContent='Some balances could not be checked. Refresh before relying on them.';panel.append(error)}
    if(b.checked&&!state.busy){const checked=document.createElement('span');checked.style.fontSize='12px';checked.textContent='Last checked: '+new Date(b.checked).toLocaleTimeString()+'. Refresh for current balances.';panel.append(checked)}
    const note=document.createElement('span');note.style.cssText='font-size:12px;line-height:1.5;color:#565964';note.textContent='Arbitrum funds are not trading cash. Moving funds to Hyperliquid is a separate transaction. Test wallet setup before sending money.';panel.append(note);body.append(panel);
   }
   button('Refresh wallet and balances','check');const back=action('Back to markets',()=>sendNative({type:'close'}));body.append(back);
  };
 });
}
