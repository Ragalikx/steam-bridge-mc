$ErrorActionPreference = 'Continue'
$utf8 = New-Object System.Text.UTF8Encoding $false

function Add-JsonKeys([string]$path) {
  if (-not (Test-Path $path)) { return }
  $t = [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8)
  if ($t -match 'spacewar_failed') { return }
  $name = [IO.Path]::GetFileName($path).ToLowerInvariant()
  if ($name -match 'ru') {
    $block = @'
  "steambridge.error.native_load": "Не удалось загрузить нативные библиотеки Steam.",
  "steambridge.error.no_steam_client": "Клиент Steam не запущен.",
  "steambridge.error.no_steam_client_hint": "Запустите Steam и попробуйте снова.",
  "steambridge.error.steam_version": "Версия клиента Steam несовместима с этим модом.",
  "steambridge.error.spacewar_failed": "Steam запущен, но Spacewar (AppID 480) не удалось стартовать.",
  "steambridge.error.spacewar_failed_hint": "Проверьте Family View, ограничения семейной библиотеки или закройте другие игры Steam.",
  "steambridge.error.wrong_appid": "Steam подключился с неверным AppID (нужен Spacewar 480).",
  "steambridge.error.family_or_license": "Этот аккаунт Steam не может использовать Spacewar (лицензия / Family View / семейная библиотека).",
  "steambridge.error.family_or_license_hint": "Снимите ограничения Family View для Spacewar или используйте другой аккаунт.",
  "steambridge.error.steam_init_failed": "Не удалось инициализировать Steam.",
'@
  } elseif ($name -match 'uk') {
    $block = @'
  "steambridge.error.native_load": "Не вдалося завантажити нативні бібліотеки Steam.",
  "steambridge.error.no_steam_client": "Клієнт Steam не запущено.",
  "steambridge.error.no_steam_client_hint": "Запустіть Steam і спробуйте знову.",
  "steambridge.error.steam_version": "Версія клієнта Steam несумісна з цим модом.",
  "steambridge.error.spacewar_failed": "Steam запущено, але Spacewar (AppID 480) не вдалося стартувати.",
  "steambridge.error.spacewar_failed_hint": "Перевірте Family View, обмеження сімейної бібліотеки або закрийте інші ігри Steam.",
  "steambridge.error.wrong_appid": "Steam підключився з неправильним AppID (потрібен Spacewar 480).",
  "steambridge.error.family_or_license": "Цей акаунт Steam не може використовувати Spacewar (ліцензія / Family View / сімейна бібліотека).",
  "steambridge.error.family_or_license_hint": "Зніміть обмеження Family View для Spacewar або використайте інший акаунт.",
  "steambridge.error.steam_init_failed": "Не вдалося ініціалізувати Steam.",
'@
  } else {
    $block = @'
  "steambridge.error.native_load": "Could not load Steam native libraries.",
  "steambridge.error.no_steam_client": "Steam client is not running.",
  "steambridge.error.no_steam_client_hint": "Start Steam, then try again.",
  "steambridge.error.steam_version": "Steam client version is incompatible with this mod.",
  "steambridge.error.spacewar_failed": "Steam is running, but Spacewar (AppID 480) could not start.",
  "steambridge.error.spacewar_failed_hint": "Check Family View, shared-library locks, or close other Steam games and retry.",
  "steambridge.error.wrong_appid": "Steam attached with the wrong AppID (expected Spacewar 480).",
  "steambridge.error.family_or_license": "This Steam account cannot use Spacewar (license / Family View / shared library).",
  "steambridge.error.family_or_license_hint": "Disable Family View restrictions for Spacewar or use an unrestricted account.",
  "steambridge.error.steam_init_failed": "Steam failed to initialize.",
'@
  }
  if ($t -match 'host_steam_shutdown') {
    $t2 = [regex]::Replace($t, '("steambridge\.error\.host_steam_shutdown"\s*:\s*"[^"]*")(,?)', "`$1,`r`n$block", 1)
  } else {
    $t2 = [regex]::Replace($t, '("steambridge\.error\.steam_shutdown"\s*:\s*"[^"]*")(,?)', "`$1,`r`n$block", 1)
  }
  if ($t2 -eq $t) { Write-Host "  json fail $path"; return }
  [IO.File]::WriteAllText($path, $t2, $utf8)
  Write-Host "  json ok"
}

