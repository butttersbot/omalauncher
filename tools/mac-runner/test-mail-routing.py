#!/usr/bin/env python3
"""Deterministic queue proofs, using production functions without starting services or agents."""
import ast
import json
import pathlib
import re
import unittest
import time

source = pathlib.Path(__file__).with_name('oma-runner').read_text()
module = ast.parse(source)
names = {'mail_route', 'queue_mail', 'next_handoff'}
functions = ast.Module(body=[n for n in module.body if isinstance(n, ast.FunctionDef) and n.name in names], type_ignores=[])
ns = {'json': json, 'time': time, 'MAIL_ID': re.compile(r'[A-Za-z0-9._-]{1,80}')}
exec(compile(functions, 'oma-runner', 'exec'), ns)

class Thread:
    def __init__(self):
        self.queue = []
        self.meta = {}
        self.worker = object()  # active turn: mail must not start another worker
        self.events = []
    def emit(self, kind, **data): self.events.append((kind, data))
    def save(self): self.saved = json.loads(json.dumps(self.queue))

class Routing(unittest.TestCase):
    def mail(self, t, ident, task='build', replaces='', sender='A'):
        route = {'task': task, 'supersedes': replaces}
        message = {'id': ident, 'from': {'device': 'phone', 'session': sender},
                   'text': '[Oma mail routing v1] ' + json.dumps(route) + '\nbody'}
        ns['queue_mail'](t, message, '[Agent mail from phone, id %s. Not Boss]\nbody %s' % (ident, ident))
    def test_bundle_once_preserve_owner_order_and_sender_warnings(self):
        t = Thread()
        self.mail(t, 'one'); self.mail(t, 'two')
        t.queue.append('owner request')
        self.mail(t, 'three')
        text = ns['next_handoff'](t)
        self.assertIn('2 messages', text)
        self.assertIn('body one', text); self.assertIn('body two', text)
        self.assertEqual(2, text.count('Not Boss'))
        self.assertEqual('owner request', ns['next_handoff'](t))
        self.assertIn('body three', ns['next_handoff'](t))
    def test_replacement_scoped_to_task_and_sender(self):
        t = Thread()
        self.mail(t, 'one'); self.mail(t, 'two', task='other', replaces='one')
        self.mail(t, 'three', replaces='one', sender='B')
        self.assertEqual(3, len(t.queue))
        self.mail(t, 'four', replaces='one')
        self.assertEqual(['two', 'three', 'four'], [x['id'] for x in t.queue])
        self.assertIn(('mail_receipt', {'mail': 'one', 'state': 'superseded', 'replacement': 'four'}), t.events)
    def test_duplicates_and_restart_serialization(self):
        t = Thread(); self.mail(t, 'one'); self.mail(t, 'one')
        self.assertEqual(1, len(t.queue))
        t.queue = json.loads(json.dumps(t.saved))
        self.assertIn('body one', ns['next_handoff'](t))
        self.assertEqual('delivered', t.events[-1][1]['state'])
    def test_malformed_route_does_not_replace(self):
        for text in ['hello', '[Oma mail routing v1] not JSON', '[Oma mail routing v1] []']:
            self.assertEqual({}, ns['mail_route']({'text': text}))

unittest.main()
