import argparse
import json
import os
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET
from build import ROOT, gradle, java_home

MATRIX = {8: ('4.4.0', '4.0.23.Final'), 17: ('5.12.1', '4.1.97.Final'), 21: ('5.14.0', '4.1.97.Final')}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java', type=int, choices=MATRIX, action='append')
    args = parser.parse_args()
    results = []
    for runtime in args.java or MATRIX:
        jna, netty = MATRIX[runtime]
        gson = '2.2.4' if runtime == 8 else '2.8.9'
        os.environ['SBM_TEST_JAVA_HOME'] = str(java_home(runtime))
        code = gradle(ROOT, ['check', '--rerun-tasks'], 17,
                      ['-PtestJnaVersion=' + jna, '-PtestNettyVersion=' + netty, '-PtestGsonVersion=' + gson])
        suites = []
        for module in ['core', 'steam-jna', 'transport-netty', 'runtime']:
            source = ROOT / module / 'build'
            target = ROOT / 'build/test-reports' / ('java-' + str(runtime)) / module
            reports = source / 'reports/tests/test'
            if reports.is_dir():
                shutil.copytree(reports, target, dirs_exist_ok=True)
            for xml in (source / 'test-results/test').glob('TEST-*.xml'):
                suite = ET.parse(xml).getroot()
                suites.append({key: int(suite.get(key, '0')) for key in ['tests', 'failures', 'errors', 'skipped']})
        result = dict(java=runtime, jna=jna, netty=netty, gson=gson, exitCode=code,
                      **{key: sum(s[key] for s in suites) for key in ['tests', 'failures', 'errors', 'skipped']})
        results.append(result)
        print(json.dumps(result), flush=True)
        if code:
            break
    destination = ROOT / 'build/test-reports/results.json'
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(results, indent=2) + '\n', encoding='utf-8')
    return 1 if any(r['exitCode'] for r in results) else 0


if __name__ == '__main__':
    raise SystemExit(main())
