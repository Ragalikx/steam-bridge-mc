from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]


def generate(target, destination):
    if target not in ['26.1', '26.2', '26.3']:
        raise ValueError(target)
    namespace = 'steambridge.mc.v' + target.replace('.', '')
    source_root = ROOT / 'adapters/neoforge-26.x/src/main/java'
    files = list(source_root.rglob('*.java'))
    replacements = {}
    for path in files:
        relative = path.relative_to(source_root).with_suffix('').as_posix().replace('/', '.')
        replacements[relative] = namespace + relative[len('steambridge'):]
    for path in files:
        text = path.read_text(encoding='utf-8')
        for original, renamed in sorted(replacements.items(), key=lambda pair: -len(pair[0])):
            text = text.replace(original, renamed)
        text = re.sub(r'^package steambridge(.*?);', r'package ' + namespace + r'\1;', text, count=1, flags=re.M)
        imports = '\nimport steambridge.steam.*;\nimport steambridge.BuildInfo;\nimport steambridge.SteamBridgeConfig;\nimport steambridge.SteamAppIdHelper;\n'
        end = text.index(';', text.index('package ')) + 1
        text = text[:end] + imports + text[end:]
        if path.name == 'SteamBridgeMod.java':
            text = text.replace('@Mod(SteamBridgeMod.MODID)\n', '')
            text = text.replace('NeoForge.EVENT_BUS.register(new SteamClientEvents());',
                                'NeoForge.EVENT_BUS.register(new SteamClientEvents());\n        NeoForge.EVENT_BUS.register(steambridge.gui.VanillaGuiIntegration.class);'.replace('steambridge.gui', namespace + '.gui'))
        if path.name == 'VanillaGuiIntegration.java':
            text, count = re.subn(r'@EventBusSubscriber\(.*?\)\s*(?=public final class)', '', text, count=1, flags=re.S)
            if count != 1:
                raise RuntimeError('Version adapter subscriber annotation changed')
        if target != '26.1':
            text = re.sub(r'\.screen\b', '.gui.screen()', text)
            text = re.sub(r'\s*\.setScreen\(', '.gui.setScreen(', text)
            text = text.replace('ShareToLanScreen', 'MultiplayerOptionsScreen')
            text = text.replace('srv.publishServer(gameType, commands, port)',
                                'srv.publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN, gameType, commands, port)')
            text = text.replace('net.minecraft.client.resources.language.I18n.exists(', 'net.minecraft.locale.Language.getInstance().has(')
        if target == '26.3':
            text = text.replace('MultiplayerOptionsScreen', 'WorldOptionsScreen')
            text = text.replace('lanSetting(gui, "gameMode")', 'lanSetting(gui, "wantedDefaultGameMode")')
            text = text.replace('lanSetting(gui, "commands")', 'lanSetting(gui, "wantedGuestCommandAccess")')
            text = text.replace('srv.publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN, gameType, commands, port)',
                                'srv.publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN, commands, port)')
            text = text.replace('return srv.publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN, commands, port);', 'srv.executeBlocking(() -> srv.setDefaultGameType(gameType));\n        return srv.publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN, commands, port);')
        relative = path.relative_to(source_root / 'steambridge')
        output = destination / Path(namespace.replace('.', '/')) / relative
        output.parent.mkdir(parents=True, exist_ok=True)
        if not output.exists() or output.read_text(encoding='utf-8') != text:
            output.write_text(text, encoding='utf-8')


if __name__ == '__main__':
    generate(sys.argv[1], Path(sys.argv[2]))
