#!/usr/bin/env python3
"""Exercise the shipped CLI against a local fake transport; no phone or accounts."""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / 'app/src/main/assets/oma-phone.sh'

class PhoneCliTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.capture = self.root / 'requests.jsonl'
        fake = self.root / 'curl'
        fake.write_text('''#!/usr/bin/env python3
import json,os,sys
args=sys.argv[1:]
with open(os.environ['SOMA_CLI_CAPTURE'],'a') as f:
 f.write(json.dumps({'args':args,'request':json.loads(args[args.index('--data-binary')+1])})+'\\n')
print(json.dumps({'ok':os.environ.get('SOMA_CLI_FAIL')!='1'}))
''')
        fake.chmod(0o700)
        self.env = dict(os.environ, PATH=str(self.root) + os.pathsep + os.environ['PATH'], SOMA_CLI_CAPTURE=str(self.capture))
    def tearDown(self):
        self.temp.cleanup()
    def run_cli(self, *args):
        return subprocess.run(['bash', str(SCRIPT), *args], env=self.env, capture_output=True, text=True)
    def records(self):
        return [json.loads(x) for x in self.capture.read_text().splitlines()] if self.capture.exists() else []
    def write_plan(self, value):
        file = self.root / 'plan.json'
        file.write_text(json.dumps(value))
        return str(file)
    def test_plan_is_one_request_with_literal_text_and_display(self):
        words = 'literal $(touch /should-not-exist); `false` "quoted"\ntext'
        data = {'id':'1792830000000-fictional-unique','steps':[
            {'command':'fill','in':'fictional.app','label':'Entry','text':words},
            {'command':'screen','in':'fictional.app','until':'Saved'}]}
        result = self.run_cli('--display','bg','--timing','plan',self.write_plan(data))
        self.assertEqual(result.returncode,0,result.stderr)
        records = self.records();self.assertEqual(len(records),1)
        self.assertEqual(records[0]['request'],dict(data,command='plan',display='bg',timing=True))
        args = records[0]['args'];self.assertEqual(args[args.index('--max-time')+1],'35')
    def test_transport_failure_is_not_retried_or_fallen_back(self):
        self.env['SOMA_CLI_FAIL']='1'
        result=self.run_cli('plan',self.write_plan({'id':'1792830000000-fictional-unique','steps':[]}))
        self.assertNotEqual(result.returncode,0)
        self.assertEqual(len(self.records()),1)
    def test_invalid_or_oversized_plan_never_reaches_transport(self):
        for value in [[], {'steps':[]}, {'id':'x','steps':'wrong'}, {'id':'x','steps':[],'padding':'x'*60001}]:
            self.assertNotEqual(self.run_cli('plan',self.write_plan(value)).returncode,0)
        self.assertEqual(self.records(),[])
    def test_receipt_and_id_generation_do_not_execute_steps(self):
        generated=self.run_cli('plan-id')
        self.assertEqual(generated.returncode,0)
        self.assertRegex(generated.stdout.strip(),r'^\d{13}-[a-f0-9-]{36}$')
        self.assertEqual(self.records(),[])
        result=self.run_cli('plan-receipt',generated.stdout.strip())
        self.assertEqual(result.returncode,0)
        self.assertEqual(self.records()[0]['request']['command'],'plan-receipt')
        self.assertNotIn('steps',self.records()[0]['request'])

if __name__ == '__main__':
    unittest.main()
