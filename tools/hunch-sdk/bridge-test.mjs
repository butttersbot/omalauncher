// Exercise the exact generated Android asset, not a second implementation of signing.
import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';
import assert from 'node:assert/strict';
import {privateKeyToAccount,generatePrivateKey} from 'viem/accounts';
import {recoverTypedDataAddress} from 'viem';
const wallet=privateKeyToAccount(generatePrivateKey());
let typed,signs=0,submits=0,failures=[];
const window={};
window.SomaHunch={post:raw=>{
 const data=JSON.parse(raw);
 queueMicrotask(async()=>{
  try{
   if(data.type==='tradeSign'){
    typed=data.typed;signs++;
    assert.ok(typed.types.EIP712Domain);assert.equal(typed.message.source,'b');
    const signature=await wallet.signTypedData(typed);
    window.HunchSdk.reply(data.call,signature,null);
   }else if(data.type==='tradeSubmit'){
    const body=data.body;submits++;
    const rawSig=body.signature.r+body.signature.s.slice(2)+body.signature.v.toString(16);
    assert.equal((await recoverTypedDataAddress({...typed,signature:rawSig})).toLowerCase(),wallet.address.toLowerCase());
    if(body.action.type==='order'){
     assert.equal(body.action.orders[0].s,'20');assert.equal(body.action.orders[0].p,'0.5');if(body.action.orders.length===2){assert.equal(body.action.orders[1].a,100000021);assert.equal(body.action.orders[1].s,'7');assert.equal(body.action.orders[1].p,'0.61');assert.equal(body.action.orders[1].c,'0x22222222222222222222222222222222');}
     window.HunchSdk.reply(data.call,{status:'ok',response:{type:'order',data:{statuses:body.action.orders.map((o,i)=>({filled:{oid:i+1,totalSz:o.s,avgPx:o.p}}))}}},null);
    }else{
     assert.equal(body.action.type,'cancelByCloid');assert.equal(body.action.cancels[0].cloid,ticket.cloid);
     window.HunchSdk.reply(data.call,{status:'ok',response:{type:'cancel',data:{statuses:['success']}}},null);
    }
   }else if(data.type==='tradeFailed')failures.push(data.message);
  }catch(error){failures.push(String(error));if(data.call)window.HunchSdk.reply(data.call,null,String(error));}
 });
}};
runInNewContext(readFileSync(new URL('../../hunch/src/main/assets/paper/exchange-sdk.js',import.meta.url),'utf8'),{window,TextEncoder,TextDecoder,setTimeout,clearTimeout,console,crypto:globalThis.crypto});
const ticket={id:'fictional',wallet:wallet.address,asset:100000010,buy:true,price:'0.5',shares:'20',tif:'Ioc',testnet:true,cloid:'0x11111111111111111111111111111111',nonce:1800000000000,expiresAfter:1800000030000};
await window.HunchSdk.send(ticket);
await window.HunchSdk.send({...ticket,id:'fictional-cancel',cancel:true,nonce:ticket.nonce+1});
await window.HunchSdk.send({...ticket,id:'fictional-batch',nonce:ticket.nonce+2,orders:[ticket,{...ticket,asset:100000021,shares:'7',price:'0.61',cloid:'0x22222222222222222222222222222222'}]});
assert.deepEqual(failures,[]);assert.equal(signs,3);assert.equal(submits,3);
console.log('PASS generated Android bundle: native typed-data RPC roundtrip, signer recovery, exact order envelope, cancel envelope; 3 fictional signatures/submissions including a two-order batch, zero HTTP');
