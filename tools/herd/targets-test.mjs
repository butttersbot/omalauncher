// "Send clipboard to": the herd page's target list (assets/herd/pet.html, between <targets> and </targets>), checked with node:
//   node tools/herd/targets-test.mjs
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const html = readFileSync(new URL('../../app/src/main/assets/herd/pet.html', import.meta.url), 'utf8');
const block = html.split('// <targets>')[1].split('// </targets>')[0];
const Targets = new Function(block + '\nreturn Targets;')();

const machines = [
  { name: 'jacks-mac-pro', address: '100.120.219.46', os: 'macOS', self: true },
  { name: 'omarchy-mg1', address: '100.77.138.16', os: 'linux' },
  { name: 'macbook-pro', address: '100.105.137.74', os: 'macOS' },
  { name: 'galaxy-z-fold8', address: '100.98.172.21', os: 'android' },
];
const others = [
  { name: 'funnel-ingress-node', address: 'fd7a:115c:a1e0::a801:26ab', os: '', online: true },
  { name: 'funnel-ingress-node', address: 'fd7a:115c:a1e0::3901:8ebc', os: '', online: true },
  { name: 'relay-node-1', address: '100.64.0.9', os: '', online: true },
  { name: 'v6-only', address: 'fd7a:115c:a1e0::1', os: 'linux', online: true },
  { name: 'ipad173', address: '100.106.165.56', os: 'iOS', online: true },
  { name: 'ipad173', address: '100.106.165.57', os: 'iOS', online: true },
  { name: 'pixel-5a', address: '100.120.156.54', os: 'android', online: false },
];

// On a computer: its fellow computers, the Fold, and the online clients; never a funnel/relay node; each name once.
assert.deepEqual(Targets.list(machines, others, false), ['omarchy-mg1', 'macbook-pro', 'galaxy-z-fold8', 'ipad173']);
// On the phone: only the computers (they run tailshare-serve).
assert.deepEqual(Targets.list(machines, others, true), ['omarchy-mg1', 'macbook-pro']);
// Nothing at all: nothing offered.
assert.deepEqual(Targets.list(undefined, undefined, false), []);
console.log('targets: ok');
