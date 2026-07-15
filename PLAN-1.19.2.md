# Forge 1.19.2 port plan

Base: `NeoForge-1.20.1` (clone). Target: Minecraft Forge `1.19.2-43.5.0` (recommended) / `43.5.2` (latest).

Scope for this branch until first green build: toolchain + compile fixes only. No push unless asked. Do not edit other branches.

## Steps

1. Branch `Forge-1.19.2` from `NeoForge-1.20.1`.
2. Gradle / MDG legacy:
   - `legacyForge { version = "1.19.2-43.5.0" }` (MinecraftForge, not neoForgeVersion).
   - `minecraft_version=1.19.2`, `forge_version=1.19.2-43.5.0`.
   - Keep Java 17, steamworks4j shade, JNA compileOnly.
3. Resources:
   - `mods.toml`: loader `[43,)`, forge `[43.5.0,)`, minecraft `[1.19.2,1.20)`.
   - `pack.mcmeta`: `pack_format` 9.
4. Client GUI API (1.20.1 -> 1.19.2):
   - `GuiGraphics` -> `PoseStack`.
   - `Button.builder(...)` -> `new Button(x, y, w, h, msg, onPress)`.
   - `rebuildWidgets()` -> `init(minecraft, width, height)`.
   - Widget position: use `.x` / `.y` (no getX/setX on 1.19.2).
   - Texture blit via `RenderSystem.setShaderTexture` + `blit(PoseStack, ...)`.
5. Touch up version strings in `SteamBridgeMod` / config comments.
6. `./gradlew.bat build` (or compileJava) until first success.
7. Commit only on `Forge-1.19.2`. Messages: plain English, no em-dash, no AI co-author.

## Out of scope for first build

- Runtime playtest / Steam connect QA
- Readme matrix update
- Slim JNA bundling (only if runtime needs it)
- Push / release jar

## Known risk areas after compile

- ShareToLan has no port EditBox on 1.19.2 (layout uses fallback Y).
- Forge event names (ScreenEvent, ClientPlayerNetworkEvent) assumed present on 43.5.
- JNA supplied by loader vs compileOnly: verify when launching client.
