#!/usr/bin/env python3
"""Verify every offline resource after AAPT packaging, not only the source tree."""
import hashlib
import json
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as apk:
    manifest = json.loads(apk.read('assets/site/android-asset-manifest.json'))
    for entry in manifest['files']:
        data = apk.read('assets/site/' + entry['path'])
        assert len(data) == entry['bytes'], entry['path']
        assert hashlib.sha256(data).hexdigest() == entry['sha256'], entry['path']
    assert 'classes.dex' in apk.namelist()
    print(f"APK verified: {len(manifest['files'])} offline assets and Android bytecode")
