# -*- coding: utf-8 -*-
"""Port Spacewar/Family View init diagnostics to all branches."""
import os
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent
os.chdir(ROOT)

BRANCHES = [
    "NeoForge-1.20.1",
    "Fabric-1.21.1",
    "Fabric-1.20.1",
    "Fabric-1.19.2",
    "Forge-1.19.2",
    "Fabric-1.16.5",
    "Forge-1.16.5",
    "Forge-1.12.2",
    "Forge-1.8.9",
    "Forge-1.7.10",
]

EN_JSON = '''  "steambridge.error.native_load": "Could not load Steam native libraries.",
  "steambridge.error.no_steam_client": "Steam client is not running.",
  "steambridge.error.no_steam_client_hint": "Start Steam, then try again.",
  "steambridge.error.steam_version": "Steam client version is incompatible with this mod.",
  "steambridge.error.spacewar_failed": "Steam is running, but Spacewar (AppID 480) could not start.",
  "steambridge.error.spacewar_failed_hint": "Check Family View, shared-library locks, or close other Steam games and retry.",
  "steambridge.error.wrong_appid": "Steam attached with the wrong AppID (expected Spacewar 480).",
  "steambridge.error.family_or_license": "This Steam account cannot use Spacewar (license / Family View / shared library).",
  "steambridge.error.family_or_license_hint": "Disable Family View restrictions for Spacewar or use an unrestricted account.",
  "steambridge.error.steam_init_failed": "Steam failed to initialize.",'''

RU_JSON = '''  "steambridge.error.native_load": "Не удалось загрузить нативные библиотеки Steam.",
  "steambridge.error.no_steam_client": "Клиент Steam не запущен.",
  "steambridge.error.no_steam_client_hint": "Запустите Steam и попробуйте снова.",
  "steambridge.error.steam_version": "Версия клиента Steam несовместима с этим модом.",
  "steambridge.error.spacewar_failed": "Steam запущен, но Spacewar (AppID 480) не удалось стартовать.",
  "steambridge.error.spacewar_failed_hint": "Проверьте Family View, ограничения семейной библиотеки или закройте другие игры Steam.",
  "steambridge.error.wrong_appid": "Steam подключился с неверным AppID (нужен Spacewar 480).",
  "steambridge.error.family_or_license": "Этот аккаунт Steam не может использовать Spacewar (лицензия / Family View / семейная библиотека).",
  "steambridge.error.family_or_license_hint": "Снимите ограничения Family View для Spacewar или используйте другой аккаунт.",
  "steambridge.error.steam_init_failed": "Не удалось инициализировать Steam.",'''

UK_JSON = '''  "steambridge.error.native_load": "Не вдалося завантажити нативні бібліотеки Steam.",
  "steambridge.error.no_steam_client": "Клієнт Steam не запущено.",
  "steambridge.error.no_steam_client_hint": "Запустіть Steam і спробуйте знову.",
  "steambridge.error.steam_version": "Версія клієнта Steam несумісна з цим модом.",
  "steambridge.error.spacewar_failed": "Steam запущено, але Spacewar (AppID 480) не вдалося стартувати.",
  "steambridge.error.spacewar_failed_hint": "Перевірте Family View, обмеження сімейної бібліотеки або закрийте інші ігри Steam.",
  "steambridge.error.wrong_appid": "Steam підключився з неправильним AppID (потрібен Spacewar 480).",
  "steambridge.error.family_or_license": "Цей акаунт Steam не може використовувати Spacewar (ліцензія / Family View / сімейна бібліотека).",
  "steambridge.error.family_or_license_hint": "Зніміть обмеження Family View для Spacewar або використайте інший акаунт.",
  "steambridge.error.steam_init_failed": "Не вдалося ініціалізувати Steam.",'''

