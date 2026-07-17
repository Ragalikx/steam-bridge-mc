# -*- coding: utf-8 -*-
"""Remove Spacewar/Family detection, re-exclude SteamApps, fix host_steam_launching color."""
import re
import subprocess
from pathlib import Path

BRANCHES = [
    "Forge-1.7.10",
    "Forge-1.8.9",
    "Forge-1.12.2",
    "Forge-1.16.5",
    "Forge-1.19.2",
    "Fabric-1.16.5",
    "Fabric-1.19.2",
    "Fabric-1.20.1",
    "Fabric-1.21.1",
    "NeoForge-1.20.1",
    "NeoForge-1.21.1",
]

# Lang strings: embed §e at start of each sentence so chat wrap keeps yellow.
HOST_EN = (
    "§eSteam is not running; attempting to start it automatically. "
    "§eWait a moment and click «Open via Steam» again."
)
HOST_RU = (
    "§eSteam не запущен; пробуем запустить его автоматически. "
    "§eПодождите немного и нажмите «Открыть для Steam» снова."
)
HOST_UK = (
    "§eSteam не запущено; пробуємо запустити його автоматично. "
    "§eЗачекайте трохи і натисніть «Відкрити для Steam» знову."
)

ERROR_KEYS = [
    "steambridge.error.native_load",
    "steambridge.error.no_steam_client",
    "steambridge.error.no_steam_client_hint",
    "steambridge.error.steam_version",
    "steambridge.error.spacewar_failed",
    "steambridge.error.spacewar_failed_hint",
    "steambridge.error.wrong_appid",
    "steambridge.error.family_or_license",
    "steambridge.error.family_or_license_hint",
    "steambridge.error.steam_init_failed",
]


def run(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, encoding="utf-8", errors="replace")


