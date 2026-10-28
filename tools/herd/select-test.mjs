// The herd page's choosing-several logic (assets/herd/pet.html, between <sel> and </sel>), checked with node:
//   node tools/herd/select-test.mjs
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const html = readFileSync(new URL('../../app/src/main/assets/herd/pet.html', import.meta.url), 'utf8');
const block = html.split('// <sel>')[1].split('// </sel>')[0];
const Sel = new Function(block + '\nreturn Sel;')();

const pocket = [{ id: 'a' }, { id: 'b' }], inbox = [{ name: 'x.txt' }];
const keys = Sel.keys(pocket, inbox);
assert.deepEqual(keys, ['p:a', 'p:b', 'i:x.txt']);

let s = Sel.toggle(new Set(), 'p:a');
assert.deepEqual([...s], ['p:a']);
s = Sel.toggle(s, 'i:x.txt');
assert.equal(s.size, 2);
assert.deepEqual([...Sel.toggle(s, 'p:a')], ['i:x.txt']);

// Select all, then (everything chosen) none.
const all = Sel.all(s, keys);
assert.equal(all.size, 3);
assert.equal(Sel.all(all, keys).size, 0);
assert.equal(Sel.all(new Set(), []).size, 0);

// A file gone meanwhile drops out of the choice.
assert.deepEqual([...Sel.prune(all, ['p:b', 'i:x.txt'])], ['p:b', 'i:x.txt']);

// Pocket ids and list names apart, with colons in a name kept.
assert.deepEqual(Sel.split(new Set(['p:a', 'i:report: v2.txt', 'p:b'])), { pocket: ['a', 'b'], inbox: ['report: v2.txt'] });

assert.equal(Sel.words(1), 'Delete 1 file?');
assert.equal(Sel.words(7), 'Delete 7 files?');
console.log('select-test: all passed');
