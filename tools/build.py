import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VARIANTS = json.loads((ROOT / 'variants.json').read_text(encoding='utf-8'))


def java_home(version):
    configured = os.environ.get('JAVA_HOME_' + str(version))
    candidates = [Path(configured)] if configured else []
    candidates.append(Path.home() / '.cache/steambridge-build' / ('jdk-' + str(version)))
    if os.environ.get('JAVA_HOME'):
        candidates.append(Path(os.environ['JAVA_HOME']))
    executable = 'java.exe' if os.name == 'nt' else 'java'
    for candidate in candidates:
        binary = candidate / 'bin' / executable
        if not binary.is_file():
            continue
        result = subprocess.run([str(binary), '-version'], capture_output=True, text=True)
        match = re.search(r'version "(?:1\.)?(\d+)', result.stderr)
        if result.returncode == 0 and match and int(match.group(1)) == version:
            return candidate.resolve()
    raise RuntimeError('Set JAVA_HOME_' + str(version) + ' to an installed JDK ' + str(version))


def gradle(directory, tasks, java, properties=()):
    env = dict(os.environ, JAVA_HOME=str(java_home(java)))
    wrapper = directory / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    if not wrapper.is_file() and directory == ROOT / 'steam-panama':
        wrapper = ROOT / 'adapters/neoforge-26.x' / wrapper.name
    command = ['cmd.exe', '/d', '/c', str(wrapper)] if os.name == 'nt' else ['bash', str(wrapper)]
    command += list(tasks) + ['--console=plain', '--no-daemon', '--max-workers=2'] + list(properties)
    installations = []
    for version in [8, 17, 21, 25]:
        try:
            installations.append(str(java_home(version)))
        except RuntimeError:
            pass
    command.append('-Porg.gradle.java.installations.paths=' + ','.join(installations))
    return subprocess.run(command, cwd=directory, env=env).returncode


def verify_jar(path, variant, platform):
    runtime = variant['runtimeJava']
    panama = variant.get('nativeBackend') == 'panama'
    natives = {'steam_api64.dll'} if panama else {'steam_api64.dll', 'steamworks4j64.dll'}
    with zipfile.ZipFile(path) as jar:
        names = set(jar.namelist())
        if variant['id'] in ['forge-1.7.10', 'forge-1.8.9', 'forge-1.12.2', 'neoforge-26.x']:
            unused = 'steambridge/core/NamedField'
            if unused + '.class' in names:
                raise RuntimeError('Unused GUI helper packaged: ' + str(path))
            for name in names:
                if name.endswith('.class') and unused.encode() in jar.read(name):
                    raise RuntimeError('Removed GUI helper is still referenced by ' + name)
        if panama and (any(name.startswith(('com/sun/jna/', 'com/codedisaster/')) for name in names) or 'steamworks4j64.dll' in names):
            raise RuntimeError('JNA/JNI backend packaged in the Panama mod')
        if any(name.endswith(('.so', '.dylib')) and name != 'com/sun/jna/linux-x86-64/libjnidispatch.so' for name in names):
            raise RuntimeError('Non-Windows native packaged: ' + str(path))
        bootstrap = 'com/sun/jna/linux-x86-64/libjnidispatch.so'
        if 'com/sun/jna/Native.class' in names and bootstrap not in names:
            raise RuntimeError('Missing Linux JNA bootstrap: ' + str(path))
        for name in names:
            if name.startswith('META-INF/jars/jna-') and name.endswith('.jar'):
                with zipfile.ZipFile(io.BytesIO(jar.read(name))) as nested:
                    if bootstrap not in nested.namelist():
                        raise RuntimeError('Missing nested Linux JNA bootstrap: ' + str(path))
        for name in natives:
            if name not in names:
                raise RuntimeError(str(path) + ' lacks ' + name)
        for name in ['steambridge/core/Attempt.class', 'steambridge/steam/SteamSocketsApi.class', 'steambridge/steam/LoopbackBridge.class', 'steambridge/steam/SteamRuntime.class', 'steambridge/steam/SteamClientBase.class', 'steambridge/steam/SteamServerBase.class']:
            if name not in names:
                raise RuntimeError(str(path) + ' lacks shared code: ' + name)
        if 'steambridge/voice/SimpleVoicePlugin.class' not in names:
            raise RuntimeError('Missing voice API integration: ' + str(path))
        if any(name.startswith(('de/maxhenkel/voicechat/api/', 'su/plo/', 'org/objectweb/asm/', 'kotlin/')) for name in names):
            raise RuntimeError('Optional integration dependencies were bundled: ' + str(path))
        for name in ['PlasmoIntegration', 'PlasmoClientAddon', 'PlasmoServerAddon', 'PlasmoUdpClient']:
            if 'steambridge/voice/' + name + '.class' not in names:
                raise RuntimeError('Missing Plasmo API integration: ' + str(path))
        if 'steambridge.voice.mixins.json' in names:
            raise RuntimeError('Obsolete Plasmo mixins packaged: ' + str(path))
        # Mixin refuses to load any ordinary class inside a declared mixin package.
        for config in [name for name in names if name.endswith('.mixins.json')]:
            package = json.loads(jar.read(config)).get('package', '').replace('.', '/') + '/'
            if package == '/':
                raise RuntimeError(config + ' declares no mixin package: ' + str(path))
            for name in names:
                if name.startswith(package) and name.endswith('.class'):
                    simple = name.rsplit('/', 1)[1]
                    if not any(part in simple for part in ('Mixin', 'Accessor', 'Invoker')):
                        raise RuntimeError(config + ' package also contains ' + name + ' in ' + str(path))
        if variant['id'].startswith('fabric-'):
            metadata = json.loads(jar.read('fabric.mod.json'))
            if 'steambridge.voice.SimpleVoicePlugin' not in metadata.get('entrypoints', {}).get('voicechat', []):
                raise RuntimeError('Voice plugin not registered: ' + str(path))
        for name in names:
            if name.startswith('steambridge/') and name.endswith('.class'):
                header = jar.read(name)[:8]
                if header[:4] != b'\xca\xfe\xba\xbe' or int.from_bytes(header[6:8], 'big') > runtime + 44:
                    raise RuntimeError('Wrong Java target: ' + name)
        if not any(name.startswith('META-INF/licenses/') for name in names):
            raise RuntimeError('Missing license notices: ' + str(path))