EN_LANG = """steambridge.error.native_load=Could not load Steam native libraries.
steambridge.error.no_steam_client=Steam client is not running.
steambridge.error.no_steam_client_hint=Start Steam, then try again.
steambridge.error.steam_version=Steam client version is incompatible with this mod.
steambridge.error.spacewar_failed=Steam is running, but Spacewar (AppID 480) could not start.
steambridge.error.spacewar_failed_hint=Check Family View, shared-library locks, or close other Steam games and retry.
steambridge.error.wrong_appid=Steam attached with the wrong AppID (expected Spacewar 480).
steambridge.error.family_or_license=This Steam account cannot use Spacewar (license / Family View / shared library).
steambridge.error.family_or_license_hint=Disable Family View restrictions for Spacewar or use an unrestricted account.
steambridge.error.steam_init_failed=Steam failed to initialize.
"""

RU_LANG = """steambridge.error.native_load=Не удалось загрузить нативные библиотеки Steam.
steambridge.error.no_steam_client=Клиент Steam не запущен.
steambridge.error.no_steam_client_hint=Запустите Steam и попробуйте снова.
steambridge.error.steam_version=Версия клиента Steam несовместима с этим модом.
steambridge.error.spacewar_failed=Steam запущен, но Spacewar (AppID 480) не удалось стартовать.
steambridge.error.spacewar_failed_hint=Проверьте Family View, ограничения семейной библиотеки или закройте другие игры Steam.
steambridge.error.wrong_appid=Steam подключился с неверным AppID (нужен Spacewar 480).
steambridge.error.family_or_license=Этот аккаунт Steam не может использовать Spacewar (лицензия / Family View / семейная библиотека).
steambridge.error.family_or_license_hint=Снимите ограничения Family View для Spacewar или используйте другой аккаунт.
steambridge.error.steam_init_failed=Не удалось инициализировать Steam.
"""

UK_LANG = """steambridge.error.native_load=Не вдалося завантажити нативні бібліотеки Steam.
steambridge.error.no_steam_client=Клієнт Steam не запущено.
steambridge.error.no_steam_client_hint=Запустіть Steam і спробуйте знову.
steambridge.error.steam_version=Версія клієнта Steam несумісна з цим модом.
steambridge.error.spacewar_failed=Steam запущено, але Spacewar (AppID 480) не вдалося стартувати.
steambridge.error.spacewar_failed_hint=Перевірте Family View, обмеження сімейної бібліотеки або закрийте інші ігри Steam.
steambridge.error.wrong_appid=Steam підключився з неправильним AppID (потрібен Spacewar 480).
steambridge.error.family_or_license=Цей акаунт Steam не може використовувати Spacewar (ліцензія / Family View / сімейна бібліотека).
steambridge.error.family_or_license_hint=Зніміть обмеження Family View для Spacewar або використайте інший акаунт.
steambridge.error.steam_init_failed=Не вдалося ініціалізувати Steam.
"""

