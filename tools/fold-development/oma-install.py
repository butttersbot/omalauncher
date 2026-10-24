#!/usr/bin/env python3
import concurrent.futures, json, pathlib, re, socket, subprocess, sys, hashlib, shlex, uuid
repo=pathlib.Path('/root/Projects/omalauncher')
apk=repo/'app/build/outputs/apk/debug/app-debug.apk'
package='dev.omalauncher.boss'
expected_cert=pathlib.Path('/root/.config/oma-development/working-cert.sha256').read_text().strip()
pending=pathlib.Path('/root/.config/oma-development/pending-install.json')
serial=json.loads(pathlib.Path('/root/.config/oma-development/device.json').read_text())['serial']
cache=pathlib.Path('/root/.config/oma-development/install-port')
service=package+'/dev.omalauncher.phone.PhoneService'
# After an update Android can leave phone control switched on and never bound: it starts binding the new build,
# stops the package once more, and does not try again. Turning the switch off and on mends it. The job does that,
# as Android's shell, only when the switch is still on and the service is not bound; switched off, it is left off.
# Switched off while the launcher's own record says Boss wants it on (a force-stop drops it from the list), the
# launcher puts it back itself at start and respects his own switch-off, so the job leaves it to the launcher.
# The working launcher is a debug build, so Android's shell can read that record with run-as.
# The log line it leaves is one of PHONE_CONTROL_OFF, _LEFT_TO_LAUNCHER, _BOUND, _HEALED, _STILL_DISCONNECTED.
# Android prints each bound service on a line of its own, so the whole block is read: with a second service
# bound (Oma Dev's), a look at the first line alone missed this one and turned a working service off and on.
HEAL=r'''  svc=__SERVICE__
  wanted() { run-as __PACKAGE__ cat shared_prefs/phone-control-heal.xml 2>/dev/null | grep -q 'name="wanted-since"'; }
  bound() { dumpsys accessibility | sed -n '/Bound services:/,/Enabled services:/p' | grep -q "id=$svc[,}]"; }
  tries=0
  until bound || [ $tries -ge 10 ]; do sleep 2; tries=$((tries+1)); done
  on=$(settings get secure enabled_accessibility_services)
  case ":$on:" in
  *":$svc:"*)
    if bound; then echo PHONE_CONTROL_BOUND
    else
      rest=""; old=$IFS; IFS=:
      for s in $on; do [ "$s" = "$svc" ] || rest="${rest:+$rest:}$s"; done
      IFS=$old
      if [ -n "$rest" ]; then settings put secure enabled_accessibility_services "$rest"; else settings delete secure enabled_accessibility_services >/dev/null; fi
      sleep 2
      settings put secure enabled_accessibility_services "$on"
      tries=0
      until bound || [ $tries -ge 10 ]; do sleep 2; tries=$((tries+1)); done
      if bound; then echo PHONE_CONTROL_HEALED; else echo PHONE_CONTROL_STILL_DISCONNECTED; fi
    fi ;;
  *) if wanted; then echo PHONE_CONTROL_LEFT_TO_LAUNCHER; else echo PHONE_CONTROL_OFF; fi ;;
  esac'''
def run(args, timeout=30):
 return subprocess.run(args,capture_output=True,text=True,timeout=timeout)
USAGE='Usage: oma-install [--no-open|--status] [--self] [--apk FILE]'
args=sys.argv[1:]
# --apk FILE: another APK than the launcher build: the launcher's, Soma Messages' (dev.omalauncher.messages) or Soma
# Notes' (dev.omalauncher.notes).
# --self: Oma installs it itself, through Android's installer, with no Wireless debugging (so off Wi-Fi too; Boss,
# 2026-10-22). Without --self that route is taken when the adb one can't be reached.
other=None
if '--apk' in args:
 i=args.index('--apk')
 if i+1>=len(args):sys.exit(USAGE)
 other=pathlib.Path(args[i+1]).resolve();del args[i:i+2]
