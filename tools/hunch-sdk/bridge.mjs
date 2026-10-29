// Pinned, documented Hyperliquid SDK handles canonical action order, MessagePack,
// hashing, EIP-712 and signature splitting. Native owns the reviewed ticket/journal.
import { getTypesForEIP712Domain, serializeTypedData } from 'viem';
import { order, cancelByCloid } from '@nktkas/hyperliquid/api/exchange';
let pending = new Map(), serial=0, active=false;
function rpc(kind, body) {
  const id=String(++serial);
  return new Promise((resolve,reject)=>{pending.set(id,{resolve,reject});window.SomaHunch.post(JSON.stringify({type:kind,call:id,...body}));});
}
window.HunchSdk = {
 reply(id,result,error){const p=pending.get(id);if(!p)return;pending.delete(id);error?p.reject(new Error(error)):p.resolve(result);},
 async send(ticket){
  if(active)throw new Error('An order is already being processed');active=true;
  try {
   const config={wallet:{address:ticket.wallet,signTypedData:typed=>rpc('tradeSign',{id:ticket.id,typed:JSON.parse(serializeTypedData({...typed,types:{EIP712Domain:getTypesForEIP712Domain({domain:typed.domain}),...typed.types}}))})},
    nonceManager:()=>ticket.nonce,
    transport:{isTestnet:ticket.testnet,request:(endpoint,body)=>{if(endpoint!=='exchange')throw new Error('Unexpected SDK route');return rpc('tradeSubmit',{id:ticket.id,body});}}};
   const opts={expiresAfter:ticket.expiresAfter};
   if(ticket.cancel)await cancelByCloid(config,{cancels:[{asset:ticket.asset,cloid:ticket.cloid}]},opts);
   else await order(config,{orders:[{a:ticket.asset,b:ticket.buy,p:ticket.price,s:ticket.shares,r:false,t:{limit:{tif:ticket.tif}},c:ticket.cloid}],grouping:'na'},
    opts);
  }catch(e){window.SomaHunch.post(JSON.stringify({type:'tradeFailed',id:ticket.id,message:String(e.message||e)}));}
  finally{active=false;}
 }
};
