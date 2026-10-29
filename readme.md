# Steam Bridge

Batch cleanup, per-peer queue control and diagnostic export are described in [reliability checks](docs/RELIABILITY.md). Minecraft UUID remains unchanged. Real Steam connections and in-game startup still require testing.

Routine events use DEBUG. Recoverable failures include connection context and suppress repeated messages for 30 seconds; startup failures remain ERROR. [Logging behavior](docs/LOGGING.md).

[Русский](readmeru.md) · [Discord](https://discord.gg/2xBnJ7awRC) · [Author](https://github.com/Ragalikx)

Steam Bridge connects Minecraft LAN worlds through Steam Networking Sockets, using direct P2P or Valve relays. Install one mod JAR matching your Minecraft version, loader and OS. Steam and Spacewar (AppID 480) must be running for both players.

## Multiloader development

`26.x` has 11 legacy adapters plus one NeoForge 26.x package covering 26.1, 26.2 and 26.3 through internal adapters. Legacy networking uses Java 8-compatible shared sources; 26.x uses a Java 25 Panama backend. This is a development build pending real Minecraft and Steam testing. The exact imported revisions are recorded in [variants.json](variants.json).

```text
core/             attempts, handle ownership, JSON settings, bounded caches, UDP sockets
steam-jna/        legacy JNA backend and shared voice adapter sources
steam-panama/     Java 25 FFM bindings and generated shared runtime for 26.x
transport-netty/  bounded queues, backpressure, local TCP bridge
runtime/          common manager/client/server lifecycle and social stores
integrations/     release-JAR voice API contract checks
adapters/         Minecraft, loader, GUI and lifecycle integration
build-logic/      shared-source compilation and platform packaging
tools/            builds, tests, native linkage checks
```

Shared sources compile into each adapter's JAR. Players do not install a separate core mod or gateway application. Manager/client/server orchestration, connection state, bans, world settings and profile caches are shared Java 8 code. Adapter facades retain Minecraft screens, login pipelines, scheduling and texture upload. Each adapter keeps its own Gradle wrapper because the oldest ForgeGradle versions cannot run under the modern root wrapper.

## Targets

| Target | Minecraft JVM | Build JVM |
| --- | --- | --- |
| forge-1.7.10 | 8 | 8 |
| forge-1.8.9 | 8 | 8 |
| forge-1.12.2 | 8 | 8 |
| forge-1.16.5 | 8 | 17 |
| forge-1.19.2 | 17 | 17 |
| fabric-1.16.5 | 8 | 17 |
| fabric-1.19.2 | 17 | 17 |
| fabric-1.20.1 | 17 | 17 |
| fabric-1.21.1 | 21 | 21 |
| neoforge-1.20.1 | 17 | 17 |
| neoforge-1.21.1 | 21 | 21 |
| neoforge-26.x (26.1 / 26.2 / 26.3) | 25 | 25 |

Legacy targets are exact Minecraft releases. NeoForge 26.x has a shared package with three internal adapters; the pinned compile targets and remaining runtime checks are documented in [the 26.x port](docs/NEOFORGE_26X.md). One repository produces separate compatible JARs. Host and guest must use compatible Minecraft versions and modpacks. The NeoForge 1.20.1 adapter uses the original `net.neoforged:forge` 47.1.106 dependency; it is not a NeoForge 20.x build.

## Usage

1. Put the matching Steam Bridge JAR in `mods` on both computers. Fabric also needs Fabric API.
2. Start Steam and Spacewar, then Minecraft.
3. Host: open a single-player world to LAN through Steam Bridge.
4. Guest: select the host in the Steam friends screen, or use the host's SteamID64 in Direct Connect.

Windows users install the main JAR. Linux x64 users install the same main JAR plus one optional SBM Linux Runtime. Four runtime JARs cover the supported targets, grouped by Java 8, 17, 21 and 25. Choose the group for your Minecraft/loader target, even when launching with a newer JVM. [Linux installation matrix and test scope](docs/LINUX_RUNTIME.md). Actual Linux JVM execution and Minecraft/Steam gameplay remain pending. ARM, 32-bit and macOS packages are unavailable.

For NeoForge 26.x, install one `steambridge-neoforge-26.x-*.jar` for all three target versions. It bundles the Steam SDK DLL and uses Project Panama directly, with no JNA or steamworks4j JNI bridge. [26.x development status](docs/NEOFORGE_26X.md).

## Configuration and voice

Hosts can send Steam invitations from session management. Accepting an invitation while Minecraft with SBM is running opens the usual connection screen from the menu after validating the current host presence. Closed-game launcher startup is unsupported. [Invite behavior and remaining two-account checks](docs/STEAM_INVITES.md).

All adapters use `config/steambridge.json`. The complete configuration is validated before applying an immutable snapshot. A missing JSON file is created atomically. Existing Forge/NeoForge `steambridge-client.toml`, `steambridge-common.toml` or `steambridge.cfg` settings are migrated when JSON is absent; the original file is preserved. Invalid configuration is logged and the previous settings remain active. Restart Minecraft after editing. An [example configuration](docs/steambridge.example.json) lists all settings. Virtual ports are 0..65534 because voice uses the next virtual port.

With `interceptUdp`, Simple Voice Chat uses its socket API when available. Ordinary IP connections keep the default client socket, and sockets already installed by another voice plugin are preserved. Older Simple Voice Chat without the client socket API retains a filtered JVM socket factory fallback. Forge 1.7.10/1.8.9 retain their original lack of a voice tunnel.

Plasmo uses its published addon and UDP client APIs with a small local relay; the previous voice mixins were removed. The default Netty UDP implementation is wrapped, while clients supplied by other addons are preserved. Server start/stop events record the actual bind address and port, including IPv6. Each accepted game peer can hold one voice connection; a stale game session cannot revoke its replacement. Both SBM backends pass API contract checks against 13 pinned Plasmo/Simple Voice Chat JARs, including nine NeoForge 26.x builds. Real voice sessions still require Minecraft and Steam testing. Install the chosen voice mod separately. Voice API, Plasmo, Kotlin and ASM classes are not bundled. [Voice scope and source references](docs/VOICE_COMPATIBILITY.md) describe version and endpoint limits. Simple Voice Chat and Plasmo cannot share the same client voice tunnel simultaneously.

TCP close now allows up to two seconds to drain queued data. A successful outbound drain requests Steam linger; peer close flushes received data to Minecraft before closing the local socket. Cancellation and transport errors still close immediately. Profile caches hold up to 256 entries per cache, with avatar texture cleanup on eviction.

## Building and verification

Install JDK 8, 17, 21 and 25. Set `JAVA_HOME_8`, `JAVA_HOME_17`, `JAVA_HOME_21`, `JAVA_HOME_25`, or use the JDK cache populated by `tools/fetch_jdks.py` on Windows. Downloads are SHA-256 checked. Python 3 is needed only for development tools.

```text
python tools/test.py
python tools/verify_voice.py --download
python tools/verify_abi.py --download
python tools/build.py --variant fabric-1.21.1 --platform windows-x64
python tools/build.py --variant neoforge-26.x
python tools/verify_native.py --variant fabric-1.21.1
```

Builds default to `0.0.0-dev`. Use `--version 1.3.0` to override it. Production JARs, individual SHA-256 files and `build-results.json` are written under `dist/<platform>/`. Shared test reports are copied to `build/test-reports/java-<runtime>/`. Native checks load the libraries and resolve Steam exports; they do not initialize Steam or establish a real session.

[Verification and release checklist](docs/TESTING.md) describes remaining real-world checks. ABI checks compare JNA and Panama against a pinned Steamworks SDK 1.62 snapshot, including native function signatures and callback layouts. The source is a public mirror with normalized line endings; the original Valve ZIP has not been verified. [Scope and provenance](docs/STEAMWORKS_ABI.md).

## CI

The GitHub Actions workflow tests the shared modules on Java 8/17/21, compares C and JNA layouts on Windows/Linux, checks voice API linkage and builds the main packages. The 26.x job tests Panama on Java 25 and checks all three internal adapters. Ubuntu jobs cover native runtime contracts for all 11 legacy targets and the Java 25 addon. Only four grouped Linux runtime JARs are exported. Artifacts get SHA-256 files and non-PR attestation steps. The workflow does not publish releases and has not run on GitHub yet.

A hash detects changed bytes. An [artifact attestation](https://docs.github.com/en/actions/concepts/security/artifact-attestations) identifies the workflow and source revision that produced them. Neither certifies gameplay correctness. The mutable ForgeGradle 2.1 snapshot is pinned to a timestamped artifact and checked by SHA-256. Ten critical native/API dependencies have checksum locks; this does not cover the entire transitive Minecraft toolchain or establish reproducible builds.

## License

Steam Bridge is [MIT licensed](LICENSE), copyright Ragalikx. Required dependency notices remain in the mod artifacts. Steam belongs to Valve; this project is not affiliated with Valve.

The [previous branch documentation](docs/legacy/README.md) is retained for historical reference. Its build and compatibility statements do not describe `26.x`.