def strip_manager(t: str) -> str:
    if "verifySpacewarContext" not in t and "InitFailure" not in t and "initEx" not in t:
        # still ensure SteamApps import gone
        t = t.replace("import com.codedisaster.steamworks.SteamAPI.InitResult;\n", "")
        t = t.replace("import com.codedisaster.steamworks.SteamApps;\n", "")
        return t

    # Remove imports
    t = re.sub(r"import com\.codedisaster\.steamworks\.SteamAPI\.InitResult;\r?\n", "", t)
    t = re.sub(r"import com\.codedisaster\.steamworks\.SteamApps;\r?\n", "", t)
    # Keep SteamAppIdHelper import if still used for APP_ID logs - may not need

    # Remove InitFailure enum + lastInitFailure field
    t = re.sub(
        r"\n    public enum InitFailure \{.*?\n    \}\n+",
        "\n",
        t,
        count=1,
        flags=re.S,
    )
    t = re.sub(
        r"\n    private volatile InitFailure lastInitFailure = InitFailure\.NONE;\r?\n",
        "\n",
        t,
    )

    # Simplify init already-initialized block
    t = t.replace("lastInitFailure = InitFailure.NONE;\n            return true;", "return true;")
    t = t.replace("\n        lastInitFailure = InitFailure.NONE;\n\n        SteamBridgeMod", "\n\n        SteamBridgeMod")
    t = t.replace(
        '[SteamManager] Failed to load Steam native libraries.");\n            lastInitFailure = InitFailure.NATIVE_LOAD;\n            return false;',
        '[SteamManager] Failed to load Steam native libraries.");\n            return false;',
    )

    # Replace initEx block with simple init()
    new_init = """        SteamBridgeMod.LOG.info("[SteamManager] Calling SteamAPI.init()...");
        try {
            if (!SteamAPI.init()) {
                SteamBridgeMod.LOG.error(
                        "[SteamManager] SteamAPI.init() returned false. Check Steam and steam_appid.txt."
                );
                return false;
            }
        } catch (SteamException e) {
            SteamBridgeMod.LOG.error("[SteamManager] SteamAPI.init() threw: {}", e.getMessage());
            return false;
        }"""
    t2, n = re.subn(
        r'SteamBridgeMod\.LOG\.info\("\[SteamManager\] Calling SteamAPI\.initEx\(\).*?\);\s*'
        r"try \{\s*InitResult result = SteamAPI\.initEx\(\);.*?return false;\s*\}\s*"
        r"\} catch \(SteamException e\) \{\s*lastInitFailure = InitFailure\.UNKNOWN;\s*"
        r'SteamBridgeMod\.LOG\.error\("\[SteamManager\] SteamAPI\.initEx\(\) threw: \{\}", e\.getMessage\(\)\);\s*'
        r"return false;\s*\}",
        new_init,
        t,
        count=1,
        flags=re.S,
    )
    if n == 0:
        print("  WARN: initEx block not replaced")
    else:
        t = t2

    # Remove verifySpacewarContext call block
    t = re.sub(
        r"\n\s*if \(!verifySpacewarContext\(\)\) \{\s*"
        r"disposeSteamInterfaces\(\);\s*"
        r"SteamAPI\.shutdown\(\);\s*"
        r"return false;\s*"
        r"\}\s*",
        "\n",
        t,
        count=1,
        flags=re.S,
    )

    # Fix catch that uses disposeSteamInterfaces + lastInitFailure
    # Restore simple dispose of interfaces if disposeSteamInterfaces exists and is used in catch
    t = t.replace("lastInitFailure = InitFailure.UNKNOWN;\n            disposeSteamInterfaces();", "disposeSteamInterfaces();")
    t = t.replace(
        "initialized = true;\n        lastInitFailure = InitFailure.NONE;\n        running.set(true);",
        "initialized = true;\n        running.set(true);",
    )

    # Remove helper methods: disposeSteamInterfaces through getLastInitFailureHintKey
    # Keep disposeSteamInterfaces only if still referenced; after removing verify call,
    # dispose may only be used in catch - keep disposeSteamInterfaces method if referenced
    t = re.sub(
        r"\n    private void diagnoseInitFailure\(InitResult result\) \{.*?\n    \}\n",
        "\n",
        t,
        count=1,
        flags=re.S,
    )
    t = re.sub(
        r"\n    private boolean verifySpacewarContext\(\) \{.*?\n    \}\n",
        "\n",
        t,
        count=1,
        flags=re.S,
    )
    t = re.sub(
        r"\n    public InitFailure getLastInitFailure\(\) \{.*?\n    \}\n",
        "\n",
        t,
        count=1,
        flags=re.S,
    )
    t = re.sub(
        r"\n    /\*\* i18n key for the last init failure.*?\n    public String getLastInitFailureKey\(\) \{.*?\n    \}\n",
        "\n",
        t,
        count=1,
        flags=re.S,
    )
    t = re.sub(
        r"\n    public String getLastInitFailureKey\(\) \{.*?\n    \}\n",
        "\n",
        t,
        count=1,
        flags=re.S,
    )
    t = re.sub(
        r"\n    /\*\* Short second-line hint key.*?\n    public String getLastInitFailureHintKey\(\) \{.*?\n    \}\n",
        "\n",
        t,
        count=1,
        flags=re.S,
    )
    t = re.sub(
        r"\n    public String getLastInitFailureHintKey\(\) \{.*?\n    \}\n",
        "\n",
        t,
        count=1,
        flags=re.S,
    )

    # If disposeSteamInterfaces is only used in catch, keep it. If nothing calls it, remove.
    if "disposeSteamInterfaces()" not in t.replace("private void disposeSteamInterfaces()", ""):
        t = re.sub(
            r"\n    private void disposeSteamInterfaces\(\) \{.*?\n    \}\n",
            "\n",
            t,
            count=1,
            flags=re.S,
        )
    else:
        # Expand catch that still uses disposeSteamInterfaces - ok
        # If catch still has old triple dispose, leave as is
        pass

    # Remove unused SteamAppIdHelper import if no longer referenced
    if "SteamAppIdHelper" not in t:
        t = re.sub(r"import steambridge\.SteamAppIdHelper;\r?\n", "", t)

    return t


def strip_appid_helper(t: str) -> str:
    # Remove isSteamClientProcessRunning method
    t2, n = re.subn(
        r"\n    /\*\*\n     \* Best-effort process probe.*?\n    public static boolean isSteamClientProcessRunning\(\) \{.*?\n    \}\n",
        "\n",
        t,
        count=1,
        flags=re.S,
    )
    if n == 0:
        t2, n = re.subn(
            r"\n    public static boolean isSteamClientProcessRunning\(\) \{.*?\n    \}\n",
            "\n",
            t,
            count=1,
            flags=re.S,
        )
    # Remove unused imports if any
    if "isSteamClientProcessRunning" not in t2:
        for imp in [
            "import java.io.BufferedReader;\n",
            "import java.io.InputStreamReader;\n",
            "import java.nio.charset.StandardCharsets;\n",
            "import java.util.Locale;\n",
            "import java.util.concurrent.TimeUnit;\n",
        ]:
            # only remove if not used elsewhere - Locale/TimeUnit might only be in that method
            pass
        # carefully remove imports only used by process probe
        if "BufferedReader" not in t2:
            t2 = t2.replace("import java.io.BufferedReader;\n", "")
        if "InputStreamReader" not in t2:
            t2 = t2.replace("import java.io.InputStreamReader;\n", "")
        if "StandardCharsets" not in t2:
            t2 = t2.replace("import java.nio.charset.StandardCharsets;\n", "")
        if "Locale" not in t2:
            t2 = t2.replace("import java.util.Locale;\n", "")
        if "TimeUnit" not in t2:
            t2 = t2.replace("import java.util.concurrent.TimeUnit;\n", "")
    return t2


