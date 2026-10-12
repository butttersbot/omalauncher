#!/usr/bin/env python3
import fcntl, pathlib, subprocess, sys, re
repo = pathlib.Path('/root/Projects/omalauncher')
lock = open('/root/.config/oma-development/sync.lock', 'w')
try: fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
except BlockingIOError: sys.exit('Another Fold sync is already running.')
def git(*args, raw=False):
    r=subprocess.run(['git','-C',str(repo),*args],stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True)
    return r.stdout if raw else r.stdout.decode().strip()
if git('branch','--show-current') != 'phone/fold-development':
    sys.exit('Sync stopped: switch to phone/fold-development first.')
if git('diff','--name-only','--diff-filter=U'):
    sys.exit('Sync stopped: resolve merge conflicts first.')
git('add','-A','--','.')
paths=git('diff','--cached','--name-only','-z',raw=True).split(b'\0')
blocked=[]
patterns=[rb'-----BEGIN (?:OPENSSH |RSA |EC |DSA )?PRIVATE KEY-----', rb'gh[pousr]_[A-Za-z0-9]{30,}', rb'github_pat_[A-Za-z0-9_]{30,}', rb'sk-ant-[A-Za-z0-9_-]{20,}']
for b in filter(None,paths):
    path=b.decode()
    name=pathlib.PurePosixPath(path).name.lower()
    if name.startswith('.env') or name in ('keystore.properties','local.properties','authorized_keys','id_ed25519','id_rsa') or name.endswith(('.jks','.keystore','.pem','.p12','.pfx')):
        blocked.append(path); continue
    r=subprocess.run(['git','-C',str(repo),'show',':'+path],capture_output=True)
    if r.returncode==0 and any(re.search(p,r.stdout) for p in patterns): blocked.append(path)
if blocked:
    git('reset','--quiet','HEAD','--',*blocked)
    sys.exit('Sync stopped: sensitive files detected; nothing committed or pushed.')
if git('diff','--cached','--name-only'):
    git('commit','-m','Sync Fold development changes')
git('push','-u','origin','HEAD:refs/heads/phone/fold-development')
print('Synced Fold changes to GitHub: '+git('rev-parse','--short','HEAD'))
