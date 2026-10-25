import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import urllib.request
import zipfile
from build import ROOT, java_home, gradle
from verify_native import dependency


def compiler(cache, download):
    platform = 'x86_64-windows' if os.name == 'nt' else 'x86_64-linux'
    executable = 'zig.exe' if os.name == 'nt' else 'zig'
    path = cache / ('zig-' + platform + '-0.15.2') / executable
    if path.is_file():
        return path
    if not download:
        raise RuntimeError('Missing Zig; use --download or pass --compiler')
    lock = json.loads((ROOT / 'tools/abi-compilers.json').read_text())[platform]
    archive = cache / Path(lock['tarball']).name
    if not archive.is_file():
        with urllib.request.urlopen(lock['tarball'], timeout=60) as source:
            payload = source.read()
        if hashlib.sha256(payload).hexdigest() != lock['shasum']:
            raise RuntimeError('Compiler checksum mismatch')
        archive.write_bytes(payload)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != lock['shasum']:
        raise RuntimeError('Compiler checksum mismatch')
    if os.name == 'nt':
        with zipfile.ZipFile(archive) as package:
            for entry in package.infolist():
                if not (cache / entry.filename).resolve().is_relative_to(cache.resolve()):
                    raise RuntimeError('Unsafe compiler archive path')
            package.extractall(cache)
    else:
        with tarfile.open(archive) as package:
            for entry in package.getmembers():
                if not (cache / entry.name).resolve().is_relative_to(cache.resolve()):
                    raise RuntimeError('Unsafe compiler archive path')
                if entry.issym() or entry.islnk():
                    raise RuntimeError('Unexpected link in compiler archive')
            package.extractall(cache, filter='data')
    return path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sdk-headers', type=Path)
    parser.add_argument('--sdk-mirror', action='store_true')
    parser.add_argument('--panama', action='store_true')
    parser.add_argument('--download', action='store_true')
    parser.add_argument('--compiler', type=Path)
    args = parser.parse_args()
    if args.sdk_headers is not None and args.sdk_mirror:
        parser.error('Choose --sdk-headers or --sdk-mirror')
    if args.panama and args.sdk_headers is None and not args.sdk_mirror:
        parser.error('--panama requires SDK headers: use --sdk-mirror or --sdk-headers')
    if os.environ.get('SBM_ABI_BUILD') == '1':
        if gradle(ROOT, [':steam-jna:classes'], 17):
            raise RuntimeError('ABI Java compilation failed')
    lock = json.loads((ROOT / 'tools/abi-headers.json').read_text())
    cache = Path.home() / '.cache/steambridge-build/abi'
    cache.mkdir(parents=True, exist_ok=True)
    headers = args.sdk_headers or cache / ('headers-' + lock['commit'])
    sdk_source = None
    if args.sdk_mirror or args.sdk_headers is not None:
        import sdk_headers
        if args.sdk_mirror:
            headers = sdk_headers.mirror(cache, args.download)
        sdk_source = sdk_headers.provenance(headers, args.sdk_mirror)
    else:
        headers.mkdir(parents=True, exist_ok=True)
        for entry in lock['files']:
            path = headers / entry['file']
            if not path.is_file() and args.download:
                url = lock['repository'].replace('https://github.com/', 'https://raw.githubusercontent.com/')
                url += '/' + lock['commit'] + '/include/steam/' + entry['file']
                with urllib.request.urlopen(url, timeout=30) as response:
                    payload = response.read()
                if hashlib.sha256(payload).hexdigest() != entry['sha256']:
                    raise RuntimeError('Header checksum mismatch: ' + entry['file'])
                path.write_bytes(payload)
            if not path.is_file():
                parser.error('Missing ABI header; use --download: ' + str(path))
            if hashlib.sha256(path.read_bytes()).hexdigest() != entry['sha256']:
                raise RuntimeError('Header checksum mismatch: ' + entry['file'])
    binary = args.compiler or compiler(cache, args.download)
    command = [str(binary)] + (['c++'] if binary.name in ['zig', 'zig.exe'] else [])
    output = ROOT / 'build/abi'
    output.mkdir(parents=True, exist_ok=True)
    golden = json.loads((ROOT / 'tools/abi-layouts.json').read_text())
    layouts = {}
    for platform, target in [('windows-x64', 'x86_64-windows-gnu'), ('linux-x64', 'x86_64-linux-gnu')]:
        ir = output / (platform + '.ll')
        subprocess.run(command + ['-target', target, '-std=c++11', '-S', '-emit-llvm',
                       '-I', str(headers), str(ROOT / 'tools/smoke/NativeAbiProbe.cpp'), '-o', str(ir)], check=True)
        values = {a + '.' + b: int(c) for a, b, c in re.findall(
            r'@SBM_(\w+?)__(\w+) = [^\n]*?constant i32 (\d+)', ir.read_text())}
        if values != golden[platform]:
            differences = {key: [golden[platform].get(key), values.get(key)]
                           for key in golden[platform].keys() | values.keys()
                           if golden[platform].get(key) != values.get(key)}
            raise RuntimeError('C ABI mismatch: ' + json.dumps(differences))
        layouts[platform] = values
    jna = dependency('net.java.dev.jna', 'jna', '4.4.0')
    classes = ROOT / 'steam-jna/build/classes/java/main'
    cp = os.pathsep.join(str(p) for p in [jna, classes])
    javac = java_home(17) / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac')
    subprocess.run([str(javac), '--release', '8', '-cp', cp, '-d', str(output),
                    str(ROOT / 'tools/smoke/NativeAbiProbe.java'),
                    str(ROOT / 'tools/smoke/NativeJnaSignatures.java')], check=True)
    results = []
    signatures = []
    for runtime, version in [(8, '4.4.0'), (17, '5.12.1'), (21, '5.14.0')]:
        java = java_home(runtime) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        cp = os.pathsep.join(str(p) for p in [dependency('net.java.dev.jna', 'jna', version), classes, output])
        response = subprocess.run([str(java), '-Djna.nosys=true', '-cp', cp,
                                   'steambridge.steam.NativeAbiProbe'], check=True, capture_output=True, text=True)
        values = {key: int(value) for key, value in (line.split('=') for line in response.stdout.splitlines())}
        platform = 'windows-x64' if os.name == 'nt' else 'linux-x64'
        if values != layouts[platform]:
            raise RuntimeError('JNA ABI differs from C on Java ' + str(runtime))
        results.append(dict(java=runtime, jna=version, checks=len(values)))
        if args.panama:
            response = subprocess.run([str(java), '-Djna.nosys=true', '-cp', cp,
                                       'steambridge.steam.NativeJnaSignatures'],
                                      check=True, capture_output=True, text=True)
            signatures.append({key: int(value) for key, value in
                               (line.split('=') for line in response.stdout.splitlines())})
    source = sdk_source or lock
    result = dict(source=source, platforms=list(layouts), jna=results,
                  exactSdkHeaders=sdk_source is not None, officialArchiveVerified=False)
    if args.panama:
        from verify_panama_abi import verify
        panama = verify(headers, command, output)
        fixture = ROOT / 'steam-panama/build/fixtures/steam_api64.dll'
        if hashlib.sha256(fixture.read_bytes()).hexdigest() != source['steamDllSha256']:
            raise RuntimeError('SDK snapshot DLL differs from packaged native dependency')
        linux_lock = json.loads((ROOT / 'tools/linux-runtime.json').read_text())
        linux_fixture = ROOT / 'steam-panama/build/fixtures/libsteam_api.so'
        if hashlib.sha256(linux_fixture.read_bytes()).hexdigest() != linux_lock['sha256']:
            raise RuntimeError('SDK snapshot Linux library differs from runtime native dependency')
        panama['steamDllMatchesSdkSnapshot'] = True
        panama['steamLinuxMatchesSdkSnapshot'] = True
        result['panama'] = panama
        for jna, values in zip(results, signatures):
            differences = {key: {'jna': value, 'sdk': panama['values'].get(key)}
                           for key, value in values.items() if value != panama['values'].get(key)}
            if differences:
                raise RuntimeError('JNA signature differs from SDK on Java ' + str(jna['java'])
                                   + ': ' + json.dumps(differences))
            jna['signatureChecks'] = len(values)
            jna['functions'] = sum(key.endswith('.count') for key in values)
    (ROOT / 'build/abi-results.json').write_text(json.dumps(result, indent=2) + '\n')
    summary = dict(result)
    summary['source'] = {key: value for key, value in source.items() if key not in ['sha256', 'files']}
    if 'panama' in summary:
        summary['panama'] = {key: value for key, value in result['panama'].items() if key != 'values'}
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
