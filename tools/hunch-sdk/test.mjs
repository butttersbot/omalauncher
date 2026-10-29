import assert from 'node:assert/strict';
import {order} from '@nktkas/hyperliquid/api/exchange';
import {privateKeyToAccount,generatePrivateKey} from 'viem/accounts';
import {recoverTypedDataAddress} from 'viem';
const wallet=privateKeyToAccount(generatePrivateKey()); // ephemeral, never printed or persisted
let typed, envelope;
const response={status:'ok',response:{type:'order',data:{statuses:[{resting:{oid:1}}]}}};
await order({wallet:{address:wallet.address,signTypedData:async data=>{typed=data;return wallet.signTypedData(data)}},nonceManager:()=>1800000000000,transport:{isTestnet:true,request:async(path,body)=>{assert.equal(path,'exchange');envelope=body;return response}}},{orders:[{a:100000010,b:false,p:'0.5',s:'20',r:false,t:{limit:{tif:'Ioc'}},c:'0x11111111111111111111111111111111'}],grouping:'na'},{expiresAfter:1800000030000});
assert.equal(typed.message.source,'b');assert.equal(typed.domain.chainId,1337);
const sig=envelope.signature;const raw=sig.r+sig.s.slice(2)+sig.v.toString(16);
assert.equal((await recoverTypedDataAddress({...typed,signature:raw})).toLowerCase(),wallet.address.toLowerCase());
assert.equal(envelope.action.orders[0].b,false);assert.equal(envelope.expiresAfter,1800000030000);
assert.deepEqual(Object.keys(envelope.action.orders[0]),['a','b','p','s','r','t','c']);
console.log('PASS SDK canonical order, EIP-712 testnet source, recovered test signer, size, cloid, expiry, sell IOC');
// Local fixture only. No HTTP calls and no mainnet key.
