#!/usr/bin/env python3
"""Verify the checked-in source against AppAuth 0.11.1 and the single redaction patch."""
import pathlib
import subprocess
import sys

REVISION = '5966cc7efc1c7cacf78598ecae45b5e7d9365f9c'
ROOT = pathlib.Path(__file__).resolve().parent
if len(sys.argv) != 2:
    raise SystemExit('Usage: verify-upstream.py <AppAuth-Android git checkout>')
repo = pathlib.Path(sys.argv[1]).resolve()

def git(*args):
    return subprocess.check_output(['git', '-C', str(repo), *args])
if git('rev-parse', f'{REVISION}^{{commit}}').decode().strip() != REVISION:
    raise SystemExit('Upstream commit is unavailable or changed')
paths = git('ls-tree', '-r', '--name-only', REVISION, '--', 'library/java', 'library/res', 'library/AndroidManifest.xml', 'LICENSE').decode().splitlines()
expected = {}
for path in paths:
    dest = path.removeprefix('library/')
    content = git('show', f'{REVISION}:{path}')
    if path == 'library/java/net/openid/appauth/AuthorizationManagementActivity.java':
        old = b'''                Logger.warn("State returned in authorization response (%s) does not match state "\n                        + "from request (%s) - discarding response",\n                        response.getState(),\n                        mAuthRequest.getState());'''
        new = b'''                // Privacy Bubble: do not log either secret transaction state value.\n                Logger.warn("Authorization response state mismatch - discarding response");'''
        if content.count(old) != 1: raise SystemExit('Expected upstream log patch point missing or ambiguous')
        content = content.replace(old, new)
    expected[dest] = content
actual = {str(p.relative_to(ROOT)) for sub in ('java', 'res') for p in (ROOT / sub).rglob('*') if p.is_file()}
actual.update(('AndroidManifest.xml', 'LICENSE'))
if actual != set(expected):
    raise SystemExit(f'Upstream file set mismatch: {sorted(actual ^ set(expected))}')
for path, content in expected.items():
    if (ROOT / path).read_bytes() != content:
        raise SystemExit(f'Unexpected source difference: {path}')
print(f'PASS: {len(expected)} upstream files at {REVISION}; only the approved log patch differs')
