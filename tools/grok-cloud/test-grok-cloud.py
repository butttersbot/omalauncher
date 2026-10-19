#!/usr/bin/env python3
"""Tests for grok-cloud: the public results door, the job door's checks and caps. Run: python3 test-grok-cloud.py"""
import importlib.machinery
import importlib.util
import json
import os
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
TMP = tempfile.mkdtemp(prefix='grok-cloud-test-')
os.environ.update({
    'GROK_CLOUD_STATE': TMP + '/state', 'GROK_CLOUD_CONFIG': TMP + '/config.json', 'GROK_CLOUD_MADE': TMP + '/made',
    'GROK_CLOUD_POCKET': 'http://127.0.0.1:9/v1/pocket', 'GROK_CLOUD_TRUST_LOOPBACK': '1',
})
loader = importlib.machinery.SourceFileLoader('grok_cloud', str(HERE / 'grok-cloud'))
spec = importlib.util.spec_from_loader('grok_cloud', loader)
gc = importlib.util.module_from_spec(spec)
loader.exec_module(gc)

SENT = []
REAL_SEND = gc.send_to_grok
REAL_KEYCHAIN = gc.keychain
gc.send_to_grok = lambda job: SENT.append(job) or None
gc.notify = lambda job: None


def start(handler):
    server = gc.Threaded(('127.0.0.1', 0), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return 'http://127.0.0.1:%d' % server.server_address[1]


JOBS = start(gc.Jobs)
RESULTS = start(gc.Results)


def call(url, body=None, ctype='application/json', method=None):
    data = body if isinstance(body, bytes) or body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method or ('POST' if data is not None else 'GET'),
                                     headers={'Content-Type': ctype})
    try:
        with urllib.request.urlopen(request, timeout=10) as r:
            return r.status, json.loads(r.read() or b'{}')
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b'{}')


def wait_status(job_id, want, seconds=5):
    end = time.time() + seconds
    while time.time() < end:
        job = gc.load(job_id)
        if job and job['status'] in want:
            return job
        time.sleep(0.05)
    return gc.load(job_id)


PNG = b'\x89PNG\r\n\x1a\n' + b'\0' * 64


def multipart(fields, files):
    boundary = 'b0undary'
    out = b''
    for k, v in fields.items():
        out += ('--%s\r\nContent-Disposition: form-data; name="%s"\r\n\r\n%s\r\n' % (boundary, k, v)).encode()
    for name, ctype, data in files:
        out += ('--%s\r\nContent-Disposition: form-data; name="file"; filename="%s"\r\nContent-Type: %s\r\n\r\n'
                % (boundary, name, ctype)).encode() + data + b'\r\n'
    return out + ('--%s--\r\n' % boundary).encode(), 'multipart/form-data; boundary=' + boundary


