import json
import os
from pathlib import Path
import re
import subprocess
import sys

from build import ROOT, gradle, java_home


def verify(headers, compiler_command, output):
    if gradle(ROOT / 'steam-panama', ['classes', 'extractNativeFixture'], 25,
              ['-PpythonExecutable=' + sys.executable]):
        raise RuntimeError('Panama ABI Java compilation failed')
    checks = json.loads((ROOT / 'tools/panama-abi.json').read_text())
    source = output / 'PanamaAbiProbe.java'
    statements = '\n'.join('System.out.println(' + json.dumps('value.' + item['key'] + '=')
                           + ' + (long)(' + item.get('platformJava', item['java']) + '));' for item in checks)
    source.write_text('''package steambridge.panama;
import java.lang.foreign.*;
import steambridge.steam.*;
public final class PanamaAbiProbe {
    private static int type(MemoryLayout layout) {
        if (layout == null) return 0;
        if (layout instanceof AddressLayout) return 1;
        if (layout.equals(ValueLayout.JAVA_BYTE)) return 2;
        if (layout.equals(ValueLayout.JAVA_INT)) return 3;
        if (layout.equals(ValueLayout.JAVA_LONG)) return 4;
        if (layout.equals(ValueLayout.JAVA_FLOAT)) return 5;
        throw new IllegalArgumentException("Unsupported FFM ABI type: " + layout);
    }
    public static void main(String[] args) {
        SteamPlatform platform = SteamPlatform.valueOf(args[0]);
''' + statements + '''
        var bindings = new java.util.HashMap<>(NativeBindings.all());
        bindings.put("FnSteamNetConnectionStatusChanged", SteamAPI.connectionStatusCallback());
        bindings.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
            String name = "function." + entry.getKey();
            FunctionDescriptor signature = entry.getValue();
            System.out.println(name + ".result=" + type(signature.returnLayout().orElse(null)));
            System.out.println(name + ".count=" + signature.argumentLayouts().size());
            for (int i = 0; i < signature.argumentLayouts().size(); i++)
                System.out.println(name + ".arg" + i + "=" + type(signature.argumentLayouts().get(i)));
        });
    }
}
''', encoding='utf-8')
    classes = ROOT / 'steam-panama/build/classes/java/main'
    java = java_home(25) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
    cp = os.pathsep.join(map(str, [classes, output]))
    javac = java.with_name('javac.exe' if os.name == 'nt' else 'javac')
    subprocess.run([str(javac), '-cp', str(classes), '-d', str(output), str(source)], check=True)
    profiles = {}
    for platform, name in [('windows-x64', 'WINDOWS_X64'), ('linux-x64', 'LINUX_X64')]:
        process = subprocess.run([str(java), '-cp', cp, 'steambridge.panama.PanamaAbiProbe', name],
                                 capture_output=True, text=True, check=True)
        profiles[platform] = {key: int(value) for key, value in
                             (line.split('=') for line in process.stdout.splitlines())}
    host = 'windows-x64' if os.name == 'nt' else 'linux-x64'
    expected = profiles[host]
    functions = sorted(key.removeprefix('function.').removesuffix('.count')
                       for key in expected if key.startswith('function.') and key.endswith('.count'))
    declarations = []
    names = []

    def emit(key, expression):
        name = 'SBM_CHECK_' + str(len(names))
        names.append(key)
        declarations.append('extern "C" const int ' + name + ' = ' + expression + ';')

    for item in checks:
        emit('value.' + item['key'], item['cpp'])
    for function in functions:
        signature = ('Signature<decltype(&' + function + ')>' if function.startswith('SteamAPI_')
                     else 'Signature<' + function + '>')
        emit('function.' + function + '.result', signature + '::result')
        emit('function.' + function + '.count', signature + '::count')
        for i in range(expected['function.' + function + '.count']):
            emit('function.' + function + '.arg' + str(i),
                 'AbiType<' + signature + '::Argument<' + str(i) + '>>::value')
    probe = output / 'PanamaAbiProbe.cpp'
    probe.write_text('''#include <stddef.h>
#include <tuple>
#include <type_traits>
#include "steam_api_flat.h"
static_assert(sizeof(void*) == 8, "x64 pointers required");
static_assert(sizeof(bool) == 1, "One-byte C++ bool required");
template<class T> struct AbiType {
    static_assert(std::is_pointer<T>::value || std::is_integral<T>::value
        || std::is_enum<T>::value || std::is_same<T, float>::value, "Unsupported C ABI type");
    static constexpr int value = std::is_pointer<T>::value ? 1 :
        std::is_same<T, float>::value ? 5 : sizeof(T) == 1 ? 2 :
        sizeof(T) == 4 ? 3 : sizeof(T) == 8 ? 4 : -1;
};
template<> struct AbiType<void> { static constexpr int value = 0; };
template<class T> struct AbiType<T&> { static constexpr int value = 1; };
template<class T> struct Signature;
template<class R, class... A> struct Signature<R(*)(A...)> {
    static constexpr int result = AbiType<R>::value;
    static constexpr int count = sizeof...(A);
    template<size_t I> using Argument = typename std::tuple_element<I, std::tuple<A...>>::type;
};
''' + '\n'.join(declarations) + '\n', encoding='utf-8')
    reports = {}
    for platform, target in [('windows-x64', 'x86_64-windows-gnu'), ('linux-x64', 'x86_64-linux-gnu')]:
        ir = output / ('panama-' + platform + '.ll')
        subprocess.run(compiler_command + ['-target', target, '-std=c++11',
                       '-S', '-emit-llvm', '-I', str(headers), '-I', str(headers.parent),
                       str(probe), '-o', str(ir)], check=True)
        actual = {names[int(index)]: int(value) for index, value in re.findall(
            r'@SBM_CHECK_(\d+) = [^\n]*?constant i32 (-?\d+)', ir.read_text())}
        expected = profiles[platform]
        if actual != expected:
            differences = {key: {'java': expected.get(key), 'sdk': actual.get(key)}
                           for key in expected.keys() | actual.keys() if expected.get(key) != actual.get(key)}
            raise RuntimeError('Panama ABI differs from SDK (' + platform + '): ' + json.dumps(differences))
        result = dict(java=25, platform=platform, checks=len(expected),
                      layoutAndConstants=len(checks), functions=len(functions), values=actual)
        (output / ('panama-' + platform + '-results.json')).write_text(json.dumps(result, indent=2) + '\n')
        reports[platform] = result
    result = dict(reports[host], verifiedPlatforms=list(reports))
    (output / 'panama-results.json').write_text(json.dumps(result, indent=2) + '\n')
    return result
