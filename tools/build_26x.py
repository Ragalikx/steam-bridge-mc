import hashlib
import json
import os
import re
import subprocess
from pathlib import Path
import sys
import zipfile

from build import ROOT, code_fingerprint, gradle, java_home


def write_jar(output, entries):
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix('.jar.tmp')
    with zipfile.ZipFile(temporary, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as jar:
        for name, data in sorted(entries.items()):
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            jar.writestr(entry, data, compresslevel=9)
    temporary.replace(output)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix('.jar.sha256').write_text(digest + '  ' + output.name + '\n', encoding='utf-8')
    return digest


def build_bundle(version='0.0.0-dev', compile_only=False):
    if not re.fullmatch(r'v?[A-Za-z0-9][A-Za-z0-9._-]*', version):
        raise ValueError('Invalid version')
    project = ROOT / 'adapters/neoforge-26.x'
    targets = json.loads((project / 'targets.json').read_text(encoding='utf-8'))
    properties = ['-PmodVersion=' + version, '-PpythonExecutable=' + sys.executable]
    if gradle(ROOT / 'steam-panama', ['test'], 25, ['-PpythonExecutable=' + sys.executable]):
        raise RuntimeError('Panama tests failed')
    jars = []
    linux_entries = None
    fingerprint = code_fingerprint()
    for target in targets:
        if gradle(project, ['compileJava', 'compileLinuxRuntimeJava'] if compile_only else ['build'], 25, properties + ['-PmcTarget=' + target]):
            raise RuntimeError('Minecraft adapter failed: ' + target)
        game = project / 'build/targets' / target / 'moddev/artifacts' / ('minecraft-patched-' + targets[target] + '.jar')
        java = java_home(25) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        subprocess.run([str(java), str(ROOT / 'tools/smoke/Verify26xContracts.java'), str(game), target], check=True)
        if not compile_only:
            candidates = list((project / 'build/targets' / target / 'libs').glob('steambridge-neoforge-26x-' + version.lstrip('v') + '+mc26x.jar'))
            if len(candidates) != 1:
                raise RuntimeError('Expected one internal target JAR: ' + target)
            jars.append((target, candidates[0]))
            addon = project / 'build/targets' / target / 'libs' / ('steambridge-linux-runtime-neoforge-26x-' + version.lstrip('v') + '+mc26x.jar')
            with zipfile.ZipFile(addon) as jar:
                contents = {name: jar.read(name) for name in jar.namelist() if not name.endswith('/')}
            if linux_entries is not None and contents != linux_entries:
                raise RuntimeError('Linux runtime differs between Minecraft targets: ' + target)
            linux_entries = contents
    if compile_only:
        return None
    if code_fingerprint() != fingerprint:
        raise RuntimeError('Code inputs changed during the 26.x build')
    destination = ROOT / 'dist/windows-x64'
    destination.mkdir(parents=True, exist_ok=True)
    output = destination / ('steambridge-neoforge-26.x-' + version.lstrip('v') + '.jar')
    entries = {}
    for target, source in jars:
        with zipfile.ZipFile(source) as jar:
            prefix = 'steambridge/mc/v' + target.replace('.', '') + '/'
            if not any(name.startswith(prefix) for name in jar.namelist()):
                raise RuntimeError('Missing internal adapter: ' + target)
            for name in jar.namelist():
                if name.endswith('/'):
                    continue
                data = jar.read(name)
                if name in entries and entries[name] != data:
                    raise RuntimeError('Different shared class/resource across targets: ' + name)
                entries[name] = data
    entries['META-INF/steambridge-targets.json'] = (json.dumps(targets, indent=2) + '\n').encode()
    forbidden = ('com/sun/jna/', 'com/codedisaster/', 'de/maxhenkel/voicechat/api/', 'su/plo/', 'kotlin/', 'steambridge/linuxruntime/')
    if any(name.startswith(forbidden) or name.endswith(('.so', '.dylib')) or name == 'steamworks4j64.dll' for name in entries):
        raise RuntimeError('Unexpected native backend or bundled API in 26.x JAR')
    if 'steam_api64.dll' not in entries or 'steambridge/panama/SteamLibrary.class' not in entries:
        raise RuntimeError('Missing Panama backend')
    digest = write_jar(output, entries)
    linux_output = ROOT / 'dist/addons' / ('steambridge-linux-runtime-neoforge-26.x-' + version.lstrip('v') + '.jar')
    write_jar(linux_output, linux_entries)
    linux = dict(file=linux_output.name, sha256=hashlib.sha256(linux_output.read_bytes()).hexdigest(), java=25, variants=['neoforge-26.x'])
    manifest = dict(file=output.name, bytes=output.stat().st_size, sha256=digest, targets=targets,
                    backend='panama', java=25, codeInputsSha256=fingerprint, linuxRuntime=linux)
    (destination / '26x-build-results.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(manifest, indent=2), flush=True)
    return output


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('--version', default='0.0.0-dev')
    parser.add_argument('--compile-only', action='store_true')
    arguments = parser.parse_args()
    build_bundle(arguments.version, arguments.compile_only)