class GrokCloud(unittest.TestCase):
    def setUp(self):
        for p in Path(TMP, 'state/jobs').glob('*'):
            p.unlink()
        SENT.clear()

    def job(self, **ask):
        code, job = call(JOBS + '/v1/jobs', {'tool': 'midjourney', 'prompt': 'a blue sheep', **ask})
        self.assertEqual(200, code, job)
        self.assertNotIn('token', job)
        return job['job'], SENT[-1]['token']

    def test_only_its_two_tools_and_their_kinds(self):
        self.assertEqual(400, call(JOBS + '/v1/jobs', {'tool': 'higgsfield', 'prompt': 'x'})[0])
        self.assertEqual(400, call(JOBS + '/v1/jobs', {'tool': 'suno', 'kind': 'image', 'prompt': 'x'})[0])
        self.assertEqual(400, call(JOBS + '/v1/jobs', {'tool': 'suno', 'prompt': ''})[0])
        self.assertEqual(400, call(JOBS + '/v1/jobs', {'tool': 'suno', 'prompt': 'x' * 2001})[0])
        self.assertEqual('song', call(JOBS + '/v1/jobs', {'tool': 'suno', 'prompt': 'lofi'})[1]['kind'])

    def test_a_result_with_its_token_is_kept_once(self):
        job_id, token = self.job()
        body, ctype = multipart({'status': 'done', 'message': 'made it'}, [('a.png', 'image/png', PNG)])
        self.assertEqual(200, call('%s/grok/cb/%s/%s' % (RESULTS, job_id, token), body, ctype)[0])
        job = wait_status(job_id, ('done', 'failed'))
        self.assertEqual('done', job['status'], job)
        self.assertEqual(PNG, Path(job['files'][0]).read_bytes())
        self.assertIsNone(job['token'])
        # The token works once.
        self.assertEqual(404, call('%s/grok/cb/%s/%s' % (RESULTS, job_id, token), body, ctype)[0])

    def test_a_wrong_or_missing_token_gets_nothing(self):
        job_id, token = self.job()
        body, ctype = multipart({'status': 'done'}, [('a.png', 'image/png', PNG)])
        self.assertEqual(404, call('%s/grok/cb/%s/%s' % (RESULTS, job_id, 'x' * len(token)), body, ctype)[0])
        self.assertEqual(404, call('%s/grok/cb/%s' % (RESULTS, job_id), body, ctype)[0])
        self.assertEqual(404, call('%s/grok/cb/../../etc/%s' % (RESULTS, token), body, ctype)[0])
        self.assertEqual('sent', gc.load(job_id)['status'])

    def test_the_results_door_answers_nothing_else(self):
        self.assertEqual(404, call(RESULTS + '/v1/jobs')[0])
        self.assertEqual(404, call(RESULTS + '/v1/jobs', {'tool': 'suno', 'prompt': 'x'})[0])

    def test_only_media_files_are_kept(self):
        job_id, token = self.job()
        body, ctype = multipart({'status': 'done'}, [('run.sh', 'text/x-sh', b'#!/bin/sh\nrm -rf ~\n')])
        call('%s/grok/cb/%s/%s' % (RESULTS, job_id, token), body, ctype)
        job = wait_status(job_id, ('done', 'failed'))
        self.assertEqual('failed', job['status'])
        self.assertEqual([], job['files'])

    def test_links_must_be_https(self):
        job_id, token = self.job()
        call('%s/grok/cb/%s/%s' % (RESULTS, job_id, token),
             {'status': 'done', 'files': ['http://127.0.0.1:1/a.png', 'file:///etc/passwd']})
        job = wait_status(job_id, ('done', 'failed'))
        self.assertEqual('failed', job['status'])
        self.assertIn('not an https link', job['message'])

    def test_blocked_and_failed_are_reported(self):
        job_id, token = self.job(tool='suno', prompt='a song')
        call('%s/grok/cb/%s/%s' % (RESULTS, job_id, token), {'status': 'blocked', 'message': 'CAPTCHA'})
        job = wait_status(job_id, ('blocked',))
        self.assertEqual('blocked', job['status'])
        self.assertIn('CAPTCHA', job['message'])

    def test_too_large_a_body_is_refused(self):
        job_id, token = self.job()
        old = gc.MAX_BODY_BYTES
        gc.MAX_BODY_BYTES = 100
        try:
            self.assertEqual(413, call('%s/grok/cb/%s/%s' % (RESULTS, job_id, token), b'x' * 200)[0])
        finally:
            gc.MAX_BODY_BYTES = old

    def test_the_hourly_cap_holds(self):
        for _ in range(gc.JOBS_PER_HOUR):
            self.job()
        code, answer = call(JOBS + '/v1/jobs', {'tool': 'midjourney', 'prompt': 'one more'})
        self.assertEqual(429, code)
        self.assertIn('an hour', answer['error'])

    def test_an_unanswered_job_expires_and_its_token_dies(self):
        job_id, token = self.job()
        job = gc.load(job_id)
        job['created'] -= gc.JOB_LIFETIME + 1
        gc.save(job)
        body, ctype = multipart({'status': 'done'}, [('a.png', 'image/png', PNG)])
        self.assertEqual(404, call('%s/grok/cb/%s/%s' % (RESULTS, job_id, token), body, ctype)[0])
        gc.expire_old()
        self.assertEqual('expired', gc.load(job_id)['status'])

    def test_job_files_are_private(self):
        job_id, _ = self.job()
        self.assertEqual(0o600, os.stat(gc.job_path(job_id)).st_mode & 0o777)

    def test_without_the_keychain_nothing_is_sent(self):
        gc.keychain = lambda account: None
        try:
            self.assertIn("isn't set up", REAL_SEND(gc.new_job('suno', 'song', 'x', 'test', None)))
        finally:
            gc.keychain = REAL_KEYCHAIN

    def test_only_an_https_webhook_is_used(self):
        gc.keychain = lambda account: {'url': 'http://example.com/hook', 'key': 'k'}[account]
        old = gc.CONFIG
        gc.CONFIG = Path(TMP, 'cfg.json')
        gc.CONFIG.write_text('{"key_header": "X-Key"}')
        try:
            self.assertIn('https', REAL_SEND(gc.new_job('suno', 'song', 'x', 'test', None)))
        finally:
            gc.keychain, gc.CONFIG = REAL_KEYCHAIN, old

if __name__ == '__main__':
    unittest.main(verbosity=1)