function Add-LangKeys([string]$path) {
  if (-not (Test-Path $path)) { return }
  $t = [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8)
  if ($t -match 'spacewar_failed') { return }
  $n = [IO.Path]::GetFileName($path).ToLowerInvariant()
  if ($n -match 'ru') {
    $lines = "steambridge.error.native_load=Не удалось загрузить нативные библиотеки Steam.`r`nsteambridge.error.no_steam_client=Клиент Steam не запущен.`r`nsteambridge.error.no_steam_client_hint=Запустите Steam и попробуйте снова.`r`nsteambridge.error.steam_version=Версия клиента Steam несовместима с этим модом.`r`nsteambridge.error.spacewar_failed=Steam запущен, но Spacewar (AppID 480) не удалось стартовать.`r`nsteambridge.error.spacewar_failed_hint=Проверьте Family View, ограничения семейной библиотеки или закройте другие игры Steam.`r`nsteambridge.error.wrong_appid=Steam подключился с неверным AppID (нужен Spacewar 480).`r`nsteambridge.error.family_or_license=Этот аккаунт Steam не может использовать Spacewar (лицензия / Family View / семейная библиотека).`r`nsteambridge.error.family_or_license_hint=Снимите ограничения Family View для Spacewar или используйте другой аккаунт.`r`nsteambridge.error.steam_init_failed=Не удалось инициализировать Steam."
  } elseif ($n -match 'uk') {
    $lines = "steambridge.error.native_load=Не вдалося завантажити нативні бібліотеки Steam.`r`nsteambridge.error.no_steam_client=Клієнт Steam не запущено.`r`nsteambridge.error.no_steam_client_hint=Запустіть Steam і спробуйте знову.`r`nsteambridge.error.steam_version=Версія клієнта Steam несумісна з цим модом.`r`nsteambridge.error.spacewar_failed=Steam запущено, але Spacewar (AppID 480) не вдалося стартувати.`r`nsteambridge.error.spacewar_failed_hint=Перевірте Family View, обмеження сімейної бібліотеки або закрийте інші ігри Steam.`r`nsteambridge.error.wrong_appid=Steam підключився з неправильним AppID (потрібен Spacewar 480).`r`nsteambridge.error.family_or_license=Цей акаунт Steam не може використовувати Spacewar (ліцензія / Family View / сімейна бібліотека).`r`nsteambridge.error.family_or_license_hint=Зніміть обмеження Family View для Spacewar або використайте інший акаунт.`r`nsteambridge.error.steam_init_failed=Не вдалося ініціалізувати Steam."
  } else {
    $lines = "steambridge.error.native_load=Could not load Steam native libraries.`r`nsteambridge.error.no_steam_client=Steam client is not running.`r`nsteambridge.error.no_steam_client_hint=Start Steam, then try again.`r`nsteambridge.error.steam_version=Steam client version is incompatible with this mod.`r`nsteambridge.error.spacewar_failed=Steam is running, but Spacewar (AppID 480) could not start.`r`nsteambridge.error.spacewar_failed_hint=Check Family View, shared-library locks, or close other Steam games and retry.`r`nsteambridge.error.wrong_appid=Steam attached with the wrong AppID (expected Spacewar 480).`r`nsteambridge.error.family_or_license=This Steam account cannot use Spacewar (license / Family View / shared library).`r`nsteambridge.error.family_or_license_hint=Disable Family View restrictions for Spacewar or use an unrestricted account.`r`nsteambridge.error.steam_init_failed=Steam failed to initialize."
  }
  if ($t -match 'host_steam_shutdown=') {
    $t2 = $t -replace '(steambridge\.error\.host_steam_shutdown=[^\r\n]+)', "`$1`r`n$lines"
  } else {
    $t2 = $t -replace '(steambridge\.error\.steam_shutdown=[^\r\n]+)', "`$1`r`n$lines"
  }
  [IO.File]::WriteAllText($path, $t2, $utf8)
  Write-Host "  lang ok"
}

