# -*- coding: utf-8 -*-
from pathlib import Path
import re
import subprocess

subprocess.check_call(["git", "checkout", "-f", "Forge-1.16.5"])

p = Path("src/main/java/steambridge/gui/VanillaGuiIntegration.java")
t = p.read_text(encoding="utf-8")
pat = re.compile(
    r"if \(parent == null\) parent = mc\.screen;\s*"
    r"if \(!SteamManager\.getInstance\(\)\.isInitialized\(\) && !SteamManager\.getInstance\(\)\.reinit\(\)\) \{\s*"
    r"event\.setGui\(new GuiSteamResync\(\s*"
    r"parent,\s*"
    r"\(\) -> Minecraft\.getInstance\(\)\.setScreen\(beginSteamConnect\(parent, sd\.ip\)\),\s*"
    r'"steambridge\.gui\.resync_success_hint_connect"\)\);\s*'
    r"\} else \{\s*"
    r"event\.setGui\(beginSteamConnect\(parent, sd\.ip\)\);\s*"
    r"\}",
    re.S,
)
repl = (
    "if (parent == null) parent = mc.screen;\n"
    "                final Screen connectParent = parent;\n"
    "                final String steamAddr = sd.ip;\n"
    "                if (!SteamManager.getInstance().isInitialized() && !SteamManager.getInstance().reinit()) {\n"
    "                      event.setGui(new GuiSteamResync(\n"
    "                              connectParent,\n"
    "                              () -> Minecraft.getInstance().setScreen(beginSteamConnect(connectParent, steamAddr)),\n"
    '                              "steambridge.gui.resync_success_hint_connect"));\n'
    "                  } else {\n"
    "                      event.setGui(beginSteamConnect(connectParent, steamAddr));\n"
    "                  }"
)
t2, n = pat.subn(repl, t, count=1)
print("vanilla n", n)
if n == 0:
    # show snippet
    i = t.find("beginSteamConnect(parent, sd.ip)")
    print(repr(t[i - 200 : i + 120]))
else:
    p.write_text(t2, encoding="utf-8")

r = Path("src/main/java/steambridge/gui/GuiSteamResync.java")
rt = r.read_text(encoding="utf-8")
rt = rt.replace("super(Component.empty());", 'super(new StringTextComponent(""));')
if "import net.minecraft.util.text.StringTextComponent" not in rt and "StringTextComponent" in rt:
    pass
r.write_text(rt, encoding="utf-8")
print("resync Component.empty left?", "Component.empty" in rt)

subprocess.check_call(["git", "add", "-A"])
st = subprocess.check_output(["git", "status", "--porcelain"], text=True)
print(st)
if st.strip():
    subprocess.check_call(
        [
            "git",
            "commit",
            "-m",
            "Fix 1.16.5 Steam resync compile (effectively final + StringTextComponent).",
        ]
    )
print("done")
