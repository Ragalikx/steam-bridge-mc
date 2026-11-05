import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

from build import ROOT, gradle, java_home
from build_26x import write_jar
from verify_native import dependency

GROUPS = json.loads((ROOT / 'tools/linux-runtime-groups.json').read_text(encoding='utf-8'))


def metadata(group, version):
    runtime_id = 'steambridge_linux_runtime'
    files = {}
    if group.get('fabric'):
        fabric = dict(schemaVersion=1, id=runtime_id, version=version,
                      name='SBM Linux Runtime', environment='client', authors=['Ragalikx'], license='MIT',
                      entrypoints=dict(preLaunch=['steambridge.linuxruntime.RuntimeBootstrap::initialize']),
                      depends=dict(fabricloader='>=0.14.9', steambridge='*', minecraft=group['fabric'], java='>=' + str(group['java'])))
        files['fabric.mod.json'] = (json.dumps(fabric, indent=2) + '\n').encode()
    neo = 'neoforge' in group
    loader = 'neoforge' if neo else 'forge'
    minecraft = group.get('neoforge', group.get('forge'))
    specification = ','.join('[' + v + ']' for v in minecraft)
    text = 'modLoader="javafml"\nloaderVersion="[1,)"\nlicense="MIT"\n\n'
    text += '[[mods]]\nmodId="' + runtime_id + '"\nversion="' + version + '"\ndisplayName="SBM Linux Runtime"\nauthors="Ragalikx"\ndisplayTest="IGNORE_ALL_VERSION"\n\n'
    for dependency_id, constraint in [('steambridge', '[0,)'), (loader, '[21.1,21.2)' if neo else '[36,)'), ('minecraft', specification)]:
        text += '[[dependencies.' + runtime_id + ']]\nmodId="' + dependency_id + '"\n'
        text += ('type="required"' if neo else 'mandatory=true') + '\nversionRange="' + constraint + '"\nordering="NONE"\nside="CLIENT"\n\n'
    files['META-INF/' + ('neoforge.mods.toml' if neo else 'mods.toml')] = text.encode()
    if group['java'] == 8:
        files['mcmod.info'] = json.dumps([dict(modid=runtime_id, name='SBM Linux Runtime', version=version,
                                             mcversion='', authorList=['Ragalikx'], description='Linux x64 Steam natives for SBM')], indent=2).encode()
    if group.get('forge'):
        # Forge 1.16.5+ loads every mod JAR as a resource pack and logs an error without pack.mcmeta.
        files['pack.mcmeta'] = (json.dumps(dict(pack=dict(description='SBM Linux Runtime', pack_format=6 if group['java'] == 8 else 9)), indent=2) + '\n').encode()
    files['META-INF/MANIFEST.MF'] = ('Manifest-Version: 1.0\r\nAutomatic-Module-Name: steambridge.linuxruntime\r\nImplementation-Vendor: Ragalikx\r\n\r\n').encode()
    return files


def build_runtimes(version, selected, compile_only=False):
    groups = [group for group in GROUPS if group['java'] != 25 and set(group['variants']) & set(selected)]
    if not groups:
        return []
    if gradle(ROOT, [':core:classes'], 17):
        raise RuntimeError('Linux runtime shared API compilation failed')
    asm = dependency('org.ow2.asm', 'asm', '9.11.1')
    native = dependency('com.code-disaster.steamworks4j', 'steamworks4j', '1.10.0')
    lock = json.loads((ROOT / 'tools/linux-runtime-jna.json').read_text(encoding='utf-8'))
    results = []
    for group in groups:
        target = group['java']
        classes = ROOT / 'build/linux-runtimes' / ('java' + str(target)) / 'classes'
        classes.mkdir(parents=True, exist_ok=True)
        compiler = java_home(max(target, 17)) / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac')
        source = ROOT / 'integrations/linux-runtime/src/main/java/steambridge/linuxruntime/RuntimeBootstrap.java'
        subprocess.run([str(compiler), '--release', str(target), '-cp', str(ROOT / 'core/build/classes/java/main'),
                        '-d', str(classes), str(source)], check=True)
        java = java_home(17) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        subprocess.run([str(java), '-cp', str(asm), str(ROOT / 'tools/smoke/GenerateRuntimeLoader.java'),
                        str(target), version.lstrip('v'), str(classes)], check=True)
        if compile_only:
            continue
        entries = metadata(group, version.lstrip('v'))
        for path in classes.rglob('*.class'):
            entries[path.relative_to(classes).as_posix()] = path.read_bytes()
        with zipfile.ZipFile(native) as jar:
            for item in lock['files']:
                payload = jar.read(item['file'])
                if hashlib.sha256(payload).hexdigest() != item['sha256']:
                    raise RuntimeError('JNA runtime native checksum mismatch: ' + item['file'])
                entries[item['resource'].lstrip('/')] = payload
        entries['META-INF/licenses/STEAMBRIDGE_MIT.txt'] = (ROOT / 'LICENSE').read_bytes()
        for path in (ROOT / 'adapters/neoforge-26.x/third_party_licenses').glob('*'):
            if path.is_file():
                entries['META-INF/licenses/' + path.name] = path.read_bytes()
        output = ROOT / 'dist/addons' / ('steambridge-linux-runtime-java' + str(target) + '-' + version.lstrip('v') + '.jar')
        digest = write_jar(output, entries)
        results.append(dict(file=output.name, path=output.relative_to(ROOT).as_posix(), java=target,
                            bytes=output.stat().st_size, sha256=digest, variants=group['variants'], backend='jna', api=1))
    return results
