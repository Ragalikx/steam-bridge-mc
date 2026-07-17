# -*- coding: utf-8 -*-
from pathlib import Path
import re
import subprocess

subprocess.check_call(["git", "checkout", "-f", "NeoForge-1.21.1"])
p = Path("src/main/java/steambridge/steam/SteamManager.java")
t = p.read_text(encoding="utf-8")

t = t.replace(
    '                        SteamBridgeMod.LOG.info("[SteamManager] Calling SteamAPI.init()...");',
    '        SteamBridgeMod.LOG.info("[SteamManager] Calling SteamAPI.init()...");',
)
t = t.replace(
    "            mySteamID = steamUser.getSteamID();\nsocketsApi = SteamSocketsApi.load();",
    "            mySteamID = steamUser.getSteamID();\n            socketsApi = SteamSocketsApi.load();",
)

# remove leftover InitFailure assignment lines
t = re.sub(r"\s*lastInitFailure = InitFailure\.[A-Z_]+;\r?\n", "\n", t)

# remove verifySpacewar block if still present
t = re.sub(
    r"\n\s*if \(!verifySpacewarContext\(\)\) \{.*?\n\s*\}\n",
    "\n",
    t,
    count=1,
    flags=re.S,
)

# orphan javadoc about Spacewar context
t = re.sub(
    r"\n    /\*\*\n     \* After a successful SteamAPI init.*?allowed\n     \*/\n+",
    "\n",
    t,
    count=1,
    flags=re.S,
)

# orphan InitFailure javadoc
t = re.sub(
    r"\n    /\*\*\n     \* Why the last \{@link #init\(\)\}.*?\n     \*/\n",
    "\n",
    t,
    count=1,
    flags=re.S,
)

# collapse duplicate reinit javadocs to one immediately before reinit()
t = re.sub(
    r"(?:\n    /\*\*\n     \* Shuts down and re-initializes Steam\. Used by the \"resync Steam\" button in GUI\.\n     \*\n     \* @return \{@code true\} if re-initialization succeeded\n     \*/\n+)+(\s*public boolean reinit\(\))",
    "\n    /**\n     * Shuts down and re-initializes Steam. Used by the \"resync Steam\" button in GUI.\n     *\n     * @return {@code true} if re-initialization succeeded\n     */\n\\1",
    t,
    count=1,
)

# remove unused disposeSteamInterfaces method if not called
if "disposeSteamInterfaces()" not in t.replace("private void disposeSteamInterfaces()", "X"):
    t = re.sub(r"\n    private void disposeSteamInterfaces\(\) \{.*?\n    \}\n", "\n", t, count=1, flags=re.S)

# remove SteamApps/InitResult imports if present
t = t.replace("import com.codedisaster.steamworks.SteamAPI.InitResult;\n", "")
t = t.replace("import com.codedisaster.steamworks.SteamApps;\n", "")

p.write_text(t, encoding="utf-8")
bad = [k for k in ["InitFailure", "verifySpacewar", "SteamApps", "lastInitFailure", "initEx"] if k in t]
print("bad leftover:", bad)

subprocess.check_call(["git", "add", "-A"])
st = subprocess.check_output(["git", "status", "--porcelain"], text=True)
print(st)
if st.strip():
    subprocess.check_call(
        ["git", "commit", "-m", "Finish removing Spacewar detection remnants on NeoForge 1.21.1."]
    )
print("ok")
