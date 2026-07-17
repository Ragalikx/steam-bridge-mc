# -*- coding: utf-8 -*-
"""Finish Spacewar diagnostics on current branch + remaining branches."""
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def run(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, encoding="utf-8", errors="replace")


def git_show(path):
    return subprocess.check_output(["git", "show", path], text=True, encoding="utf-8", errors="replace")


def ensure_helpers(manager_path: Path):
    t = manager_path.read_text(encoding="utf-8")
    if "private void disposeSteamInterfaces() {" in t:
        return t
    neo = git_show("NeoForge-1.21.1:src/main/java/steambridge/steam/SteamManager.java")
    i = neo.find("    private void disposeSteamInterfaces() {")
    j = neo.find("    public boolean reinit()")
    if i < 0 or j < 0:
        raise RuntimeError("helpers not found in neo")
    block = neo[i:j]
    if "public boolean reinit()" not in t:
        raise RuntimeError("reinit missing")
    t = t.replace("    public boolean reinit()", block + "    public boolean reinit()")
    return t


def ensure_imports(t: str) -> str:
    if "SteamAPI.InitResult" not in t and "import com.codedisaster.steamworks.SteamAPI.InitResult" not in t:
        t = t.replace(
            "import com.codedisaster.steamworks.SteamAPI;",
            "import com.codedisaster.steamworks.SteamAPI;\n"
            "import com.codedisaster.steamworks.SteamAPI.InitResult;\n"
            "import com.codedisaster.steamworks.SteamApps;",
        )
    if "import steambridge.SteamAppIdHelper" not in t:
        t = t.replace(
            "import steambridge.SteamBridgeMod;",
            "import steambridge.SteamAppIdHelper;\nimport steambridge.SteamBridgeMod;",
        )
    return t


def patch_resync(path: Path):
    if not path.exists():
        return
    t = path.read_text(encoding="utf-8")
    if "getLastInitFailure" in t:
        return
    if "I18n.format(" in t:
        i18n = "format"
    elif "I18n.translate(" in t:
        i18n = "translate"
    else:
        i18n = "get"
    use_escape = "\\u00a7" in t
    sec = "\\u00a7" if use_escape else "\u00a7"
    repl = (
        "SteamManager.InitFailure fail = SteamManager.getInstance().getLastInitFailure();\n"
        "            if (fail != null && fail != SteamManager.InitFailure.NONE) {\n"
        f'                statusLine1 = "{sec}c" + I18n.{i18n}(SteamManager.getInstance().getLastInitFailureKey());\n'
        "                String hintKey = SteamManager.getInstance().getLastInitFailureHintKey();\n"
        "                statusLine2 = hintKey.isEmpty()\n"
        f'                        ? "{sec}7" + I18n.{i18n}("steambridge.gui.resync_timeout_hint")\n'
        f'                        : "{sec}7" + I18n.{i18n}(hintKey);\n'
        "            } else {\n"
        f'                statusLine1 = "{sec}c" + I18n.{i18n}("steambridge.gui.resync_timeout");\n'
        f'                statusLine2 = "{sec}7" + I18n.{i18n}("steambridge.gui.resync_timeout_hint");\n'
        "            }"
    )

    def do_sub(pattern):
        return re.subn(pattern, lambda _m: repl, t, count=1)

    t2, n = do_sub(
        r'statusLine1 = "(?:§|\\u00a7)c" \+ I18n\.(?:get|format|translate)\("steambridge\.gui\.resync_timeout"\);\s*'
        r'statusLine2 = "(?:§|\\u00a7)7" \+ I18n\.(?:get|format|translate)\("steambridge\.gui\.resync_timeout_hint"\);'
    )
    if n == 0:
        # file may already contain real section sign
        t2, n = do_sub(
            r'statusLine1 = "\xc2\xa7c" \+ I18n\.(?:get|format|translate)\("steambridge\.gui\.resync_timeout"\);\s*'
            r'statusLine2 = "\xc2\xa77" \+ I18n\.(?:get|format|translate)\("steambridge\.gui\.resync_timeout_hint"\);'
        )
    print("  resync", n)
    if n:
        path.write_text(t2, encoding="utf-8")