function Patch-Manager {
  $p = 'src/main/java/steambridge/steam/SteamManager.java'
  $c = [IO.File]::ReadAllText($p)
  if ($c -match 'SPACEWAR_FAILED') { Write-Host '  manager already'; return }

  if ($c -notmatch 'InitResult') {
    $c = $c.Replace('import com.codedisaster.steamworks.SteamAPI;', "import com.codedisaster.steamworks.SteamAPI;`r`nimport com.codedisaster.steamworks.SteamAPI.InitResult;`r`nimport com.codedisaster.steamworks.SteamApps;")
  }
  if ($c -notmatch 'import steambridge\.SteamAppIdHelper') {
    $c = $c.Replace('import steambridge.SteamBridgeMod;', "import steambridge.SteamAppIdHelper;`r`nimport steambridge.SteamBridgeMod;")
  }

  $enum = @"

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

"@
  $c = $c.Replace(
    'private volatile boolean initialized = false;',
    $enum + "    private volatile boolean initialized = false;`r`n    private volatile InitFailure lastInitFailure = InitFailure.NONE;")

  $c = $c.Replace(
    'if (initialized) {
            SteamBridgeMod.LOG.warn("[SteamManager] init() called but already initialized.");
            return true;
        }

        SteamBridgeMod.LOG.info("[SteamManager] Loading Steam native libraries...");',
    'if (initialized) {
            SteamBridgeMod.LOG.warn("[SteamManager] init() called but already initialized.");
            lastInitFailure = InitFailure.NONE;
            return true;
        }

        lastInitFailure = InitFailure.NONE;

        SteamBridgeMod.LOG.info("[SteamManager] Loading Steam native libraries...");')

  $c = $c.Replace(
    '[SteamManager] Failed to load Steam native libraries.");
            return false;',
    '[SteamManager] Failed to load Steam native libraries.");
            lastInitFailure = InitFailure.NATIVE_LOAD;
            return false;')

  $newInit = @'
        SteamBridgeMod.LOG.info("[SteamManager] Calling SteamAPI.initEx() (Spacewar appid={})...",
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
        }
'@
  $c2 = [regex]::Replace($c, '(?s)SteamBridgeMod\.LOG\.info\("\[SteamManager\] Calling SteamAPI\.init\(\)\.\.\."\);\s*try \{\s*if \(!SteamAPI\.init\(\)\) \{.*?\}\s*\} catch \(SteamException e\) \{\s*SteamBridgeMod\.LOG\.error\("\[SteamManager\] SteamAPI\.init\(\) threw: \{\}", e\.getMessage\(\)\);\s*return false;\s*\}', $newInit)
  if ($c2 -eq $c) { Write-Host '  FAIL initEx replace' } else { $c = $c2 }

  if ($c -notmatch 'verifySpacewarContext') {
    $c = $c.Replace(
      "mySteamID = steamUser.getSteamID();`r`n            socketsApi = SteamSocketsApi.load();",
      "mySteamID = steamUser.getSteamID();`r`n`r`n            if (!verifySpacewarContext()) {`r`n                disposeSteamInterfaces();`r`n                SteamAPI.shutdown();`r`n                return false;`r`n            }`r`n`r`n            socketsApi = SteamSocketsApi.load();")
    $c = $c.Replace(
      "mySteamID = steamUser.getSteamID();`n            socketsApi = SteamSocketsApi.load();",
      "mySteamID = steamUser.getSteamID();`n`n            if (!verifySpacewarContext()) {`n                disposeSteamInterfaces();`n                SteamAPI.shutdown();`n                return false;`n            }`n`n            socketsApi = SteamSocketsApi.load();")
  }

  if ($c -match 'Failed to initialize SteamNetworkingSockets' -and $c -notmatch 'disposeSteamInterfaces\(\);') {
    $c = [regex]::Replace($c, '(?s)(SteamBridgeMod\.LOG\.error\("\[SteamManager\] Failed to initialize SteamNetworkingSockets: \{\}", t\.getMessage\(\), t\);\s*)if \(steamUser != null\) \{.*?SteamAPI\.shutdown\(\);\s*return false;', {
      param($m)
      $m.Groups[1].Value + "lastInitFailure = InitFailure.UNKNOWN;`r`n            disposeSteamInterfaces();`r`n            SteamAPI.shutdown();`r`n            return false;"
    })
  }

  $c = $c.Replace(
    "initialized = true;`r`n        running.set(true);",
    "initialized = true;`r`n        lastInitFailure = InitFailure.NONE;`r`n        running.set(true);")
  $c = $c.Replace(
    "initialized = true;`n        running.set(true);",
    "initialized = true;`n        lastInitFailure = InitFailure.NONE;`n        running.set(true);")

  $helpers = @'

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

'@
  if ($c -match 'public boolean reinit\(\)' -and $c -notmatch 'diagnoseInitFailure') {
    $c = $c.Replace('public boolean reinit()', $helpers + '    public boolean reinit()')
  }

  [IO.File]::WriteAllText((Resolve-Path $p), $c, $utf8)
  Write-Host '  manager patched'
}