self_route='--self' in args
args=[a for a in args if a!='--self']
if args not in ([],['--no-open'],['--status']):sys.exit(USAGE)
STATE=pathlib.Path('/root/.cache/oma-update/state.json')
def bridge(request,timeout=300):
 r=run(['curl','--silent','--show-error','--max-time',str(timeout),'--abstract-unix-socket',package+'.phone','--header','Content-Type: application/json','--data-binary',json.dumps(request),'http://localhost/phone'],timeout+10)
 if r.returncode:sys.exit('Oma did not answer on the phone bridge: '+(r.stderr.strip() or 'no reply')+'. Is Oma running?')
 return json.loads(r.stdout)
def self_status():
 if not STATE.exists():print('No update has gone through Oma itself yet.');return
 st=json.loads(STATE.read_text())
 told={'committed':'handed to Android\'s installer','confirming':'waiting for Boss to confirm on the phone','installed':'installed','failed':'failed'}
 print('Self-install of %s version %s: %s%s'%(st.get('package'),st.get('version'),told.get(st.get('status'),st.get('status')),(' ('+st['message']+')') if st.get('message') else ''))
if '--status' in args and self_route:self_status();sys.exit(0)
if other:apk=other
if not apk.is_file():sys.exit('No working-launcher APK found. Run oma-build first.' if not other else 'No APK at '+str(other)+'.')
r=run(['/opt/android-sdk/build-tools/36.0.0/aapt2','dump','badging',str(apk)])
m=re.search(r"package: name='([^']+)' versionCode='(\d+)'",r.stdout)
allowed=[package]+(['dev.omalauncher.messages','dev.omalauncher.notes'] if other else [])
if r.returncode or not m or m.group(1) not in allowed:sys.exit('Install refused: APK is not the working launcher'+(', Soma Messages or Soma Notes' if other else '')+'.')
target=m.group(1)
version=int(m.group(2))
r=run(['/opt/android-sdk/build-tools/36.0.0/apksigner','verify','--print-certs',str(apk)])
if r.returncode:sys.exit('Install refused: APK signature verification failed.')
m=re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)',r.stdout)
if not m or m.group(1)!=expected_cert:sys.exit('Install refused: signing identity does not match the working launcher.')
def install_self():
 # Staged in Linux's own files, which Oma reads as its own; Oma checks package, key and version again itself.
 stage=pathlib.Path('/root/.cache/oma-update');stage.mkdir(parents=True,exist_ok=True)
 for old in stage.glob('incoming-*.apk'):old.unlink()
 staged=stage/('incoming-%s-%d.apk'%(target,version))
 staged.write_bytes(apk.read_bytes())
 answer=bridge({'command':'self-install','path':str(staged)})
 if not answer.get('ok'):sys.exit('Not installed: '+str(answer.get('error')))
 if target==package:pathlib.Path('/root/.config/oma-development/next-version-code').write_text(str(version+1)+'\n')
 print('Handed to Android\'s installer through Oma (no Wi-Fi needed): %s version %d.'%(target,version),flush=True)
 if target==package:print('Oma restarts as it updates; Linux, login and settings stay. The first time, Android asks Boss to confirm on the phone. oma-install --self --status says how it went.',flush=True)
 else:print('oma-install --self --status says how it went.',flush=True)
 sys.exit(0)
if self_route:install_self()
run(['adb','start-server'])
def connect(port):
 endpoint='127.0.0.1:'+str(port)
 r=run(['adb','connect',endpoint],10)
 if r.returncode:return None
 r=run(['adb','-s',endpoint,'shell','getprop','ro.serialno'],10)
 return endpoint if r.returncode==0 and r.stdout.strip()==serial else None
endpoint=None
if cache.exists():
 try:endpoint=connect(int(cache.read_text().strip()))
 except (ValueError,subprocess.TimeoutExpired):pass
if not endpoint:
 # Android selects a new local ephemeral TLS port after debugging restarts.
 # Probe loopback only; authenticate and verify the physical serial before use.
 def probe(port):
  try:
   with socket.create_connection(('127.0.0.1',port),timeout=.03):return port
  except OSError:return None
 with concurrent.futures.ThreadPoolExecutor(max_workers=96) as pool:
  ports=[p for p in pool.map(probe,range(30000,65536)) if p]
 for port in ports:
  try:endpoint=connect(port)
  except subprocess.TimeoutExpired:continue
  if endpoint:cache.write_text(str(port));break
