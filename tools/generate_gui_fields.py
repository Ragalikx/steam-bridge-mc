import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def render(target):
    imports = {'steambridge.core.NamedField', 'steambridge.core.LogLimiter', 'steambridge.core.ProblemLog', 'steambridge.SteamBridgeMod'}

    def name(value):
        if value in ['boolean', 'int']:
            return {'boolean': 'Boolean', 'int': 'Integer'}[value]
        value = value.replace('/', '.')
        if value.startswith('java.lang.'):
            return value.removeprefix('java.lang.')
        outer = value.split('$')[0]
        imports.add(outer)
        return outer.split('.')[-1] + ('.' + value.split('$', 1)[1] if '$' in value else '')

    fields = []
    for key, field in target['fields'].items():
        owner, type_name = name(field['owner']), name(field['type'])
        aliases = list(dict.fromkeys([field['name'], field['runtimeName']]))
        fields.append('    static final NamedField<' + type_name + '> ' + key
                      + ' = new NamedField<>(' + owner + '.class, ' + type_name + '.class, '
                      + ', '.join(json.dumps(alias) for alias in aliases) + ');')
    return ('package steambridge.gui;\n\n' + '\n'.join('import ' + value + ';' for value in sorted(imports))
            + '\n\nfinal class GuiFields {\n    private static final LogLimiter LOG_LIMITER = new LogLimiter(32, 30_000);\n' + '\n'.join(fields) + '''

    private GuiFields() {}

    static <T> T read(NamedField<T> field, Object owner) {
        try {
            return field.get(owner);
        } catch (RuntimeException failure) {
            ProblemLog.warn(SteamBridgeMod.LOG, LOG_LIMITER, "gui-field-access",
                    "GUI field access failed: {}", field, failure);
            return null;
        }
    }
}
''')


def generate(check=False):
    targets = json.loads((ROOT / 'tools/gui-fields.json').read_text())
    for variant, target in targets.items():
        path = ROOT / 'adapters' / variant / 'src/main/java/steambridge/gui/GuiFields.java'
        source = render(target)
        if check:
            if not path.is_file() or path.read_text(encoding='utf-8') != source:
                raise RuntimeError('GUI bindings differ from metadata: ' + variant)
        else:
            path.write_text(source, encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    generate(parser.parse_args().check)
