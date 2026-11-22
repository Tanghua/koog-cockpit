"""Import a local voice-access.json into one explicitly selected debug device.
The file contains secrets: keep it outside Git. No credentials are printed.
"""
import argparse
import json
import subprocess
from pathlib import Path
from urllib.parse import urlsplit

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--config', required=True, type=Path)
parser.add_argument('--adb', default='adb')
args = parser.parse_args()
raw = args.config.read_bytes()
data = json.loads(raw)
origin = urlsplit(data['origin'])
if (origin.scheme != 'https' or not origin.hostname or origin.username or origin.password
        or origin.path not in ('', '/') or origin.query or origin.fragment):
    parser.error('origin must be an HTTPS origin without a path or embedded credentials')
for key in ('username', 'password'):
    if key in data and not isinstance(data[key], str):
        parser.error(key + ' must be a string')
pkg = 'com.qwenaudio.validation.koog'
base = [args.adb, '-s', args.serial]

def run(*command, **kwargs):
    subprocess.run(base + list(command), check=True, stdout=subprocess.DEVNULL, **kwargs)

run('shell', 'am', 'force-stop', pkg)
run('shell', 'run-as', pkg, 'mkdir', '-p', 'files')
run('shell', 'run-as', pkg, 'tee', 'files/voice-access.json', input=raw)
run('shell', 'run-as', pkg, 'chmod', '600', 'files/voice-access.json')
run('shell', 'am', 'start', '-W', '-n', pkg + '/cockpit.validation.ValidationActivity')
remaining = subprocess.check_output(base + ['shell', 'run-as', pkg, 'ls', 'files'])
if b'voice-access.json' in remaining.splitlines():
    raise SystemExit('Configuration was not imported. Check the HTTPS origin and Android Keystore availability.')
print('Local configuration imported; credentials were not printed. Allow microphone permission when prompted.')
