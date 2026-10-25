from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import urllib.request

from build import ROOT


def lock():
    return json.loads((ROOT / 'tools/steamworks-sdk-1.62.json').read_text())


def mirror(cache, download):
    pinned = lock()
    directory = cache / ('steamworks-sdk-' + pinned['sdkVersion'] + '-' + pinned['commit'])

    def fetch(entry):
        path = directory / entry['file']
        if not path.is_file():
            if not download:
                raise RuntimeError('Missing SDK fixture; use --download: ' + str(path))
            url = pinned['repository'].replace('https://github.com/', 'https://raw.githubusercontent.com/')
            url += '/' + pinned['commit'] + '/' + entry['file']
            with urllib.request.urlopen(url, timeout=30) as response:
                payload = response.read()
            if hashlib.sha256(payload).hexdigest() != entry['sha256']:
                raise RuntimeError('SDK fixture checksum mismatch: ' + entry['file'])
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(payload)
        if hashlib.sha256(path.read_bytes()).hexdigest() != entry['sha256']:
            raise RuntimeError('SDK fixture checksum mismatch: ' + entry['file'])

    with ThreadPoolExecutor(max_workers=6) as pool:
        list(pool.map(fetch, pinned['files']))
    return directory / 'public/steam'


def provenance(headers, from_mirror):
    pinned = lock()
    hashes = {}
    for entry in pinned['files']:
        if not entry['file'].startswith('public/steam/') or not entry['file'].endswith('.h'):
            continue
        path = headers / Path(entry['file']).name
        payload = path.read_bytes()
        normalized = hashlib.sha256(payload.replace(b'\r\n', b'\n')).hexdigest()
        if normalized != entry['normalizedSha256']:
            raise RuntimeError('Header differs from pinned SDK 1.62 snapshot: ' + str(path))
        hashes[path.name] = dict(sha256=hashlib.sha256(payload).hexdigest(), normalizedSha256=normalized)
    dll = next(entry for entry in pinned['files'] if entry['file'].endswith('steam_api64.dll'))
    return dict(kind='pinned-sdk-mirror' if from_mirror else 'supplied-sdk-headers',
                sdkVersion=pinned['sdkVersion'], repository=pinned['repository'],
                commit=pinned['commit'], headers=str(headers), sha256=hashes,
                lineEndingsNormalizedForComparison=True, officialArchiveVerified=False,
                steamDllSha256=dll['sha256'])