def patch_langs():
    lang_dir = Path("src/main/resources/assets/steambridge/lang")
    if not lang_dir.exists():
        return
    for name in ["en_us.json", "ru_ru.json", "uk_ua.json", "en_US.json", "ru_RU.json", "uk_UA.json"]:
        dest = lang_dir / name
        if not dest.exists():
            continue
        t = dest.read_text(encoding="utf-8")
        if "spacewar_failed" in t:
            continue
        try:
            neo = git_show(f"NeoForge-1.21.1:src/main/resources/assets/steambridge/lang/{name.lower()}")
        except Exception:
            neo = git_show("NeoForge-1.21.1:src/main/resources/assets/steambridge/lang/en_us.json")
            if "ru" in name.lower():
                neo = git_show("NeoForge-1.21.1:src/main/resources/assets/steambridge/lang/ru_ru.json")
            elif "uk" in name.lower():
                neo = git_show("NeoForge-1.21.1:src/main/resources/assets/steambridge/lang/uk_ua.json")
        keys = [
            ln.rstrip().rstrip(",")
            for ln in neo.splitlines()
            if "steambridge.error." in ln
            and any(
                k in ln
                for k in [
                    "native_load",
                    "no_steam_client",
                    "steam_version",
                    "spacewar",
                    "wrong_appid",
                    "family_or_license",
                    "steam_init_failed",
                ]
            )
        ]
        # re-add commas between keys
        block = ",\n".join(keys)
        t2, n = re.subn(
            r'("steambridge\.error\.host_steam_shutdown"\s*:\s*"[^"]*")(,?)',
            r"\1,\n" + block,
            t,
            count=1,
        )
        if n == 0:
            t2, n = re.subn(
                r'("steambridge\.error\.steam_shutdown"\s*:\s*"[^"]*")(,?)',
                r"\1,\n" + block,
                t,
                count=1,
            )
        print("  json", name, n)
        if n:
            dest.write_text(t2, encoding="utf-8")

    for name in list(lang_dir.glob("*.lang")):
        t = name.read_text(encoding="utf-8")
        if "spacewar_failed" in t:
            continue
        # derive from matching json if possible
        base = name.stem.lower()
        json_name = base + ".json"
        try:
            neo = git_show(f"NeoForge-1.21.1:src/main/resources/assets/steambridge/lang/{json_name}")
        except Exception:
            continue
        lines = []
        for ln in neo.splitlines():
            m = re.search(
                r'"(steambridge\.error\.(?:native_load|no_steam_client|no_steam_client_hint|steam_version|spacewar_failed|spacewar_failed_hint|wrong_appid|family_or_license|family_or_license_hint|steam_init_failed))"\s*:\s*"([^"]*)"',
                ln,
            )
            if m:
                lines.append(f"{m.group(1)}={m.group(2)}")
        if lines:
            name.write_text(t.rstrip() + "\n" + "\n".join(lines) + "\n", encoding="utf-8")
            print("  lang", name.name, len(lines))


def finish_current():
    helper = git_show("NeoForge-1.21.1:src/main/java/steambridge/SteamAppIdHelper.java").replace(
        "LOG.debug", "LOG.info"
    )
    Path("src/main/java/steambridge/SteamAppIdHelper.java").write_text(helper, encoding="utf-8")

    mp = Path("src/main/java/steambridge/steam/SteamManager.java")
    t = ensure_helpers(mp)
    t = ensure_imports(t)
    # ensure SPACEWAR path exists; if not, abort for this branch
    if "initEx" not in t:
        print("  WARN: manager missing initEx - full port needed")
    mp.write_text(t, encoding="utf-8")
    print("  manager ok")

    patch_resync(Path("src/main/java/steambridge/gui/GuiSteamResync.java"))
    patch_langs()

    run("git add -A")
    st = run("git status --porcelain")
    if st.stdout.strip():
        run('git commit -m "Diagnose Spacewar/Family View failures when Steam API init fails."')
        print("  committed")
    else:
        print("  nothing to commit")


