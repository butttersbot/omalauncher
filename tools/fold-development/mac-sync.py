#!/usr/bin/python3
import fcntl, pathlib, subprocess, os, sys
base=pathlib.Path.home()/'agents/luna/phone-source-sync'
lock=open(base/'sync.lock','w')
try: fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
except BlockingIOError: sys.exit(0)
repo=str(pathlib.Path.home()/'Projects/omalauncher-fold')
def git(*args):
 r=subprocess.run(['/usr/bin/git','-C',repo,*args],capture_output=True,text=True,timeout=45,env={**os.environ,'GIT_TERMINAL_PROMPT':'0'})
 if r.returncode: raise RuntimeError(r.stderr.strip() or 'Git update failed')
 return r.stdout.strip()
def report(message):
 state=base/'status.txt'
 if not state.exists() or state.read_text()!=message:
  state.write_text(message);print(message,flush=True)
try:
 if git('branch','--show-current')!='fold-sync': raise RuntimeError('Paused: mirror branch changed; preserving local work.')
 changes=git('status','--porcelain','--untracked-files=all').splitlines()
 if any(line!='?? CLAUDE.md' for line in changes): raise RuntimeError('Paused: Mac mirror has local edits; preserving them.')
 git('fetch','--no-tags','fork','refs/heads/phone/fold-development:refs/remotes/fork/phone/fold-development')
 before=git('rev-parse','HEAD');target=git('rev-parse','fork/phone/fold-development')
 if before!=target: git('merge','--ff-only','fork/phone/fold-development')
 report('Mac Fold source synced: '+git('rev-parse','--short','HEAD'))
except Exception as e:
 report(str(e));sys.exit(1)