HELPERS = r'''
    private void disposeSteamInterfaces() {
        if (steamUser != null) {
            steamUser.dispose();
            steamUser = null;
        }
        if (steamFriends != null) {
            steamFriends.dispose();
            steamFriends = null;
        }
        if (steamUtils != null) {
            steamUtils.dispose();
            steamUtils = null;
        }
    }

    private void diagnoseInitFailure(InitResult result) {
        boolean steamProc = SteamAppIdHelper.isSteamClientProcessRunning();
        if (result == InitResult.NoSteamClient) {
            lastInitFailure = InitFailure.NO_STEAM_CLIENT;
        } else if (result == InitResult.VersionMismatch) {
            lastInitFailure = InitFailure.VERSION_MISMATCH;
        } else if (steamProc) {
            lastInitFailure = InitFailure.SPACEWAR_FAILED;
        } else if (result == InitResult.FailedGeneric) {
            lastInitFailure = InitFailure.NO_STEAM_CLIENT;
        } else {
            lastInitFailure = InitFailure.UNKNOWN;
        }
        SteamBridgeMod.LOG.error(
                "[SteamManager] SteamAPI.initEx()={} steamProcess={} failure={} (appid={})",
                result, steamProc, lastInitFailure, SteamAppIdHelper.APP_ID
        );
    }

    private boolean verifySpacewarContext() {
        int appId = steamUtils != null ? steamUtils.getAppID() : 0;
        if (appId != 0 && appId != SteamAppIdHelper.APP_ID_INT) {
            lastInitFailure = InitFailure.WRONG_APP_ID;
            SteamBridgeMod.LOG.error(
                    "[SteamManager] Wrong Steam AppID after init: got {} expected {} (Spacewar).",
                    appId, SteamAppIdHelper.APP_ID_INT
            );
            return false;
        }

        SteamApps apps = null;
        try {
            apps = new SteamApps();
            boolean subscribed = apps.isSubscribed();
            SteamID owner = apps.getAppOwner();
            long me = mySteamID != null ? SteamNativeHandle.getNativeHandle(mySteamID) : 0L;
            long ownerHandle = owner != null ? SteamNativeHandle.getNativeHandle(owner) : 0L;
            boolean familyShared = ownerHandle != 0L && me != 0L && ownerHandle != me;

            SteamBridgeMod.LOG.info(
                    "[SteamManager] Spacewar context: appId={} subscribed={} familyShared={} owner={}",
                    appId, subscribed, familyShared, ownerHandle
            );

            if (!subscribed) {
                lastInitFailure = InitFailure.FAMILY_OR_LICENSE;
                SteamBridgeMod.LOG.error(
                        "[SteamManager] isSubscribed()=false for Spacewar. Family View / shared library may block multiplayer."
                );
                return false;
            }
            if (familyShared) {
                SteamBridgeMod.LOG.info(
                        "[SteamManager] App owned by another account (Family Library). Owner SteamID={}",
                        ownerHandle
                );
            }
        } catch (Throwable t) {
            SteamBridgeMod.LOG.warn("[SteamManager] Spacewar license probe failed: {}", t.getMessage());
        } finally {
            if (apps != null) {
                try { apps.dispose(); } catch (Throwable ignored) {}
            }
        }
        return true;
    }

    public InitFailure getLastInitFailure() {
        return lastInitFailure;
    }

    public String getLastInitFailureKey() {
        switch (lastInitFailure) {
            case NATIVE_LOAD:       return "steambridge.error.native_load";
            case NO_STEAM_CLIENT:   return "steambridge.error.no_steam_client";
            case VERSION_MISMATCH:  return "steambridge.error.steam_version";
            case SPACEWAR_FAILED:   return "steambridge.error.spacewar_failed";
            case WRONG_APP_ID:      return "steambridge.error.wrong_appid";
            case FAMILY_OR_LICENSE: return "steambridge.error.family_or_license";
            case UNKNOWN:           return "steambridge.error.steam_init_failed";
            case NONE:
            default:                return "steambridge.error.steam_init_failed";
        }
    }

    public String getLastInitFailureHintKey() {
        switch (lastInitFailure) {
            case SPACEWAR_FAILED:   return "steambridge.error.spacewar_failed_hint";
            case FAMILY_OR_LICENSE: return "steambridge.error.family_or_license_hint";
            case NO_STEAM_CLIENT:   return "steambridge.error.no_steam_client_hint";
            default:                return "";
        }
    }

'''

ENUM = '''
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

'''


def run(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, encoding="utf-8", errors="replace")


def git_show(ref_path):
    r = run(f'git show "{ref_path}"')
    if r.returncode != 0:
        raise RuntimeError(r.stderr)
    return r.stdout


