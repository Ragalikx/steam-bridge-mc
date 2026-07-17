# -*- coding: utf-8 -*-
"""Fix host_steam_launching color on all branches; strip § from code prefixes where Style is used."""
import re
import subprocess
from pathlib import Path

# Lang without section signs when using ChatFormatting/Style (modern).
# For 1.7-1.12 keep §e per sentence in lang for wrap.
HOST_EN = "Steam is not running; attempting to start it automatically. Wait a moment and click «Open via Steam» again."
HOST_RU = "Steam не запущен; пробуем запустить его автоматически. Подождите немного и нажмите «Открыть для Steam» снова."
HOST_UK = "Steam не запущено; пробуємо запустити його автоматично. Зачекайте трохи і натисніть «Відкрити для Steam» знову."

HOST_EN_LEGACY = (
    "§eSteam is not running; attempting to start it automatically. "
    "§eWait a moment and click «Open via Steam» again."
)
HOST_RU_LEGACY = (
    "§eSteam не запущен; пробуем запустить его автоматически. "
    "§eПодождите немного и нажмите «Открыть для Steam» снова."
)
HOST_UK_LEGACY = (
    "§eSteam не запущено; пробуємо запустити його автоматично. "
    "§eЗачекайте трохи і натисніть «Відкрити для Steam» знову."
)


def run(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, encoding="utf-8", errors="replace")


def set_host_lang(path: Path, legacy: bool):
    t = path.read_text(encoding="utf-8")
    name = path.name.lower()
    if legacy:
        host = HOST_RU_LEGACY if "ru" in name else HOST_UK_LEGACY if "uk" in name else HOST_EN_LEGACY
    else:
        host = HOST_RU if "ru" in name else HOST_UK if "uk" in name else HOST_EN

    if path.suffix == ".lang":
        lines = []
        for line in t.splitlines():
            if line.startswith("steambridge.gui.host_steam_launching="):
                lines.append("steambridge.gui.host_steam_launching=" + host)
            else:
                lines.append(line)
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    else:
        host_json = host.replace("\\", "\\\\").replace('"', '\\"')
        # also allow unicode § form already in file
        t2, n = re.subn(
            r'("steambridge\.gui\.host_steam_launching"\s*:\s*")(?:\\u00a7e)?[^"]*(")',
            r"\1" + host_json + r"\2",
            t,
            count=1,
        )
        if n:
            path.write_text(t2, encoding="utf-8")


def fix_legacy_java(t: str) -> str:
    """1.7-1.12: ChatComponentText(I18n...) without leading §e prefix — color comes from lang."""
    # \u00A7e + I18n.format("host_steam_launching")
    t = re.sub(
        r'"\\u00A7e"\s*\+\s*net\.minecraft\.client\.resources\.I18n\.format\("steambridge\.gui\.host_steam_launching"\)',
        'net.minecraft.client.resources.I18n.format("steambridge.gui.host_steam_launching")',
        t,
    )
    t = re.sub(
        r'"\\u00a7e"\s*\+\s*I18n\.format\("steambridge\.gui\.host_steam_launching"\)',
        'I18n.format("steambridge.gui.host_steam_launching")',
        t,
    )
    t = re.sub(
        r'"§e"\s*\+\s*I18n\.format\("steambridge\.gui\.host_steam_launching"\)',
        'I18n.format("steambridge.gui.host_steam_launching")',
        t,
    )
    return t


def fix_116_java(t: str) -> str:
    """1.16: StringTextComponent/LiteralText parses § codes — use lang with §e only."""
    t = re.sub(
        r'new StringTextComponent\("\\u00a7e"\s*\+\s*I18n\.get\("steambridge\.gui\.host_steam_launching"\)\)',
        'new StringTextComponent(I18n.get("steambridge.gui.host_steam_launching"))',
        t,
    )
    t = re.sub(
        r'new LiteralText\("\\u00a7e"\s*\+\s*I18n\.translate\("steambridge\.gui\.host_steam_launching"\)\)',
        'new LiteralText(I18n.translate("steambridge.gui.host_steam_launching"))',
        t,
    )
    return t


