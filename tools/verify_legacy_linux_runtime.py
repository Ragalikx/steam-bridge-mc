import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import tomllib
import zipfile

from build import ROOT, VARIANTS, java_home
from build_legacy_runtimes import GROUPS
from verify_gui import classpath_file
from verify_native import dependency


def verify_artifact(path, group):
    lock = json.loads((ROOT / 'tools/linux-runtime-jna.json').read_text(encoding='utf-8'))
    with zipfile.ZipFile(path) as jar:
        classes = [name for name in jar.namelist() if name.endswith('.class')]
        if set(classes) != {'steambridge/linuxruntime/RuntimeBootstrap.class', 'steambridge/linuxruntime/RuntimeLoader.class'}:
            raise RuntimeError('Unexpected code in grouped Linux runtime: ' + str(classes))
        for name in classes:
            if int.from_bytes(jar.read(name)[6:8], 'big') != group['java'] + 44:
                raise RuntimeError('Incorrect grouped runtime Java target: ' + name)
        for item in lock['files']:
            if hashlib.sha256(jar.read(item['resource'].lstrip('/'))).hexdigest() != item['sha256']:
                raise RuntimeError('Incorrect grouped runtime native: ' + item['file'])
        if {name for name in jar.namelist() if name.endswith(('.so', '.dll', '.dylib'))} != {item['resource'].lstrip('/') for item in lock['files']}:
            raise RuntimeError('Unexpected grouped runtime natives')
        fabric = json.loads(jar.read('fabric.mod.json'))
        if fabric['depends']['minecraft'] != group['fabric'] or list(fabric['entrypoints']) != ['preLaunch']:
            raise RuntimeError('Incorrect Fabric preLaunch registration')
        name = 'META-INF/' + ('neoforge.mods.toml' if group['java'] == 21 else 'mods.toml')
        metadata = tomllib.loads(jar.read(name).decode('utf-8'))
        if metadata['mods'][0]['modId'] != 'steambridge_linux_runtime':
            raise RuntimeError('Incorrect grouped runtime mod id')
        if group['java'] == 8 and json.loads(jar.read('mcmod.info'))[0]['modid'] != 'steambridge_linux_runtime':
            raise RuntimeError('Incorrect legacy FML metadata')
    sha = hashlib.sha256(path.read_bytes()).hexdigest()
    if path.with_suffix('.jar.sha256').read_text().split()[0] != sha:
        raise RuntimeError('Grouped runtime checksum sidecar mismatch')
    return dict(file=path.name, sha256=sha, java=group['java'], variants=group['variants'])


def contains(path, name):
    if path.is_dir():
        return (path / name).is_file()
    if path.is_file() and path.suffix == '.jar':
        with zipfile.ZipFile(path) as jar:
            return name in jar.namelist()
    return False


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--variant', action='append', choices=[v['id'] for v in VARIANTS if v.get('nativeBackend') != 'panama'])
    parser.add_argument('--version', default='0.0.0-dev')
    args = parser.parse_args()
    selected = [v for v in VARIANTS if v.get('nativeBackend') != 'panama' and (not args.variant or v['id'] in args.variant)]
    results = []
    for variant in selected:
        group = next(group for group in GROUPS if variant['id'] in group['variants'])
        addon = ROOT / 'dist/addons' / ('steambridge-linux-runtime-java' + str(group['java']) + '-' + args.version.lstrip('v') + '.jar')
        record = verify_artifact(addon, group)
        artifact = ROOT / 'dist/windows-x64' / ('steambridge-' + variant['id'] + '-' + args.version.lstrip('v') + '.jar')
        paths = [Path(line) for line in classpath_file(variant['id']).read_text().splitlines()]
        if variant['id'].startswith('fabric-'):
            entrypoint, key = 'fabric', ''
            api = next(path for path in paths if contains(path, 'net/fabricmc/loader/api/entrypoint/PreLaunchEntrypoint.class'))
        else:
            entrypoint = ('cpw.mods.fml.common.Mod' if variant['id'] == 'forge-1.7.10' else
                          'net.neoforged.fml.common.Mod' if variant['id'] == 'neoforge-1.21.1' else 'net.minecraftforge.fml.common.Mod')
            key = 'modid' if variant['id'] in ['forge-1.7.10', 'forge-1.8.9', 'forge-1.12.2'] else 'value'
            api = next(path for path in paths if contains(path, entrypoint.replace('.', '/') + '.class'))
        with tempfile.TemporaryDirectory(prefix='sbm-legacy-runtime-') as temp:
            scratch = Path(temp)
            with zipfile.ZipFile(artifact) as jar:
                nested = [name for name in jar.namelist() if name.startswith('META-INF/jars/jna-') and name.endswith('.jar')]
                if nested:
                    jna = scratch / 'jna.jar'
                    payload = jar.read(nested[0])
                    jna.write_bytes(payload)
                    with zipfile.ZipFile(io.BytesIO(payload)) as native:
                        assert 'com/sun/jna/linux-x86-64/libjnidispatch.so' in native.namelist()
                elif 'com/sun/jna/Native.class' in jar.namelist():
                    assert 'com/sun/jna/linux-x86-64/libjnidispatch.so' in jar.namelist()
                    jna = artifact
                else:
                    jna = next(path for path in paths if contains(path, 'com/sun/jna/Native.class'))
            support = [path for path in paths if contains(path, 'net/neoforged/api/distmarker/Dist.class')]
            cp = os.pathsep.join(map(str, [artifact, jna, dependency('org.apache.logging.log4j', 'log4j-api', '2.17.2'), scratch] + support))
            compiler = java_home(max(17, variant['runtimeJava'])) / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac')
            subprocess.run([str(compiler), '--release', '8', '-cp', cp, '-d', temp,
                            str(ROOT / 'tools/smoke/NativeLinkSmoke.java'), str(ROOT / 'tools/smoke/LegacyLinuxRuntimeSmoke.java')], check=True)
            java = java_home(variant['runtimeJava']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
            subprocess.run([str(java), '-Djna.nosys=true', '-Duser.home=' + temp, '-cp', cp,
                            'steambridge.steam.LegacyLinuxRuntimeSmoke', str(addon), str(api), entrypoint, key], check=True)
        results.append(dict(variant=variant['id'], runtime=record, loaderApi=str(api), host='windows-x64' if os.name == 'nt' else 'linux-x64',
                            scope='Real loader annotation/interface contracts, packaged provider and host native linkage; no Minecraft loader startup or Steam initialization'))
    (ROOT / 'build/legacy-linux-runtime-results.json').write_text(json.dumps(results, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(results, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