if not endpoint:
 if '--status' in args:self_status();sys.exit(0)
 print('Wireless debugging is not reachable (off Wi-Fi?): Oma installs it itself instead.',flush=True)
 install_self()
if target!=package:
 # Soma Messages or Notes over adb: an ordinary update (or first install), nothing restarts.
 r=run(['adb','-s',endpoint,'install','-r',str(apk)],300)
 sys.exit(0 if 'Success' in r.stdout and not print('Installed '+target+' version '+str(version)+' over adb.') else 'Install failed: '+(r.stdout+r.stderr).strip())
r=run(['adb','-s',endpoint,'shell','dumpsys','package',package])
m=re.search(r'versionCode=(\d+)',r.stdout)
if not m:sys.exit('Working launcher was not found; refusing a new installation.')
installed=int(m.group(1))
if '--status' in sys.argv:
 print('Working launcher installed version: '+str(installed))
 if pending.exists():
  plan=json.loads(pending.read_text())
  log=run(['adb','-s',endpoint,'shell','cat',plan['log']])
  print(log.stdout.strip() or 'Update has not completed yet.')
  if installed==plan['version'] and 'INSTALL_SUCCESS' in log.stdout:print('In-place launcher update verified.')
  for mark,told in [('PHONE_CONTROL_BOUND','Phone control reconnected by itself.'),('PHONE_CONTROL_HEALED','Phone control did not reconnect; the installer turned it off and on, and it is connected.'),('PHONE_CONTROL_STILL_DISCONNECTED','Phone control is DISCONNECTED: turning it off and on did not reconnect it. Boss needs to toggle it in Accessibility settings or restart the phone.'),('PHONE_CONTROL_LEFT_TO_LAUNCHER','Phone control was found switched off, but Boss had it on: left to the launcher, which turns it back on by itself.'),('PHONE_CONTROL_OFF','Phone control is switched off in Accessibility settings; left off.')]:
   if mark in log.stdout:print(told)
 sys.exit(0)
if version<=installed:
 sys.exit('Build a newer update first: APK version '+str(version)+', installed '+str(installed)+'. Run oma-build then oma-install.')
uid=uuid.uuid4().hex
remote='/data/local/tmp/oma-update-'+uid+'.apk'
remote_script='/data/local/tmp/oma-update-'+uid+'.sh'
log='/data/local/tmp/oma-update-'+uid+'.log'
script=pathlib.Path('/root/.config/oma-development/install-job.sh')
lines=['#!/system/bin/sh','sleep 10','if pm install -r '+shlex.quote(remote)+'; then','  echo INSTALL_SUCCESS']
if '--no-open' not in sys.argv:lines+=['  am start -n '+package+'/dev.omalauncher.MainActivity']
lines+=HEAL.replace('__SERVICE__',service).replace('__PACKAGE__',package).splitlines()
lines+=['else','  echo INSTALL_FAILED','fi','rm -f '+shlex.quote(remote)+' '+shlex.quote(remote_script)]
script.write_text('\n'.join(lines)+'\n')
for source,dest in [(apk,remote),(script,remote_script)]:
 r=run(['adb','-s',endpoint,'push',str(source),dest],120)
 if r.returncode:sys.exit('Could not stage the update; working launcher unchanged.')
pathlib.Path('/root/.config/oma-development/next-version-code').write_text(str(version+1)+'\n')
pending.write_text(json.dumps({'version':version,'log':log,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest()}))
command='nohup sh '+shlex.quote(remote_script)+' >'+shlex.quote(log)+' 2>&1 </dev/null &'
r=run(['adb','-s',endpoint,'shell',command])
if r.returncode:sys.exit('Could not start the independent installer; working launcher unchanged.')
print('Launcher update scheduled in 10 seconds. Android will restart Oma; Linux, login and settings are retained.',flush=True)
print('After reopening, oma-install --status checks the update result.',flush=True)