def fix_modern_java(t: str) -> str:
    """1.19+: Component.literal does not parse § — use translatable + yellow style."""
    # ensure ChatFormatting import
    if "host_steam_launching" in t and "ChatFormatting" not in t:
        if "import net.minecraft.network.chat.Component;" in t:
            t = t.replace(
                "import net.minecraft.network.chat.Component;",
                "import net.minecraft.ChatFormatting;\nimport net.minecraft.network.chat.Component;",
            )
        elif "import net.minecraft.network.chat.Component" in t:
            pass

    # Component.literal("§e" + I18n.get("steambridge.gui.host_steam_launching"))
    patterns = [
        (
            r'Component\.literal\("§e"\s*\+\s*I18n\.get\("steambridge\.gui\.host_steam_launching"\)\)',
            'Component.translatable("steambridge.gui.host_steam_launching").withStyle(ChatFormatting.YELLOW)',
        ),
        (
            r'Component\.literal\("\\u00a7e"\s*\+\s*I18n\.get\("steambridge\.gui\.host_steam_launching"\)\)',
            'Component.translatable("steambridge.gui.host_steam_launching").withStyle(ChatFormatting.YELLOW)',
        ),
    ]
    for pat, repl in patterns:
        t2, n = re.subn(pat, repl, t)
        if n:
            t = t2
            print("  modern host color replaced", n)

    # Also fix host_started / host_failed for consistency if they use literal+§
    t = re.sub(
        r'Component\.literal\("§a"\s*\+\s*I18n\.get\("steambridge\.gui\.host_started"\)\)',
        'Component.translatable("steambridge.gui.host_started").withStyle(ChatFormatting.GREEN)',
        t,
    )
    t = re.sub(
        r'Component\.literal\("§c"\s*\+\s*I18n\.get\("steambridge\.gui\.host_failed"\)\)',
        'Component.translatable("steambridge.gui.host_failed").withStyle(ChatFormatting.RED)',
        t,
    )
    return t


LEGACY = {"Forge-1.7.10", "Forge-1.8.9", "Forge-1.12.2"}
V116 = {"Forge-1.16.5", "Fabric-1.16.5"}
MODERN = {
    "Forge-1.19.2",
    "Fabric-1.19.2",
    "Fabric-1.20.1",
    "Fabric-1.21.1",
    "NeoForge-1.20.1",
    "NeoForge-1.21.1",
}


def main():
    for b in list(LEGACY | V116 | MODERN):
        print(f"\n==== {b} ====")
        run(f"git checkout -f {b}")
        legacy = b in LEGACY
        for p in Path("src/main/resources").rglob("*"):
            if p.suffix in (".lang", ".json") and "lang" in str(p).replace("\\", "/"):
                if "host_steam" in p.read_text(encoding="utf-8") or True:
                    set_host_lang(p, legacy=legacy or b in V116)

        for jp in [
            Path("src/main/java/steambridge/gui/VanillaGuiIntegration.java"),
            Path("src/main/java/steambridge/proxy/ClientProxy.java"),
        ]:
            if not jp.exists():
                continue
            t = jp.read_text(encoding="utf-8")
            if b in LEGACY:
                t = fix_legacy_java(t)
            elif b in V116:
                t = fix_116_java(t)
            else:
                t = fix_modern_java(t)
            jp.write_text(t, encoding="utf-8")

        # ChatFormatting import for fabric yarn might be net.minecraft.util.Formatting in 1.16 yarn
        if b in MODERN:
            vg = Path("src/main/java/steambridge/gui/VanillaGuiIntegration.java")
            if vg.exists():
                t = vg.read_text(encoding="utf-8")
                if "ChatFormatting" in t and "import net.minecraft.ChatFormatting" not in t:
                    # fabric 1.19+ is net.minecraft.ChatFormatting same as mojmap
                    if "import net.minecraft.network.chat.Component;" in t:
                        t = t.replace(
                            "import net.minecraft.network.chat.Component;",
                            "import net.minecraft.ChatFormatting;\nimport net.minecraft.network.chat.Component;",
                        )
                        vg.write_text(t, encoding="utf-8")

        run("git add -A")
        st = run("git status --porcelain")
        if st.stdout.strip():
            run('git commit -m "Fix host Steam-launch chat color (style / per-sentence formatting)."')
            print("  committed")
        else:
            print("  nothing")

    run("git checkout NeoForge-1.21.1")
    print("DONE")


if __name__ == "__main__":
    main()
