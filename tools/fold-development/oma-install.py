#!/usr/bin/env python3
import concurrent.futures, json, pathlib, re, socket, subprocess, sys, hashlib, shlex, uuid
repo=pathlib.Path('/root/Projects/omalauncher')
apk=repo/'app/build/outputs/apk/debug/app-debug.apk'
package='dev.omalauncher.boss'
expected_cert=pathlib.Path('/root/.config/oma-development/working-cert.sha256').read_text().strip()
pending=pathlib.Path('/root/.config/oma-development/pending-install.json')
serial=json.loads(pathlib.Path('/root/.config/oma-development/device.json').read_text())['serial']
cache=pathlib.Path('/root/.config/oma-development/install-port')
def run(args, timeout=30):
 return subprocess.run(args,capture_output=True,text=True,timeout=timeout)
if sys.argv[1:] not in ([],['--no-open'],['--status']):sys.exit('Usage: oma-install [--no-open|--status]')
if not apk.is_file():sys.exit('No working-launcher APK found. Run oma-build first.')
r=run(['/opt/android-sdk/build-tools/36.0.0/aapt2','dump','badging',str(apk)])
m=re.search(r"package: name='([^']+)'",r.stdout)
if r.returncode or not m or m.group(1)!=package:sys.exit('Install refused: APK is not the working launcher package.')
r=run(['/opt/android-sdk/build-tools/36.0.0/apksigner','verify','--print-certs',str(apk)])
if r.returncode:sys.exit('Install refused: APK signature verification failed.')
m=re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)',r.stdout)
if not m or m.group(1)!=expected_cert:sys.exit('Install refused: signing identity does not match the working launcher.')
metadata=json.loads((apk.parent/'output-metadata.json').read_text())
version=int(metadata['elements'][0]['versionCode'])
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
if not endpoint:sys.exit('Local installer is disconnected. Enable Wireless debugging on the Fold while on Wi-Fi, then retry oma-install. Pair again if Android forgot this installer.')
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