def strip_resync(t: str) -> str:
    if "getLastInitFailure" not in t:
        return t
    # Detect I18n method and color style
    if "I18n.format(" in t:
        i18n = "format"
    elif "I18n.translate(" in t:
        i18n = "translate"
    else:
        i18n = "get"
    sec = "\\u00a7" if "\\u00a7" in t else "§"
    simple = (
        f'statusLine1 = "{sec}c" + I18n.{i18n}("steambridge.gui.resync_timeout");\n'
        f'            statusLine2 = "{sec}7" + I18n.{i18n}("steambridge.gui.resync_timeout_hint");'
    )
    t2, n = re.subn(
        r"SteamManager\.InitFailure fail = SteamManager\.getInstance\(\)\.getLastInitFailure\(\);\s*"
        r"if \(fail != null && fail != SteamManager\.InitFailure\.NONE\) \{.*?\n            \} else \{\s*"
        r"statusLine1 = .*?;\s*"
        r"statusLine2 = .*?;\s*"
        r"\}",
        lambda _m: simple,
        t,
        count=1,
        flags=re.S,
    )
    print(f"  resync strip n={n}")
    return t2 if n else t


def strip_host_initfailure(t: str) -> str:
    if "InitFailure" not in t and "SPACEWAR_FAILED" not in t:
        return t
    # Remove the hard-fail block for SPACEWAR etc before launch steam
    t2, n = re.subn(
        r"\s*SteamManager\.InitFailure fail = SteamManager\.getInstance\(\)\.getLastInitFailure\(\);\s*"
        r"//[^\n]*\n\s*//[^\n]*\n\s*"
        r"if \(fail == SteamManager\.InitFailure\.SPACEWAR_FAILED.*?return;\s*\}\s*",
        "\n",
        t,
        count=1,
        flags=re.S,
    )
    if n == 0:
        t2, n = re.subn(
            r"\s*SteamManager\.InitFailure fail = SteamManager\.getInstance\(\)\.getLastInitFailure\(\);\s*"
            r".*?if \(fail == SteamManager\.InitFailure\.SPACEWAR_FAILED.*?return;\s*\}\s*",
            "\n",
            t,
            count=1,
            flags=re.S,
        )
    print(f"  host InitFailure strip n={n}")
    return t2 if n else t


def fix_host_message_code(t: str) -> str:
    """Remove Java-side §e/§a/§c prefix for host_steam_launching only if lang embeds §e;
    keep other messages. Also ensure host_steam_launching uses formatting-friendly construction.
    """
    # 1.7 may use \u00A7e + I18n - leave prefix OR remove if we put §e in lang
    # Prefer: lang has §e, code still adds §e => double yellow ok in MC
    # For wrap fix, lang has multiple §e; code §e prefix is fine.

    # Fix cases where only part of message is colored: use explicit multi-part if split
    # Not needed if lang has §e per sentence.
    return t


def patch_lang_json(path: Path):
    t = path.read_text(encoding="utf-8")
    name = path.name.lower()
    host = HOST_RU if "ru" in name else HOST_UK if "uk" in name else HOST_EN
    # update host_steam_launching
    t2, n = re.subn(
        r'("steambridge\.gui\.host_steam_launching"\s*:\s*")[^"]*(")',
        r"\1" + host.replace("\\", "\\\\").replace('"', '\\"') + r"\2",
        t,
        count=1,
    )
    # host already has §e - use unicode escape for JSON safety
    host_json = host.replace("§", "\\u00a7")
    t2, n = re.subn(
        r'("steambridge\.gui\.host_steam_launching"\s*:\s*")[^"]*(")',
        lambda m: m.group(1) + host_json + m.group(2),
        t,
        count=1,
    )
    t = t2 if n else t
    # remove error keys
    for key in ERROR_KEYS:
        t = re.sub(rf'\s*"{re.escape(key)}"\s*:\s*"[^"]*"\s*,?', "", t)
    # clean double commas / trailing commas before }
    t = re.sub(r",\s*,", ",", t)
    t = re.sub(r",\s*}", "\n}", t)
    path.write_text(t, encoding="utf-8")
    print(f"  json {path.name}")


def patch_lang_file(path: Path):
    t = path.read_text(encoding="utf-8")
    name = path.name.lower()
    host = HOST_RU if "ru" in name else HOST_UK if "uk" in name else HOST_EN
    lines_out = []
    for line in t.splitlines():
        if line.startswith("steambridge.gui.host_steam_launching="):
            lines_out.append("steambridge.gui.host_steam_launching=" + host)
            continue
        if any(line.startswith(k + "=") for k in ERROR_KEYS):
            continue
        lines_out.append(line)
    path.write_text("\n".join(lines_out) + "\n", encoding="utf-8")
    print(f"  lang {path.name}")


