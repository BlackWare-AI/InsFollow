"""Verify built APK identity; never inspect application/user data."""
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys

apk = Path(sys.argv[1])
sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
tools = sorted((sdk / 'build-tools').glob('*/aapt'), key=lambda p: tuple(int(x) for x in p.parent.name.split('.') if x.isdigit()))
metadata = subprocess.check_output([str(tools[-1]), 'dump', 'badging', str(apk)], text=True)
expected = {'name': 'ai.blackware.insfollow', 'versionCode': '1', 'versionName': '0.1'}
first_line = metadata.splitlines()[0]
for key, value in expected.items():
    if re.search(rf"\b{key}='{re.escape(value)}'", first_line) is None:
        raise SystemExit(f'Unexpected APK {key}')
print(f'{apk.name}: {apk.stat().st_size} bytes; SHA-256 {hashlib.sha256(apk.read_bytes()).hexdigest()}')
