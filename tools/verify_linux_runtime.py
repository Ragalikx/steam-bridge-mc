import argparse
import hashlib
import json
import os
import re
import subprocess
import struct
import tempfile
import tomllib
import zipfile

from build import ROOT, java_home


def elf_exports(payload):
    offset = struct.unpack_from('<Q', payload, 40)[0]
    entry_size, count = struct.unpack_from('<HH', payload, 58)
    sections = [struct.unpack_from('<IIQQQQIIQQ', payload, offset + i * entry_size) for i in range(count)]
    names = set()
    for section in sections:
        if section[1] != 11:
            continue
        strings = sections[section[6]]
        table = payload[strings[4]:strings[4] + strings[5]]
        for offset in range(section[4], section[4] + section[5], section[9]):
            name, info, visibility, defined, _, _ = struct.unpack_from('<IBBHQQ', payload, offset)
            if defined and info >> 4 in [1, 2] and visibility & 3 in [0, 3]:
                names.add(table[name:table.index(b'\0', name)].decode('utf-8'))
    return names


def verify_artifact(path):
    lock = json.loads((ROOT / 'tools/linux-runtime.json').read_text(encoding='utf-8'))
    with zipfile.ZipFile(path) as jar:
        names = set(jar.namelist())
        resource = lock['resource'].lstrip('/')
        library = jar.read(resource)
        if len(library) != lock['bytes'] or hashlib.sha256(library).hexdigest() != lock['sha256']:
            raise RuntimeError('Linux runtime native checksum mismatch')
        if library[:6] != b'\x7fELF\x02\x01' or library[18:20] != b'\x3e\x00':
            raise RuntimeError('Linux runtime is not an x64 ELF')
        classes = {name for name in names if name.endswith('.class')}
        if classes != {'steambridge/linuxruntime/LinuxRuntime.class'}:
            raise RuntimeError('Unexpected code bundled in Linux runtime: ' + str(classes))
        if int.from_bytes(jar.read(next(iter(classes)))[6:8], 'big') != 69:
            raise RuntimeError('Linux runtime requires the Java 25 class target')
        natives = {name for name in names if name.endswith(('.so', '.dll', '.dylib'))}
        if natives != {resource}:
            raise RuntimeError('Unexpected natives in Linux runtime')
        metadata = tomllib.loads(jar.read('META-INF/neoforge.mods.toml').decode('utf-8'))
        if metadata['mods'][0]['modId'] != 'steambridge_linux_runtime':
            raise RuntimeError('Incorrect Linux runtime mod ID')
        requirements = {item['modId']: item for item in metadata['dependencies']['steambridge_linux_runtime']}
        if requirements['steambridge']['type'] != 'required':
            raise RuntimeError('Linux runtime must require SBM')
        for mod in ['minecraft', 'neoforge']:
            if requirements[mod]['versionRange'] != '[26.1,26.4)':
                raise RuntimeError('Incorrect Linux runtime version range')
        if not any(name.startswith('META-INF/licenses/') for name in names):
            raise RuntimeError('Missing Linux runtime license notices')
    return dict(file=path.name, path=path.relative_to(ROOT).as_posix(), bytes=path.stat().st_size,
                sha256=hashlib.sha256(path.read_bytes()).hexdigest(), api=lock['api'],
                nativeSha256=lock['sha256'], platform=lock['platform'], targets=['26.1', '26.2', '26.3'])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--version', default='0.0.0-dev')
    args = parser.parse_args()
    version = args.version.lstrip('v')
    artifact = ROOT / 'dist/addons' / ('steambridge-linux-runtime-neoforge-26.x-' + version + '.jar')
    result = verify_artifact(artifact)
    if artifact.with_suffix('.jar.sha256').read_text().split()[0] != result['sha256']:
        raise RuntimeError('Linux runtime sidecar checksum mismatch')
    main_jar = ROOT / 'dist/windows-x64' / ('steambridge-neoforge-26.x-' + version + '.jar')
    java = java_home(25) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
    with tempfile.TemporaryDirectory(prefix='steambridge-runtime-') as cache:
        javac = java.with_name('javac.exe' if os.name == 'nt' else 'javac')
        subprocess.run([str(javac), '-cp', str(main_jar), '-d', cache,
                        str(ROOT / 'tools/smoke/LinuxRuntimeSmoke.java')], check=True)
        process = subprocess.run([str(java), '--enable-native-access=ALL-UNNAMED', '-Duser.home=' + cache,
                                  '-cp', os.pathsep.join([str(main_jar), cache]),
                                  'steambridge.panama.LinuxRuntimeSmoke', str(artifact)],
                                 check=True, capture_output=True, text=True)
        print(process.stdout)
        exports = int(re.search(r'nativeExportChecks=(\d+)', process.stdout)[1])
        required = set(re.search(r'nativeExports=([^\r\n]+)', process.stdout)[1].split(','))
    lock = json.loads((ROOT / 'tools/linux-runtime.json').read_text(encoding='utf-8'))
    with zipfile.ZipFile(artifact) as jar:
        available = elf_exports(jar.read(lock['resource'].lstrip('/')))
    if not required <= available:
        raise RuntimeError('Linux ELF is missing exports: ' + str(sorted(required - available)))
    result.update(nativeSmokeHost='windows-x64' if os.name == 'nt' else 'linux-x64',
                  nativeExportChecks=exports,
                  linuxElfExportChecks=len(required),
                  scope='Packaged addon constructor, registration, extraction and host native exports; no NeoForge or Steam initialization')
    (ROOT / 'build/linux-runtime-results.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
