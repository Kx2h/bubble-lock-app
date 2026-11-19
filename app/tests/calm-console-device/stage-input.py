"""Stage an explicitly supplied guardian password into the QA package only."""
import argparse
import getpass
import json
import os
from pathlib import Path
import subprocess

parser=argparse.ArgumentParser()
parser.add_argument('--run-id',default='calm-console-device')
args=parser.parse_args()
serial=os.environ.get('ANDROID_SERIAL')
if not serial:raise SystemExit('Set ANDROID_SERIAL to the authorized test phone.')
repo=Path(__file__).resolve().parents[3]
adb=os.environ.get('ADB',str(repo/'app/scripts/adb-windows.sh'))
password=getpass.getpass('Guardian password (empty reuses the existing session): ')
payload={'runId':args.run_id,'username':'guardian','password':password}
command="run-as org.privacybubble.designqa sh -c 'umask 077; mkdir -p files; cat > files/input.json'"
result=subprocess.run([adb,'-s',serial,'shell','-T',command],input=json.dumps(payload),text=True,capture_output=True)
password='';payload['password']=''
if result.returncode:raise SystemExit('Private input transfer failed; response omitted.')
print('Private input staged; no credentials in arguments or output.')
