import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

from build import ROOT, VARIANTS, gradle, java_home
from generate_gui_fields import generate

TARGETS = json.loads((ROOT / 'tools/gui-fields.json').read_text())
CACHE = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches'


def classpath_file(variant):
    return ROOT / 'adapters' / variant / 'build/gui-classpath.txt'


def gradle_arguments(variant):
    return (['sbmGuiClasspath', '-I', str(ROOT / 'tools/gui-classpath.gradle')],
            ['-PguiClasspathOutput=' + str(classpath_file(variant))])


def contains_owners(path, owners):
    if not path.is_file() or path.suffix != '.jar':
        return False
    with zipfile.ZipFile(path) as jar:
        names = set(jar.namelist())
        return all(owner + '.class' in names for owner in owners)


def check_fields(path, fields, runtime=False):
    names = ['runtimeOwner', 'runtimeName', 'runtimeDescriptor'] if runtime else ['owner', 'name', 'descriptor']
    command = [str(java_home(25) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')),
               str(ROOT / 'tools/smoke/VerifyGuiFields.java'), str(path)]
    command += [field[name] for field in fields for name in names]
    subprocess.run(command, check=True)


def check_mapping_aliases(target, sources):
    aliases = {}
    namespace = target['runtimeNamespace']
    if namespace == 'intermediary':
        for line in Path(sources[0]['file']).read_text().splitlines()[1:]:
            parts = line.split('\t')
            if parts[0] == 'c':
                owner = parts[1:]
            elif parts[:2] == ['', 'f']:
                aliases[(owner[2], parts[5])] = (owner[1], parts[4])
    elif namespace == 'srg' and len(sources) == 1:
        for line in Path(sources[0]['file']).read_text().splitlines():
            if line.startswith('FD: '):
                parts = line.split()
                aliases[tuple(parts[2].rsplit('/', 1))] = tuple(parts[1].rsplit('/', 1))
    if aliases:
        for field in target['fields'].values():
            if aliases.get((field['owner'], field['name'])) != (field['runtimeOwner'], field['runtimeName']):
                raise RuntimeError('Runtime field alias differs from mappings: ' + field['owner'] + '.' + field['name'])


def verify_variant(variant):
    generate(check=True)
    target = TARGETS[variant]
    fields = list(target['fields'].values())
    owners = {field['owner'] for field in fields}
    candidates = [Path(line) for line in classpath_file(variant).read_text().splitlines()]
    jars = [path for path in candidates if contains_owners(path, owners)]
    if len(jars) != 1:
        raise RuntimeError('Expected one Minecraft classpath JAR: ' + variant + ' ' + str(jars))
    check_fields(jars[0], fields)
    sources = []
    for source in target['mappingSources']:
        path = CACHE / source['cachePath']
        matches = [path] if path.is_file() else list(CACHE.rglob(path.name))
        match = next((candidate for candidate in matches
                      if hashlib.sha256(candidate.read_bytes().replace(b'\r\n', b'\n')).hexdigest()
                      == source['sha256']), None)
        if match is None:
            # Generated NeoForm mapping cache keys may depend on the host path.
            if path.name.endswith('_srgToOfficial.srg'):
                match = next((candidate for candidate in (CACHE / 'neoformruntime/intermediate_results').glob('*_srgToOfficial.srg')
                              if hashlib.sha256(candidate.read_bytes().replace(b'\r\n', b'\n')).hexdigest()
                              == source['sha256']), None)
        if match is None:
            raise RuntimeError('Pinned runtime mappings missing or changed: ' + str(path))
        sources.append(dict(file=str(match), sha256=source['sha256']))
    check_mapping_aliases(target, sources)
    runtime_jar = None
    if target['runtimeNamespace'] == 'intermediary':
        version = variant.split('-', 1)[1]
        candidates = (CACHE / 'fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-intermediary').glob(version + '-*/*.jar')
        runtime_jar = next((path for path in candidates if contains_owners(path, {f['runtimeOwner'] for f in fields})), None)
        if runtime_jar is None:
            raise RuntimeError('Missing Fabric intermediary JAR: ' + variant)
    elif variant == 'forge-1.16.5':
        runtime_jar = CACHE / 'forge_gradle/mcp_repo/net/minecraft/joined/1.16.5-20210115.111550/joined-1.16.5-20210115.111550-srg.jar'
    elif target['runtimeNamespace'] == 'named':
        runtime_jar = jars[0]
    if runtime_jar is not None:
        check_fields(runtime_jar, fields, runtime=True)
    result = dict(variant=variant, fields=len(fields), namedJar=str(jars[0]),
                  runtimeNamespace=target['runtimeNamespace'], mappingSources=sources,
                  runtimeJar=str(runtime_jar) if runtime_jar else None,
                  scope='Class fields and pinned runtime aliases; no Minecraft screen or loader execution')
    output = ROOT / 'adapters' / variant / 'build/gui-contract-results.json'
    output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print('GUI contracts passed: ' + variant, flush=True)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--variant', action='append', choices=list(TARGETS))
    parser.add_argument('--refresh-classpath', action='store_true')
    args = parser.parse_args()
    results = []
    for variant in args.variant or TARGETS:
        if args.refresh_classpath or not classpath_file(variant).is_file():
            tasks, properties = gradle_arguments(variant)
            java = next(v['buildJava'] for v in VARIANTS if v['id'] == variant)
            if gradle(ROOT / 'adapters' / variant, tasks, java, properties):
                return 1
        results.append(verify_variant(variant))
    (ROOT / 'build/gui-contract-results.json').write_text(json.dumps(results, indent=2) + '\n', encoding='utf-8')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