def code_fingerprint():
    paths = set()
    for root in ['core', 'steam-jna', 'steam-panama', 'transport-netty', 'runtime', 'integrations', 'adapters', 'build-logic', 'tools', '.github', 'gradle']:
        for path in (ROOT / root).rglob('*'):
            if path.is_file() and not any(part in ['build', '.gradle', '__pycache__', 'run'] for part in path.relative_to(ROOT).parts):
                paths.add(path)
    paths.update(ROOT / name for name in ['build.gradle', 'settings.gradle', 'gradle.properties', 'variants.json'])
    digest = hashlib.sha256()
    for path in sorted(paths):
        digest.update(path.relative_to(ROOT).as_posix().encode('utf-8') + b'\0')
        digest.update(path.read_bytes().replace(b'\r\n', b'\n') + b'\0')
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--variant', action='append', choices=[v['id'] for v in VARIANTS])
    parser.add_argument('--platform', default='windows-x64', choices=['windows-x64'])
    parser.add_argument('--version', default='0.0.0-dev')
    parser.add_argument('--compile-only', action='store_true')
    parser.add_argument('--shared-tests', action='store_true')
    args = parser.parse_args()
    if not re.fullmatch(r'v?[A-Za-z0-9][A-Za-z0-9._-]*', args.version):
        parser.error('Invalid version')
    if args.shared_tests:
        code = gradle(ROOT, ['check'], 17)
        if code:
            return code
    selected = [v for v in VARIANTS if args.variant is None or v['id'] in args.variant]
    destination = ROOT / 'dist' / args.platform
    destination.mkdir(parents=True, exist_ok=True)
    inputs_sha256 = code_fingerprint()
    records, failed, addons = [], [], []
    from build_legacy_runtimes import build_runtimes
    addons.extend(build_runtimes(args.version, [variant['id'] for variant in selected], args.compile_only))
    for variant in selected:
        print('\nBuilding ' + variant['id'], flush=True)
        if variant.get('nativeBackend') == 'panama':
            from build_26x import build_bundle
            artifact = build_bundle(args.version, args.compile_only)
            if args.compile_only:
                records.append(dict(variant, result='compiled'))
            else:
                verify_jar(artifact, variant, args.platform)
                addons.append(json.loads((destination / '26x-build-results.json').read_text(encoding='utf-8'))['linuxRuntime'])
                records.append(dict(variant, file=artifact.name, bytes=artifact.stat().st_size,
                                    sha256=hashlib.sha256(artifact.read_bytes()).hexdigest()))
            continue
        directory = ROOT / 'adapters' / variant['id']
        from verify_gui import TARGETS, gradle_arguments, verify_variant
        gui_tasks, gui_properties = gradle_arguments(variant['id'])
        code = gradle(directory, (['compileJava'] if args.compile_only else ['build']) + gui_tasks,
                      variant['buildJava'], ['-PmodVersion=' + args.version,
                                            '-PnativePlatform=' + args.platform] + gui_properties)
        if code:
            failed.append(variant['id'])
            continue
        if variant['id'] in TARGETS:
            verify_variant(variant['id'])
        if args.compile_only:
            records.append(dict(variant, result='compiled'))
            continue
        expected_version = args.version.lstrip('v') + '+mc' + variant['id'].split('-', 1)[1]
        candidates = list((directory / 'build/libs').glob('*' + expected_version + '.jar'))
        if len(candidates) != 1:
            raise RuntimeError('Expected one production jar for ' + variant['id'] + ': ' + str(candidates))
        source = candidates[0]
        verify_jar(source, variant, args.platform)
        target = destination / ('steambridge-' + variant['id'] + '-' + args.version.lstrip('v') + '.jar')
        shutil.copy2(source, target)
        digest = hashlib.sha256(target.read_bytes()).hexdigest()
        target.with_suffix('.jar.sha256').write_text(digest + '  ' + target.name + '\n', encoding='utf-8')
        records.append(dict(variant, file=target.name, bytes=target.stat().st_size, sha256=digest))
    if code_fingerprint() != inputs_sha256:
        raise RuntimeError('Code inputs changed during the build; rebuild the affected snapshot')
    result = dict(version=args.version, platform=args.platform, codeInputsSha256=inputs_sha256, dependencyLockSha256=hashlib.sha256((ROOT / "tools/locked-dependencies.json").read_bytes()).hexdigest(), results=records, addons=addons, failed=failed)
    manifest = ROOT / 'build/compile-results.json' if args.compile_only else destination / 'build-results.json'
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, indent=2))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