def patch_manager(text: str) -> str:
    if "SPACEWAR_FAILED" in text:
        return text

    if "InitResult" not in text:
        text = text.replace(
            "import com.codedisaster.steamworks.SteamAPI;",
            "import com.codedisaster.steamworks.SteamAPI;\n"
            "import com.codedisaster.steamworks.SteamAPI.InitResult;\n"
            "import com.codedisaster.steamworks.SteamApps;",
        )
    if "import steambridge.SteamAppIdHelper" not in text:
        text = text.replace(
            "import steambridge.SteamBridgeMod;",
            "import steambridge.SteamAppIdHelper;\nimport steambridge.SteamBridgeMod;",
        )

    text = text.replace(
        "private volatile boolean initialized = false;",
        ENUM + "    private volatile boolean initialized = false;\n"
        "    private volatile InitFailure lastInitFailure = InitFailure.NONE;",
    )

    text = text.replace(
        '''if (initialized) {
            SteamBridgeMod.LOG.warn("[SteamManager] init() called but already initialized.");
            return true;
        }

        SteamBridgeMod.LOG.info("[SteamManager] Loading Steam native libraries...");''',
        '''if (initialized) {
            SteamBridgeMod.LOG.warn("[SteamManager] init() called but already initialized.");
            lastInitFailure = InitFailure.NONE;
            return true;
        }

        lastInitFailure = InitFailure.NONE;

        SteamBridgeMod.LOG.info("[SteamManager] Loading Steam native libraries...");''',
    )

    text = text.replace(
        '[SteamManager] Failed to load Steam native libraries.");\n            return false;',
        '[SteamManager] Failed to load Steam native libraries.");\n'
        "            lastInitFailure = InitFailure.NATIVE_LOAD;\n            return false;",
    )

    new_init = '''        SteamBridgeMod.LOG.info("[SteamManager] Calling SteamAPI.initEx() (Spacewar appid={})...",
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
        }'''

    text2, n = re.subn(
        r'SteamBridgeMod\.LOG\.info\("\[SteamManager\] Calling SteamAPI\.init\(\)\.\.\."\);\s*'
        r'try \{\s*if \(!SteamAPI\.init\(\)\) \{.*?\}\s*\} catch \(SteamException e\) \{\s*'
        r'SteamBridgeMod\.LOG\.error\("\[SteamManager\] SteamAPI\.init\(\) threw: \{\}", e\.getMessage\(\)\);\s*'
        r'return false;\s*\}',
        new_init,
        text,
        count=1,
        flags=re.S,
    )
    if n == 0:
        print("  FAIL initEx")
    else:
        text = text2

    if "verifySpacewarContext" not in text:
        text = text.replace(
            "mySteamID = steamUser.getSteamID();\n            socketsApi = SteamSocketsApi.load();",
            "mySteamID = steamUser.getSteamID();\n\n"
            "            if (!verifySpacewarContext()) {\n"
            "                disposeSteamInterfaces();\n"
            "                SteamAPI.shutdown();\n"
            "                return false;\n"
            "            }\n\n"
            "            socketsApi = SteamSocketsApi.load();",
        )

    if "Failed to initialize SteamNetworkingSockets" in text and "disposeSteamInterfaces();" not in text:
        text2, n = re.subn(
            r'(SteamBridgeMod\.LOG\.error\("\[SteamManager\] Failed to initialize SteamNetworkingSockets: \{\}", t\.getMessage\(\), t\);\s*)'
            r'if \(steamUser != null\) \{.*?SteamAPI\.shutdown\(\);\s*return false;',
            r'\1lastInitFailure = InitFailure.UNKNOWN;\n            disposeSteamInterfaces();\n'
            r'            SteamAPI.shutdown();\n            return false;',
            text,
            count=1,
            flags=re.S,
        )
        if n:
            text = text2

    text = text.replace(
        "initialized = true;\n        running.set(true);",
        "initialized = true;\n        lastInitFailure = InitFailure.NONE;\n        running.set(true);",
    )

    if "diagnoseInitFailure" not in text and "public boolean reinit()" in text:
        text = text.replace("public boolean reinit()", HELPERS + "    public boolean reinit()")

    return text


