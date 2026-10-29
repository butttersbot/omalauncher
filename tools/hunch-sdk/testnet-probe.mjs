// Explicit testnet-only integration probe. Creates an unfunded disposable test
// signer in memory. Never reads a user key; never touches mainnet or a faucet.
import {order} from '@nktkas/hyperliquid/api/exchange';
import {privateKeyToAccount,generatePrivateKey} from 'viem/accounts';
import assert from 'node:assert/strict';
const host='https://api.hyperliquid-testnet.xyz';
async function info(body){return (await fetch(host+'/info',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})).json()}
const meta=await info({type:'outcomeMeta'});
assert.ok(meta.outcomes.length);
const mids=await info({type:'allMids'});
let selected;
for(const market of meta.outcomes.filter(m=>mids['#'+(10*m.outcome)]).slice(0,20)){
 const book=await info({type:'l2Book',coin:'#'+(10*market.outcome)});
 const ask=book.levels?.[1]?.[0]?.px;
 if(ask&&Number(ask)>0&&Number(ask)<1){selected={market,price:ask};break;}
}
assert.ok(selected,'No quoted testnet outcome in the last 30 listings');
const wallet=privateKeyToAccount(generatePrivateKey());
let bodyReply;
try{
 await order({wallet,transport:{isTestnet:true,request:async(endpoint,body)=>{
  assert.equal(endpoint,'exchange');
  const response=await fetch(host+'/exchange',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
  bodyReply=await response.json();
  console.log('Testnet HTTP',response.status,'response',JSON.stringify(bodyReply));
  return bodyReply;
 }}},{orders:[{a:100000000+10*selected.market.outcome,b:true,p:selected.price,s:'20',r:false,t:{limit:{tif:'Ioc'}},c:'0x'+crypto.randomUUID().replaceAll('-','')}],grouping:'na'},{expiresAfter:Date.now()+30000});
}catch(e){
 const reply=JSON.stringify(bodyReply);
 assert.ok(bodyReply,'No exchange response');
 assert.match(reply,/deposit|balance|exist|fund/i);
 assert.ok(reply.toLowerCase().includes(wallet.address.toLowerCase()),'Exchange did not recover the disposable signer address');
 console.log('PASS TESTNET signing recovered the disposable signer; correctly refused unfunded account. NO FUNDED FILL PROVEN.');
}
console.log('Testnet readback',JSON.stringify(await info({type:'orderStatus',user:wallet.address,oid:'0x11111111111111111111111111111111'})));
