# -*- coding: utf-8 -*-
"""Fix SteamManager on all branches: correct Spacewar helper methods, no duplicate shutdown."""
import re
import subprocess
from pathlib import Path

# subprocess used throughout

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


def run(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, encoding="utf-8", errors="replace")


def neo_helpers():
    t = subprocess.check_output(
        ["git", "show", "NeoForge-1.21.1:src/main/java/steambridge/steam/SteamManager.java"],
        text=True,
        encoding="utf-8",
    )
    # Only diagnostic helpers — NOT shutdown/reinit
    start = t.find("    private void disposeSteamInterfaces() {")
    end = t.find("    public void shutdown() {")
    if start < 0 or end < 0 or end <= start:
        # fallback: cut at reinit if shutdown not after helpers
        end = t.find("    public boolean reinit() {")
    block = t[start:end]
    # strip trailing blank lines control
    if "getLastInitFailureHintKey" not in block:
        raise RuntimeError("helpers incomplete")
    if "public void shutdown()" in block:
        raise RuntimeError("helpers accidentally include shutdown")
    return block


def ensure_imports(t: str) -> str:
    if "import com.codedisaster.steamworks.SteamAPI.InitResult" not in t:
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


def strip_broken_helpers(t: str) -> str:
    """Remove any previous disposeSteamInterfaces..getLastInitFailureHintKey blocks (possibly including a
    wrongly-copied shutdown). Keep the original class shutdown/reinit."""
    # Remove from private void disposeSteamInterfaces through end of getLastInitFailureHintKey method
    # repeatedly until gone, but stop if we'd remove the only copy that is clean... we re-add later.
    pattern = re.compile(
        r"\n    private void disposeSteamInterfaces\(\) \{.*?\n    public String getLastInitFailureHintKey\(\) \{.*?\n    \}\n",
        re.S,
    )
    t2, n = pattern.subn("\n", t)
    print(f"  stripped helper blocks: {n}")
    # If a duplicate bare shutdown appeared immediately after (from bad insert), leave single shutdown.
    # Count shutdown
    return t2


def inject_helpers(t: str, helpers: str) -> str:
    if "private void disposeSteamInterfaces() {" in t and "getLastInitFailureHintKey" in t:
        print("  helpers already present after strip? skip inject")
        return t
    # Prefer inject before reinit
    if "    public boolean reinit() {" in t:
        return t.replace("    public boolean reinit() {", helpers + "    public boolean reinit() {")
    if "    public void shutdown() {" in t:
        return t.replace("    public void shutdown() {", helpers + "    public void shutdown() {")
    raise RuntimeError("no insertion point")


def ensure_init_ex(t: str) -> str:
    """If still on SteamAPI.init(), upgrade."""
    if "initEx" in t:
        return t
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
    print(f"  initEx upgrade {n}")
    return t2 if n else t


def ensure_enum(t: str) -> str:
    if "SPACEWAR_FAILED" in t:
        return t
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
    return t


def ensure_verify_call(t: str) -> str:
    if "verifySpacewarContext()" in t:
        return t
    return t.replace(
        "mySteamID = steamUser.getSteamID();\n            socketsApi = SteamSocketsApi.load();",
        "mySteamID = steamUser.getSteamID();\n\n"
        "            if (!verifySpacewarContext()) {\n"
        "                disposeSteamInterfaces();\n"
        "                SteamAPI.shutdown();\n"
        "                return false;\n"
        "            }\n\n"
        "            socketsApi = SteamSocketsApi.load();",
    )


def fix_shutdown_dupes(t: str) -> str:
    """Keep only the first public void shutdown() definition."""
    parts = list(re.finditer(r"    public void shutdown\(\) \{", t))
    if len(parts) <= 1:
        return t
    print(f"  found {len(parts)} shutdown methods — removing extras")
    # Remove second and later full method bodies (brace-matched)
    # Work from the end so indices stay valid
    for m in reversed(parts[1:]):
        start = m.start()
        i = m.end() - 1  # at '{'
        depth = 0
        j = i
        while j < len(t):
            if t[j] == "{":
                depth += 1
            elif t[j] == "}":
                depth -= 1
                if depth == 0:
                    j += 1
                    break
            j += 1
        # include following newline
        while j < len(t) and t[j] in "\r\n":
            j += 1
        t = t[:start] + t[j:]
    return t


def main():
    helpers = neo_helpers()
    print("helpers bytes", len(helpers))

    for b in BRANCHES:
        print(f"\n==== {b} ====")
        run(f"git checkout {b}")
        # don't touch clean neo 1.21.1 if already perfect
        p = Path("src/main/java/steambridge/steam/SteamManager.java")
        t = p.read_text(encoding="utf-8")
        before_sd = t.count("public void shutdown() {")
        t = ensure_imports(t)
        t = ensure_enum(t)
        t = ensure_init_ex(t)
        t = ensure_verify_call(t)
        t = strip_broken_helpers(t)
        t = inject_helpers(t, helpers)
        t = fix_shutdown_dupes(t)
        after_sd = t.count("public void shutdown() {")
        print(f"  shutdown count {before_sd} -> {after_sd}")
        assert after_sd == 1, f"shutdown count {after_sd}"
        assert "getLastInitFailureKey" in t
        assert "private void diagnoseInitFailure" in t
        assert t.count("private void disposeSteamInterfaces() {") == 1
        p.write_text(t, encoding="utf-8")

        # helper file
        helper = subprocess_show_helper()
        Path("src/main/java/steambridge/SteamAppIdHelper.java").write_text(helper, encoding="utf-8")

        run("git add -A")
        st = run("git status --porcelain")
        if st.stdout.strip():
            run('git commit -m "Fix Spacewar init diagnostics (helpers, no duplicate shutdown)."')
            print("  committed")
        else:
            print("  clean")

    run("git checkout NeoForge-1.21.1")
    print("FIXED")


def subprocess_show_helper():
    h = subprocess.check_output(
        ["git", "show", "NeoForge-1.21.1:src/main/java/steambridge/SteamAppIdHelper.java"],
        text=True,
        encoding="utf-8",
    )
    return h.replace("LOG.debug", "LOG.info")


import subprocess  # noqa: E402 — keep near bottom for style; used above via run/check_output

if __name__ == "__main__":
    main()