def patch_resync(text: str) -> str:
    if "getLastInitFailure" in text:
        return text
    if "I18n.format(" in text:
        i18n = "format"
    elif "I18n.translate(" in text:
        i18n = "translate"
    else:
        i18n = "get"
    sec = r"\u00a7" if r"\u00a7" in text else "§"
    repl = f'''SteamManager.InitFailure fail = SteamManager.getInstance().getLastInitFailure();
            if (fail != null && fail != SteamManager.InitFailure.NONE) {{
                statusLine1 = "{sec}c" + I18n.{i18n}(SteamManager.getInstance().getLastInitFailureKey());
                String hintKey = SteamManager.getInstance().getLastInitFailureHintKey();
                statusLine2 = hintKey.isEmpty()
                        ? "{sec}7" + I18n.{i18n}("steambridge.gui.resync_timeout_hint")
                        : "{sec}7" + I18n.{i18n}(hintKey);
            }} else {{
                statusLine1 = "{sec}c" + I18n.{i18n}("steambridge.gui.resync_timeout");
                statusLine2 = "{sec}7" + I18n.{i18n}("steambridge.gui.resync_timeout_hint");
            }}'''
    text2, n = re.subn(
        r'statusLine1 = "(?:§|\\u00a7)c" \+ I18n\.(?:get|format|translate)\("steambridge\.gui\.resync_timeout"\);\s*'
        r'statusLine2 = "(?:§|\\u00a7)7" \+ I18n\.(?:get|format|translate)\("steambridge\.gui\.resync_timeout_hint"\);',
        repl,
        text,
        count=1,
    )
    if n == 0:
        print("  resync miss")
    return text2 if n else text


def patch_lang_json(path: Path):
    t = path.read_text(encoding="utf-8")
    if "spacewar_failed" in t:
        return
    name = path.name.lower()
    block = RU_JSON if "ru" in name else UK_JSON if "uk" in name else EN_JSON
    if "host_steam_shutdown" in t:
        t2, n = re.subn(
            r'("steambridge\.error\.host_steam_shutdown"\s*:\s*"[^"]*")(,?)',
            r"\1,\n" + block,
            t,
            count=1,
        )
    else:
        t2, n = re.subn(
            r'("steambridge\.error\.steam_shutdown"\s*:\s*"[^"]*")(,?)',
            r"\1,\n" + block,
            t,
            count=1,
        )
    if n:
        path.write_text(t2, encoding="utf-8")
        print(f"  json {path.name}")


def patch_lang_file(path: Path):
    t = path.read_text(encoding="utf-8")
    if "spacewar_failed" in t:
        return
    name = path.name.lower()
    block = RU_LANG if "ru" in name else UK_LANG if "uk" in name else EN_LANG
    if "host_steam_shutdown=" in t:
        t2 = re.sub(
            r"(steambridge\.error\.host_steam_shutdown=[^\r\n]+)",
            r"\1\n" + block.rstrip(),
            t,
            count=1,
        )
    else:
        t2 = re.sub(
            r"(steambridge\.error\.steam_shutdown=[^\r\n]+)",
            r"\1\n" + block.rstrip(),
            t,
            count=1,
        )
    if t2 != t:
        path.write_text(t2, encoding="utf-8")
        print(f"  lang {path.name}")


def main():
    helper = git_show("NeoForge-1.21.1:src/main/java/steambridge/SteamAppIdHelper.java")
    helper = helper.replace("LOG.debug", "LOG.info")

    for b in BRANCHES:
        print(f"\n==== {b} ====")
        r = run(f"git checkout {b}")
        if r.returncode != 0:
            print("checkout fail", r.stderr)
            continue

        Path("src/main/java/steambridge/SteamAppIdHelper.java").write_text(helper, encoding="utf-8")

        mp = Path("src/main/java/steambridge/steam/SteamManager.java")
        mp.write_text(patch_manager(mp.read_text(encoding="utf-8")), encoding="utf-8")
        print("  manager")

        rp = Path("src/main/java/steambridge/gui/GuiSteamResync.java")
        if rp.exists():
            rp.write_text(patch_resync(rp.read_text(encoding="utf-8")), encoding="utf-8")
            print("  resync")

        for p in Path("src/main/resources").rglob("*"):
            if not p.is_file():
                continue
            if p.suffix == ".json" and "lang" in str(p).replace("\\", "/"):
                patch_lang_json(p)
            elif p.suffix == ".lang":
                patch_lang_file(p)

        run("git add -A")
        st = run("git status --porcelain")
        if st.stdout.strip():
            run('git commit -m "Diagnose Spacewar/Family View failures when Steam API init fails."')
            print("  committed")
        else:
            print("  nothing")

    run("git checkout NeoForge-1.21.1")
    print("PORT DONE")


if __name__ == "__main__":
    main()
