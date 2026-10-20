# Blocklight — a custom Minecraft launcher

A fast, native desktop launcher for Minecraft. No Electron, no webviews — the UI is
JavaFX (native rendering) and the core is plain Java, so it starts instantly and
stays out of your way.

## Features

- **Microsoft account sign-in** — official OAuth 2.0 device-code flow, then Xbox Live
  → XSTS → Minecraft services. Your password is never entered in the launcher.
  Tokens are refreshed automatically before launch.
- **Offline accounts** — play on offline-mode servers with a username and the
  correct offline UUID (`OfflinePlayer` name hash).
- **Vanilla versions** — installs any version from Mojang's manifest (releases and
  snapshots), including the client jar, libraries, natives, assets, and log configs.
- **Fabric** — one-click install via the official Fabric meta API.
- **Forge** — installs via the official Forge installer jar (run automatically
  against the launcher's own versions directory).
- **Modrinth modpacks (.mrpack)** — import from the Installations tab. The pack's mods
  and overrides are downloaded with hash verification and applied into an
  *isolated instance folder* (`instances/<name>/` with its own mods, config,
  saves and options), while the client jar, libraries and assets stay shared
  with every other installation. Fabric and Forge packs are supported;
  NeoForge/Quilt packs are detected and reported. Deleting the pack from the
  Installations tab removes both halves.
- **Custom JVM settings** — memory (min/max), extra JVM arguments, Java executable,
  game directory, resolution, fullscreen.
- **Launch wrapper** — prefix the game process with any command, e.g. `mangohud --dlsym`,
  `gamescope -f --`, or `prime-run`.
- Version inheritance (`inheritsFrom`) is resolved, so modded profiles that extend
  a vanilla version work exactly like the official launcher.
- Downloads are concurrent, SHA-1 verified, resumable-by-file, and fall back
  across maven mirrors (Mojang, Fabric, Forge, Quilt) when a library isn't in a
  declared repo.

## Requirements

- Java 21 or newer (`java -version`) to run the launcher itself. Recent Minecraft
  versions (26.x) need **Java 25** for the game — the launcher detects the required
  version per version json and finds a suitable runtime (current JVM, `JAVA_HOME`,
  PATH), stopping with a clear message when there is none. The Windows installer
  bundles JRE 25 already.
- Linux, macOS, or Windows.
- A display to actually run the game (the launcher itself needs one too).

## Build & run

```bash
./build.sh     # compiles and packs out/blocklight.jar
./run.sh       # builds if needed, then launches the app
```

Dependencies (already vendored in `libs/`): Gson, JavaFX (base/graphics/controls).

## Windows installer

`windows/` builds a self-contained Windows setup executable:

```bash
cd windows
./build-installer.sh     # -> out/Blocklight-Setup-1.0-x64.exe (~37 MB)
```

The script works from Linux, macOS or WSL and needs no root access: it downloads
the JavaFX Windows natives and a Temurin JRE 25 (x64) itself, and fetches
`makensis` + NSIS stubs from Debian packages into `windows/tools/` if NSIS isn't
already on PATH. Everything downloaded once is reused on rebuilds.

The setup:

1. asks for the **Blocklight installation directory** (default:
   `%LOCALAPPDATA%\Programs\Blocklight`),
2. asks for the **Minecraft installation directory** — defaults to
   `%USERPROFILE%\.blocklight\game` but can point anywhere (portable
   folder, external drive); the choice is written to
   `%USERPROFILE%\.blocklight\settings.json` as `gameDir` (only when no
   settings file exists yet, so existing settings are never overwritten),
3. bundles its own Java runtime, creates Start-menu/desktop shortcuts (runs via
   `javaw.exe`, no console window) and an uninstaller.

The game itself is not bundled — versions/mods/assets are downloaded by the
launcher on demand, like on Linux.

## Using it

1. **Accounts** tab → *Sign in with Microsoft* (approve the code at the shown URL),
   or *Add offline account*.
2. **Versions** tab → pick a version → *Install Vanilla*, or install **Fabric** /
   **Forge** for it.
3. **Installations** tab → manage what's on disk, or *Import .mrpack modpack...*
   to add a whole Modrinth pack.
4. **Play** tab → choose account + version → **PLAY**. Progress and the game log
   stream into the console pane at the bottom.

## Tabs

| Tab | What it does |
| --- | --- |
| **Play** | Pick account + version, install-on-demand, launch, stop |
| **Versions** | Browse all Minecraft versions; install vanilla, Fabric, or Forge with live progress |
| **Installations** | Manage what's installed: see sizes, set default, open folder, **delete** installations, **import `.mrpack` modpacks**, clear shared downloads/caches |
| **Accounts** | Microsoft sign-in (device code) or offline/UUID accounts |
| **Settings** | Java, memory, JVM args, wrapper command, resolution, installation directory, force dedicated GPU, performance mode, GameMode |

### Performance mode (on by default)

Settings → **Performance mode** prepends Aikar's tuned G1GC flag set to every
launch — smaller, more predictable GC pauses mean fewer frame-time spikes while
chunks load. Heap-region sizing adapts to your max memory (8M, or 16M above 12GB),
and the preset is automatically skipped if you've set your own GC flag in
Extra JVM args, so it never conflicts with a hand-tuned setup.

Other speed measures built in:

- Downloads run **4–16 threads in parallel** with sha1 verification
- Version manifest and Forge maven metadata are **disk-cached** (app opens instantly)
- Fabric loader / Forge build lists are **session-cached**
- Natives are extracted **once per version**, not on every launch
- **Feral GameMode** support: when `gamemode` is installed, the game runs under
  `gamemoderun` (performance CPU governor, I/O priority, scheduler tuning);
  auto-detected in Settings and skipped gracefully when not installed

### Installation manager

The **Installations** tab lists every version folder in your installation directory,
including broken/incomplete leftovers you can clean up:

- Per-installation size and type (Vanilla / Fabric / Forge / Modpack) with complete/incomplete status
- **Delete installation...** — removes only `versions/<id>` (confirmation shows the size);
  for modpacks the pack's `instances/<id>` folder (mods/config/saves) is removed as well.
  Shared `libraries/`, `assets/`, other versions, and accounts are untouched
- **Set as default version** for the Play tab
- **Open folder** in your file manager (packs also get *Open pack folder* for
  mods/config/saves)
- **Maintenance**: clear shared downloads (libraries & assets — re-download automatically
  on next launch) or clear metadata caches

Each version lives in its own `versions/<id>` folder, so deleting one installation
never affects any other.

## Data locations

| What | Where |
| --- | --- |
| Settings | `~/.blocklight/settings.json` |
| Accounts (tokens obfuscated, not encrypted) | `~/.blocklight/accounts.json` |
| Metadata caches (version list, Forge maven) | `~/.blocklight/cache` |
| Installation directory | `~/.blocklight/game` by default, changeable in Settings |
| Modpack instances (mods/config/saves per pack) | `<installation dir>/instances/<pack name>` |

> **Upgrading from the old data dir:** the launcher moved its home from
> `~/.mc-custom-launcher` to `~/.blocklight`. On first run, everything
> (settings, accounts, caches, game files) is migrated automatically — nothing
> to do manually.

**Multiple versions coexist safely:** the installation directory is laid out like a
standard `.minecraft` — every version gets its own subfolder under `versions/`
(`versions/1.21.4`, `versions/fabric-loader-0.19.5-1.21.4`,
`versions/26.3-forge-66.0.13`, …). Vanilla, Fabric, and Forge installs never
overwrite each other, and they all share the downloaded `libraries/` and
`assets/` so disk usage stays low. Changing the installation directory in
Settings simply switches to a different set of versions (e.g. a portable copy
on an external drive).

## Notes

- Pre-1.17 Minecraft versions need an older Java (8–17). Install one and point
  *Settings → Java path* at it; the launcher warns when the current JVM is too old
  for the selected version.
- Forge installs by running the official installer in-process (it generates the
  version profile the launcher then picks up). Check the console pane for its output.
- This is an independent project, not affiliated with Mojang or Microsoft.

## Project layout

```
src/main/java/dev/mclauncher/
├── MainLauncher.java        # entry point (non-Application wrapper for fat-jar launches)
├── App.java                 # window shell, sidebar navigation, console
├── core/                    # manifest, version chains, rules, downloads, natives, launch
├── auth/                    # Microsoft device-code OAuth, offline accounts, storage
├── install/                 # Fabric (meta API) and Forge (official installer) 
├── storage/                 # settings persistence
└── ui/                      # Play / Versions / Accounts / Settings views + theme
```

## License

GPL-3.0 — see [LICENSE](LICENSE).
