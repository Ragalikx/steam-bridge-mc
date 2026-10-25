import argparse
import hashlib
import json
import os
from pathlib import Path
import urllib.request
from build import ROOT, gradle


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--download', action='store_true')
    parser.add_argument('--panama', action='store_true')
    args = parser.parse_args()
    records = json.loads((ROOT / 'tools/voice-targets.json').read_text(encoding='utf-8'))
    cache = Path.home() / '.cache/steambridge-build/voice'
    cache.mkdir(parents=True, exist_ok=True)
    jars = []
    for record in records:
        path = cache / record['file']
        if not path.is_file():
            if not args.download:
                parser.error('Missing voice fixture; use --download: ' + str(path))
            with urllib.request.urlopen(record['url'], timeout=60) as response:
                payload = response.read()
            if hashlib.sha256(payload).hexdigest() != record['sha256']:
                raise RuntimeError('Voice fixture checksum mismatch: ' + record['file'])
            path.write_bytes(payload)
        if hashlib.sha256(path.read_bytes()).hexdigest() != record['sha256']:
            raise RuntimeError('Voice fixture checksum mismatch: ' + record['file'])
        jars.append(str(path))
    properties = ['-PvoiceTargetJars=' + os.pathsep.join(jars)]
    if args.panama:
        import sys
        code = gradle(ROOT / 'steam-panama', ['classes'], 25,
                      ['-PpythonExecutable=' + sys.executable])
        if code:
            return code
        properties.append('-PvoiceAdapterClasses=' + str(ROOT / 'steam-panama/build/classes/java/main'))
    code = gradle(ROOT, [':voice-contract:verifyVoiceTargets'], 17, properties)
    result = dict(exitCode=code, backend='panama' if args.panama else 'jna', fixtures=records,
                  scope='API linkage against pinned release JARs; no Minecraft/Steam session')
    output = ROOT / ('build/voice-panama-target-results.json' if args.panama else 'build/voice-target-results.json')
    output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    return code


if __name__ == '__main__':
    raise SystemExit(main())
