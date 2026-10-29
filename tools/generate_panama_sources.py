from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def generate(destination):
    files = list((ROOT / 'runtime/src/main/java').rglob('*.java'))
    for area in ['voice', 'proxy']:
        files.extend((ROOT / 'steam-jna/src/main/java/steambridge' / area).glob('*.java'))
    files.extend(ROOT / 'steam-jna/src/main/java/steambridge' / name for name in [
        'SteamBridgeConfig.java', 'steam/SteamConnectionStatus.java'])
    for source in files:
        relative = source.relative_to(next(parent for parent in source.parents if parent.name == 'java'))
        text = source.read_text(encoding='utf-8').replace('com.codedisaster.steamworks.', 'steambridge.panama.')
        if source.name == 'SteamRuntime.java':
            for name in ['SteamAuth', 'SteamAuthTicket', 'SteamException', 'SteamFriendsCallback',
                         'SteamResult', 'SteamUserCallback', 'SteamUtilsCallback']:
                text = text.replace('import steambridge.panama.' + name + ';\n', '')
            for name in ['SteamUserCallbackAdapter', 'SteamFriendsCallbackAdapter', 'SteamUtilsCallbackAdapter']:
                start = text.index('    private ' + ('static class ' if name == 'SteamUserCallbackAdapter' else 'class ') + name)
                opening = text.index('{', start)
                depth, end = 1, opening + 1
                while depth:
                    depth += (text[end] == '{') - (text[end] == '}')
                    end += 1
                text = text[:start] + text[end:]
            text = text.replace('SteamAPI.loadLibraries(new SteamNativeLibraries())', 'SteamAPI.loadLibraries()')
            text = text.replace('catch (SteamException e)', 'catch (RuntimeException e)')
            text = text.replace(' throws SteamException', '')
            text = text.replace('SteamException | RuntimeException | LinkageError', 'RuntimeException | LinkageError')
            for name in ['SteamUser', 'SteamFriends', 'SteamUtils']:
                text = text.replace('new ' + name + '(new ' + name + 'CallbackAdapter())', 'new ' + name + '()')
            text = text.replace('mySteamID = steamUser.getSteamID();',
                                'mySteamID = steamUser.getSteamID();\n            SteamAPI.setListeners(this::invalidateProfile, this::handleSteamShutdown);\n            SteamAPI.setInviteListeners(this::receiveInvite, this::validateInvite);')
            pattern = r'                SteamNetConnectionStatusChangedCallback copy =.*?earlyStatuses.addLast\(copy\);'
            text, count = re.subn(pattern, '                earlyStatuses.addLast(event);', text, flags=re.S)
            if count != 1:
                raise RuntimeError('Shared callback ownership source changed; update Panama generation')
        if source.name == 'SteamProfileCache.java':
            text = text.replace('memory.buffer().getByteBuffer(0, bytes)', 'memory.buffer().asByteBuffer()')
        output = destination / relative
        output.parent.mkdir(parents=True, exist_ok=True)
        if not output.exists() or output.read_text(encoding='utf-8') != text:
            output.write_text(text, encoding='utf-8')


def generate_tests(destination):
    files = list((ROOT / 'steam-jna/src/test/java/steambridge/voice').glob('*.java'))
    files.append(ROOT / 'steam-jna/src/test/java/steambridge/proxy/SteamUdpProxyTest.java')
    for source in files:
        relative = source.relative_to(ROOT / 'steam-jna/src/test/java')
        output = destination / relative
        output.parent.mkdir(parents=True, exist_ok=True)
        text = source.read_text(encoding='utf-8').replace('com.codedisaster.steamworks.', 'steambridge.panama.')
        if not output.exists() or output.read_text(encoding='utf-8') != text:
            output.write_text(text, encoding='utf-8')


if __name__ == '__main__':
    import sys
    (generate_tests if '--tests' in sys.argv else generate)(Path(sys.argv[1]))