def ensure_steamapps_exclude(build_gradle: str) -> str:
    if "SteamApps*" in build_gradle:
        return build_gradle
    # Insert near other steamworks excludes if present
    needle = "exclude 'com/codedisaster/steamworks/SteamUserStats*'"
    line = "    exclude 'com/codedisaster/steamworks/SteamApps*'\n"
    if needle in build_gradle:
        return build_gradle.replace(needle, needle + "\n" + line.rstrip("\n") if False else needle + "\n" + line.rstrip())
    # try after SteamUGC
    for n in [
        "exclude 'com/codedisaster/steamworks/SteamUGC*'",
        "exclude 'com/codedisaster/steamworks/SteamServer*'",
        "exclude 'com/codedisaster/steamworks/SteamRemoteStorage*'",
    ]:
        if n in build_gradle:
            return build_gradle.replace(n, n + "\n    exclude 'com/codedisaster/steamworks/SteamApps*'")
    # fabric/neo may use different jar packaging - search shade/jar block
    m = re.search(r"(exclude 'com/codedisaster/steamworks/Steam[A-Za-z]+\*'\n)", build_gradle)
    if m:
        # append after last such exclude
        lasts = list(re.finditer(r"exclude 'com/codedisaster/steamworks/Steam[A-Za-z]+\*'\n", build_gradle))
        if lasts:
            pos = lasts[-1].end()
            return build_gradle[:pos] + "    exclude 'com/codedisaster/steamworks/SteamApps*'\n" + build_gradle[pos:]
    print("  WARN: could not insert SteamApps exclude")
    return build_gradle


def process_branch(branch: str):
    print(f"\n==== {branch} ====")
    r = run(f"git checkout -f {branch}")
    if r.returncode != 0:
        print(r.stderr)
        return

    mp = Path("src/main/java/steambridge/steam/SteamManager.java")
    if mp.exists():
        t = strip_manager(mp.read_text(encoding="utf-8"))
        mp.write_text(t, encoding="utf-8")
        print("  manager")

    ah = Path("src/main/java/steambridge/SteamAppIdHelper.java")
    if ah.exists():
        ah.write_text(strip_appid_helper(ah.read_text(encoding="utf-8")), encoding="utf-8")
        print("  appid helper")

    rs = Path("src/main/java/steambridge/gui/GuiSteamResync.java")
    if rs.exists():
        rs.write_text(strip_resync(rs.read_text(encoding="utf-8")), encoding="utf-8")
        print("  resync")

    for vg in [
        Path("src/main/java/steambridge/gui/VanillaGuiIntegration.java"),
        Path("src/main/java/steambridge/proxy/ClientProxy.java"),
    ]:
        if vg.exists():
            t = vg.read_text(encoding="utf-8")
            t2 = strip_host_initfailure(t)
            t2 = fix_host_message_code(t2)
            # Ensure host_steam_launching messages: if code prepends color with only one §e,
            # also rewrite to use the lang string which has per-sentence §e, and avoid stripping.
            # For 1.7 ChatComponentText - use the string as-is WITHOUT stripping codes from I18n.
            # If code does "§e" + I18n and I18n already has §e, double is fine.
            # Fix: for host_started/failed keep as is; for host_steam_launching remove leading color
            # so only lang §e applies (cleaner), OR keep both.
            # 1.7 wrap issue: put §e in lang. Also force code to not use String without § on second line.
            if "host_steam_launching" in t2:
                # ensure code path that only uses format without color gets color from lang
                pass
            vg.write_text(t2, encoding="utf-8")
            print(f"  {vg.name}")

    bg = Path("build.gradle")
    if bg.exists():
        bg.write_text(ensure_steamapps_exclude(bg.read_text(encoding="utf-8")), encoding="utf-8")
        print("  build.gradle")

    lang_root = Path("src/main/resources")
    if lang_root.exists():
        for p in lang_root.rglob("*"):
            if not p.is_file():
                continue
            if p.suffix == ".json" and "lang" in str(p).replace("\\", "/"):
                patch_lang_json(p)
            elif p.suffix == ".lang":
                patch_lang_file(p)

    run("git add -A")
    st = run("git status --porcelain")
    if st.stdout.strip():
        run(
            'git commit -m "Remove Spacewar detection; exclude SteamApps; fix host launch message color."'
        )
        print("  committed")
    else:
        print("  nothing")


def main():
    for b in BRANCHES:
        process_branch(b)
    run("git checkout NeoForge-1.21.1")
    print("DONE")


if __name__ == "__main__":
    main()
