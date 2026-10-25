import argparse
from pathlib import Path
import subprocess
import tempfile
import zipfile
from build import ROOT, VARIANTS, java_home


def dependency(group, artifact, version):
    directory = Path.home() / '.gradle/caches/modules-2/files-2.1' / group / artifact / version
    candidates = [p for p in directory.glob('**/*.jar') if p.name == artifact + '-' + version + '.jar']
    if not candidates:
        raise RuntimeError('Missing cached dependency: ' + str(directory))
    return candidates[0]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--variant', choices=[v['id'] for v in VARIANTS])
    parser.add_argument('--version', default='0.0.0-dev')
    args = parser.parse_args()
    import os
    if os.name != 'nt':
        import sys
        from verify_legacy_linux_runtime import main as verify_linux
        sys.argv = [sys.argv[0], '--version', args.version] + (['--variant', args.variant] if args.variant else [])
        return verify_linux()
    platform = 'windows-x64' if os.name == 'nt' else 'linux-x64'
    jna = dependency('net.java.dev.jna', 'jna', '5.12.1')
    logging = dependency('org.apache.logging.log4j', 'log4j-api', '2.17.2')
    separator = ';' if os.name == 'nt' else ':'
    selected = [v for v in VARIANTS if (args.variant is None or v['id'] == args.variant)
                and v.get('nativeBackend') != 'panama']
    if not selected:
        parser.error('Use the Java 25 Panama tests for the 26.x native backend')
    with tempfile.TemporaryDirectory(prefix='steambridge-smoke-') as temp:
        scratch = Path(temp)
        first = ROOT / 'dist' / platform / ('steambridge-' + selected[0]['id'] + '-' + args.version.lstrip('v') + '.jar')
        compile_classpath = separator.join(str(p) for p in [first, jna, logging])
        compiler = java_home(17) / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac')
        subprocess.run([str(compiler), '--release', '8', '-cp', compile_classpath, '-d', str(scratch),
                        str(ROOT / 'tools/smoke/NativeLinkSmoke.java')], check=True)
        for variant in selected:
            artifact = ROOT / 'dist' / platform / ('steambridge-' + variant['id'] + '-' + args.version.lstrip('v') + '.jar')
            with zipfile.ZipFile(artifact) as jar:
                nested = [name for name in jar.namelist() if name.startswith('META-INF/jars/jna-') and name.endswith('.jar')]
                if nested:
                    runtime_jna = scratch / (variant['id'] + '-jna.jar')
                    runtime_jna.write_bytes(jar.read(nested[0]))
                else:
                    version = '4.4.0' if variant['id'] == 'forge-1.16.5' else '5.12.1'
                    runtime_jna = dependency('net.java.dev.jna', 'jna', version)
            classpath = separator.join(str(p) for p in [artifact, runtime_jna, logging, scratch])
            java = java_home(variant['runtimeJava']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
            print(variant['id'], flush=True)
            subprocess.run([str(java), '-Djna.nosys=true', '-cp', classpath, 'steambridge.steam.NativeLinkSmoke'], check=True)


if __name__ == '__main__':
    main()
