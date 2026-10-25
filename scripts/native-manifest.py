#!/usr/bin/env python3
import hashlib
import json
import pathlib
import subprocess
import sys
root = pathlib.Path(__file__).resolve().parent.parent


def verify():
    recorded = json.loads((root / 'app/src/main/assets/native-build.json').read_text())
    expected = {f'jniLibs/{abi}/lib{program}.so'
                for abi in ('arm64-v8a', 'armeabi-v7a', 'x86_64')
                for program in ('frpc', 'cloudflared')}
    actual = {p.relative_to(root / 'app/src/main').as_posix()
              for p in (root / 'app/src/main/jniLibs').glob('*/*.so')}
    if actual != expected or {row['path'] for row in recorded['binaries']} != expected:
        raise SystemExit('Native manifest must contain all six tunnel programs, and all six files must exist.')
    for row in recorded['binaries']:
        binary = root / 'app/src/main' / row['path']
        if binary.stat().st_size != row['size'] or hashlib.sha256(binary.read_bytes()).hexdigest() != row['sha256']:
            raise SystemExit('Native manifest mismatch: ' + row['path'])
    for patch in recorded['patches']:
        if hashlib.sha256((root / patch['file']).read_bytes()).hexdigest() != patch['sha256']:
            raise SystemExit('Native patch differs from manifest: ' + patch['file'])
    print('Verified six native tunnel programs and patch hashes.')


if sys.argv[1:] == ['--verify']:
    verify()
    raise SystemExit(0)
if sys.argv[1:]:
    raise SystemExit('Usage: native-manifest.py [--verify]')

manifest = {
    'ndk': '28.2.13676358', 'api': 33, 'go': subprocess.check_output(['go', 'version'], text=True).strip(),
    'patches': [{'file': 'scripts/patch-native.py', 'purpose': 'Honor cloudflared login --origincert and use the Android system DNS bridge for Go SRV/TXT/A/AAAA', 'sha256': hashlib.sha256((root / 'scripts/patch-native.py').read_bytes()).hexdigest()}],
    'sources': [
        {'name': 'frpc', 'version': '0.65.0', 'url': 'https://github.com/fatedier/frp/tree/v0.65.0', 'archiveSha256': 'bbec0d1855e66c96e3a79ff97b8c74d9b1b45ec560aa7132550254d48321f7de', 'license': 'Apache-2.0'},
        {'name': 'cloudflared', 'version': '2025.8.1', 'url': 'https://github.com/cloudflare/cloudflared/tree/2025.8.1', 'archiveSha256': '803d3e162dc91da6b3b4f7961f57c113fa818d45517758511f72d254d68a46ba', 'license': 'Apache-2.0'},
    ],
    'binaries': [
        {'path': p.relative_to(root / 'app/src/main').as_posix(), 'size': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
        for p in sorted((root / 'app/src/main/jniLibs').glob('*/*.so'))
    ],
}
(root / 'app/src/main/assets/native-build.json').write_text(json.dumps(manifest, indent=2) + '\n')
verify()