function Patch-Resync {
  $p = 'src/main/java/steambridge/gui/GuiSteamResync.java'
  if (-not (Test-Path $p)) { return }
  $c = [IO.File]::ReadAllText($p)
  if ($c -match 'getLastInitFailure') { Write-Host '  resync already'; return }

  $i18n = 'get'
  if ($c -match 'I18n\.format\(') { $i18n = 'format' }
  elseif ($c -match 'I18n\.translate\(') { $i18n = 'translate' }
  $sec = if ($c -match '\\u00a7') { '\u00a7' } else { '§' }

  $repl = @"
SteamManager.InitFailure fail = SteamManager.getInstance().getLastInitFailure();
            if (fail != null && fail != SteamManager.InitFailure.NONE) {
                statusLine1 = "${sec}c" + I18n.$i18n(SteamManager.getInstance().getLastInitFailureKey());
                String hintKey = SteamManager.getInstance().getLastInitFailureHintKey();
                statusLine2 = hintKey.isEmpty()
                        ? "${sec}7" + I18n.$i18n("steambridge.gui.resync_timeout_hint")
                        : "${sec}7" + I18n.$i18n(hintKey);
            } else {
                statusLine1 = "${sec}c" + I18n.$i18n("steambridge.gui.resync_timeout");
                statusLine2 = "${sec}7" + I18n.$i18n("steambridge.gui.resync_timeout_hint");
            }
"@

  $c2 = [regex]::Replace($c, 'statusLine1 = "(?:§|\\u00a7)c" \+ I18n\.(get|format|translate)\("steambridge\.gui\.resync_timeout"\);\s*statusLine2 = "(?:§|\\u00a7)7" \+ I18n\.(get|format|translate)\("steambridge\.gui\.resync_timeout_hint"\);', $repl)
  if ($c2 -eq $c) { Write-Host '  resync miss' } else {
    [IO.File]::WriteAllText((Resolve-Path $p), $c2, $utf8)
    Write-Host '  resync patched'
  }
}

$branches = @(
  'NeoForge-1.20.1','Fabric-1.21.1','Fabric-1.20.1','Fabric-1.19.2','Forge-1.19.2',
  'Fabric-1.16.5','Forge-1.16.5','Forge-1.12.2','Forge-1.8.9','Forge-1.7.10'
)

foreach ($b in $branches) {
  Write-Host "`n==== $b ===="
  git checkout $b 2>&1 | Out-Null
  git show 'NeoForge-1.21.1:src/main/java/steambridge/SteamAppIdHelper.java' | Out-File -FilePath 'src/main/java/steambridge/SteamAppIdHelper.java' -Encoding utf8
  $h = [IO.File]::ReadAllText('src/main/java/steambridge/SteamAppIdHelper.java')
  $h = $h -replace 'LOG\.debug', 'LOG.info'
  # strip UTF8 BOM that Out-File may add
  if ($h.Length -gt 0 -and [int][char]$h[0] -eq 0xFEFF) { $h = $h.Substring(1) }
  [IO.File]::WriteAllText((Resolve-Path 'src/main/java/steambridge/SteamAppIdHelper.java'), $h, $utf8)

  Patch-Manager
  Patch-Resync

  Get-ChildItem -Recurse src/main/resources -ErrorAction SilentlyContinue | Where-Object {
    $_.Name -match '\.(json|lang)$'
  } | ForEach-Object {
    if ($_.Extension -eq '.json') { Add-JsonKeys $_.FullName } else { Add-LangKeys $_.FullName }
  }

  git add -A
  if (git status --porcelain) {
    git commit -m "Diagnose Spacewar/Family View failures when Steam API init fails." 2>&1 | Out-Null
    Write-Host '  committed'
  } else {
    Write-Host '  nothing'
  }
}

git checkout NeoForge-1.21.1 2>&1 | Out-Null
Write-Host 'PORT DONE'