def full_port_branch():
    """Apply manager patch from neo by copying diagnostic structure using previous py logic minimally."""
    mp = Path("src/main/java/steambridge/steam/SteamManager.java")
    t = mp.read_text(encoding="utf-8")
    if "SPACEWAR_FAILED" not in t:
        # re-use simplified port from neo init replacement
        import importlib.util

        # inline minimal port_manager
        if "InitResult" not in t:
            t = t.replace(
                "import com.codedisaster.steamworks.SteamAPI;",
                "import com.codedisaster.steamworks.SteamAPI;\n"
                "import com.codedisaster.steamworks.SteamAPI.InitResult;\n"
                "import com.codedisaster.steamworks.SteamApps;",
            )
        if "import steambridge.SteamAppIdHelper" not in t:
            t = t.replace(
                "import steambridge.SteamBridgeMod;",
                "import steambridge.SteamAppIdHelper;\nimport steambridge.SteamBridgeMod;",
            )
        enum = """
    public enum InitFailure {
        NONE,
        NATIVE_LOAD,
        NO_STEAM_CLIENT,
        VERSION_MISMATCH,
        SPACEWAR_FAILED,
        WRONG_APP_ID,
        FAMILY_OR_LICENSE,
        UNKNOWN
    }

"""
        t = t.replace(
            "private volatile boolean initialized = false;",
            enum
            + "    private volatile boolean initialized = false;\n"
            + "    private volatile InitFailure lastInitFailure = InitFailure.NONE;",
        )
        t = t.replace(
            """if (initialized) {
            SteamBridgeMod.LOG.warn("[SteamManager] init() called but already initialized.");
            return true;
        }

        SteamBridgeMod.LOG.info("[SteamManager] Loading Steam native libraries...");""",
            """if (initialized) {
            SteamBridgeMod.LOG.warn("[SteamManager] init() called but already initialized.");
            lastInitFailure = InitFailure.NONE;
            return true;
        }

        lastInitFailure = InitFailure.NONE;

        SteamBridgeMod.LOG.info("[SteamManager] Loading Steam native libraries...");""",
        )
        t = t.replace(
            '[SteamManager] Failed to load Steam native libraries.");\n            return false;',
            '[SteamManager] Failed to load Steam native libraries.");\n'
            "            lastInitFailure = InitFailure.NATIVE_LOAD;\n            return false;",
        )
        new_init = """        SteamBridgeMod.LOG.info("[SteamManager] Calling SteamAPI.initEx() (Spacewar appid={})...",
                SteamAppIdHelper.APP_ID);
        try {
            InitResult result = SteamAPI.initEx();
            if (result != InitResult.OK) {
                diagnoseInitFailure(result);
                return false;
            }
        } catch (SteamException e) {
            lastInitFailure = InitFailure.UNKNOWN;
            SteamBridgeMod.LOG.error("[SteamManager] SteamAPI.initEx() threw: {}", e.getMessage());
            return false;
        }"""
        t2, n = re.subn(
            r'SteamBridgeMod\.LOG\.info\("\[SteamManager\] Calling SteamAPI\.init\(\)\.\.\."\);\s*'
            r"try \{\s*if \(!SteamAPI\.init\(\)\) \{.*?\}\s*\} catch \(SteamException e\) \{\s*"
            r'SteamBridgeMod\.LOG\.error\("\[SteamManager\] SteamAPI\.init\(\) threw: \{\}", e\.getMessage\(\)\);\s*'
            r"return false;\s*\}",
            new_init,
            t,
            count=1,
            flags=re.S,
        )
        print("  initEx", n)
        if n:
            t = t2
        if "verifySpacewarContext" not in t:
            t = t.replace(
                "mySteamID = steamUser.getSteamID();\n            socketsApi = SteamSocketsApi.load();",
                "mySteamID = steamUser.getSteamID();\n\n"
                "            if (!verifySpacewarContext()) {\n"
                "                disposeSteamInterfaces();\n"
                "                SteamAPI.shutdown();\n"
                "                return false;\n"
                "            }\n\n"
                "            socketsApi = SteamSocketsApi.load();",
            )
        if "Failed to initialize SteamNetworkingSockets" in t and "disposeSteamInterfaces();" not in t:
            t2, n = re.subn(
                r'(SteamBridgeMod\.LOG\.error\("\[SteamManager\] Failed to initialize SteamNetworkingSockets: \{\}", t\.getMessage\(\), t\);\s*)'
                r"if \(steamUser != null\) \{.*?SteamAPI\.shutdown\(\);\s*return false;",
                r"\1lastInitFailure = InitFailure.UNKNOWN;\n            disposeSteamInterfaces();\n"
                r"            SteamAPI.shutdown();\n            return false;",
                t,
                count=1,
                flags=re.S,
            )
            if n:
                t = t2
        t = t.replace(
            "initialized = true;\n        running.set(true);",
            "initialized = true;\n        lastInitFailure = InitFailure.NONE;\n        running.set(true);",
        )
        mp.write_text(t, encoding="utf-8")
    t = ensure_helpers(mp)
    t = ensure_imports(t)
    mp.write_text(t, encoding="utf-8")


def main():
    remaining = [
        "Fabric-1.16.5",
        "Forge-1.16.5",
        "Forge-1.12.2",
        "Forge-1.8.9",
        "Forge-1.7.10",
    ]
    for b in remaining:
        print(f"\n==== {b} ====")
        r = run(f"git checkout {b}")
        if r.returncode != 0:
            print(r.stderr)
            continue
        # discard dirty partial if needed? Fabric-1.16.5 has good partial - keep
        full_port_branch()
        finish_current()
    run("git checkout NeoForge-1.21.1")
    print("DONE")


if __name__ == "__main__":
    main()
