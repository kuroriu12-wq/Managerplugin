# Manager Plugin for AbsentMotion: Design

Date: 2026-11-21
Status: draft, waiting for approval

## Intent and why

AbsentMotion is a video/motion editor (Android + PC, shared Kotlin UI, shared C++/Skia engine).
Today everything it can do is built in. Kuep wants an Adobe-style plugin system: a user installs
a plugin through a Manager, and the plugin adds a whole custom feature (a custom editing tool,
panel or workflow, not only an effect). The plugin lives outside AbsentMotion's own code and
must work on PC and phone. It starts as a test plugin, so the first version stays simple.

## What exists today (relevant facts)

- There is no plugin or extension system. Searches for "plugin" and "extension" in the repo find nothing relevant.
- Effects are built in. Each effect is a JSON file in `shared/assets/effects/` (id like `absent.glitch`, params, presets, i18n, help page). Kotlin loads them into `EffectCatalog` / `EffectDefinition`. The Effects panel and effect browser are driven by that catalog.
- The native side is `EffectRuntime` (`shared/cpp/effects.cpp`), which renders effects with Skia `SkRuntimeEffect` shaders.
- Editing goes through `EditorState` and scene/layer functions such as `Layer.addEffect`, `withEffectProperty`, keyframes, `state.edit {}` / `editSelected {}`.
- The PC build (`pc/`) runs the same shared Kotlin UI using stand-ins for Android APIs. Android code is in `android/`.
- The `Managerplugin` GitHub repo exists and is empty. The AbsentMotion repo in Graphify is `kuroriu12-wq/AbsentMotion-test-plugin`.

## Goals

1. A Manager screen in AbsentMotion: install a plugin from a `.zip`, list installed plugins, enable, disable, remove.
2. A single plugin package format with one `manifest.json` that supports three kinds of contribution (below).
3. Same behaviour on PC and Android.
4. Plugins cannot crash the app or corrupt a project, and cannot read files or use the network unless a permission is declared and shown to the user at install.
5. Plugin logic stays out of AbsentMotion core. The app gets a thin loader and host API only.
6. First version is small. Each layer ships on its own.

## Non-goals (v1)

- A plugin store, marketplace, accounts, ratings, or signing infrastructure.
- Plugins that ship native or compiled Kotlin/Java code.
- Plugins that run in a separate process.
- Network and file permissions actually being granted (the manifest field exists, the host denies them for now).
- Plugin tools that react to canvas gestures (drags and taps on the preview) or draw overlays. Planned for host API version 2.

## Anti-goals

- A plugin that can freeze the editor with no way to stop it.
- A plugin run that leaves a project half-edited after a failure.
- Plugin code scattered through the editor UI instead of going through the host API.

## Approaches considered

| | Idea | Trade-off |
|---|---|---|
| A | Data-only packs: JSON + shaders + assets loaded into the existing effect catalog | Safe, same everywhere; can only add effects, presets, templates |
| B | Scripted plugins: manifest + script run by an embedded engine against a host API | Custom tools and panels on both platforms; needs an engine and a sandbox |
| C | Separate programs talking over IPC | Most power; Android makes it very hard, needs a build per OS |
| D | Compiled Kotlin plugins | Full power; no sandbox, Android restricts loading downloaded code |
| E | HTML/JS web-panel plugins | Easy to write, sandboxed; PC needs an embedded browser, cannot draw into the editor |
| F | Recipe plugins: JSON list of steps plus an input form | Simplest and safest; no loops or conditions |

**Picked: B, A and F together** (Kuep's choice), as layers of one system. C, D and E are rejected for v1.

## Design

### Package format

```
myplugin.zip
  manifest.json
  recipes/*.json      (F)
  effects/*.json      (A, same schema as shared/assets/effects, plus .sksl shaders)
  scripts/main.lua    (B)
  assets/             (images, fonts, etc.)
```

`manifest.json` declares: `id` (unique, e.g. `kuep.shakeintro`), `name`, `version`, `api` (host API
version it targets), `contributes` (which of recipes / effects / script entries it adds), and
`permissions` (list; v1 host grants none).

### Manager (host side, in AbsentMotion)

- Plugins tab: Install (pick a .zip), list, enable/disable, remove, show permissions.
- Install validates the manifest and every file against a schema before anything is copied.
- Plugins are stored in an app-private plugins folder (platform-specific path, same layout).
- Loaded at app start and when installed. A plugin that fails to load is disabled and shows its error. It never blocks startup.
- Plugin with an `api` version the host does not support is disabled with a clear message.
- `api` is a single integer. The host lists the versions it supports; v1 supports `1` and requires an exact match.
- Plugin ids: anything starting with `absent.` is reserved for built-ins, so a plugin using it is rejected. Installing a plugin whose id is already installed asks the user to confirm replacing it (an update).
- Disabling or removing a plugin does not touch projects. Layers that use a plugin effect keep the effect node and its id in the project file. The Effects panel already handles an effect id with no definition (it shows the id and greys the settings out). The effect is simply not rendered until the plugin is back. Not yet confirmed: that the native renderer skips an unknown id without error, so this needs a test in step 2 of the build order.

```
Plugins tab -> Install -> pick .zip -> validate -> permissions shown -> installed
   -> loaded at startup -> contributions appear (tool / effect / recipe in a list)
```

### Layer F: recipes

A recipe has `inputs` (typed form fields: number, color, choice, text, switch) and `steps`.
Each step is one call into existing editor operations (add effect, set property, keyframe, add layer, ...).
Values can reference inputs (`$strength`). Running a recipe applies all steps as **one undo step**.
If any step fails the whole run is rolled back. No loops or conditions.

How rollback works (checked in the code): `Scene` and `Layer` are immutable (`Layer.copy(node = ...)`).
`EditorState.edit {}` takes a function from the current scene to a new scene and pushes one history entry.
So the runner folds all steps over a copy of the scene and calls `edit { next }` once at the end. A
failure or a budget overrun just never commits; nothing needs to be aborted. The same rule applies to
Lua scripts: every host-API edit works on a pending scene that is committed once per user action.

### Layer A: effect packs

Effect JSON in a plugin uses the existing effect schema. The loader adds those definitions
to `EffectCatalog` so they show up in the Effects browser next to built-in effects, marked as
coming from a plugin. Shaders are compiled at load. A shader that fails to compile disables only that effect.

Checked in the code: at startup `EngineThread` reads `EffectCatalog.read(context)`, turns every definition
into XML with `EffectDefinition.runtimeXml()` (the SkSL source goes in an `sksl` attribute) and passes it to
`NativeEngine.loadEffect(xml)`, which calls `EffectRuntime::load`. So plugin effects need no new native code:
merging their definitions into `EffectCatalog.read` is enough. Because the native runtime has no unload call,
v1 applies changes to which plugin effects are enabled on the next app start (my call). Limit for v1:
plugin effects are plain shader effects only. Effects that need native behaviour (the `native` flag,
backdrop/blur/glyph passes, puppet, particles) stay built-in only.

### Layer B: scripts

- Engine: Lua through LuaJ (pure JVM, so identical on Android and PC, no native libraries). This is my call.
- Plugins talk to the app only through a host API: read the scene and selection, edit the scene (same operations as recipe steps), declare panels and forms with data (the app draws them with its own components), show messages.
- No access to files, network, OS, or Java classes. Anything not in the host API does not exist for the script.
- Runtime limits: an instruction/time budget per call, enforced by a hook; going over stops the script and rolls back the edit.
- Script edits also go through one undo step per user action.

### Repos

- `Managerplugin` repo: the manifest spec and JSON schema, the host API reference, sample plugins (one per layer), and the packaging steps.
- AbsentMotion repo: the Manager screen, loader, validator, recipe runner, script host.

### Build order

1. F (manifest, validator, Manager screen, recipe runner) with one sample recipe plugin.
2. A (effect packs into the catalog) with one sample effect plugin.
3. B (Lua host and API) with one sample script plugin.

Each step is shippable on its own.

## Decisions Kuep made

- B, A and F all included, as layers.
- Plugins are whole custom features, not only effects.
- Works on PC and phone. Simple. Kept separate from AbsentMotion core.
- A plugin tool in v1 is form-and-run: a menu entry that opens a data-declared form, then runs a recipe or script. Canvas gestures wait for API version 2.

## My calls (change any of these)

- Lua via LuaJ for scripts.
- Side-load from a `.zip` only; no store.
- Permissions exist in the manifest and are shown at install, but v1 grants none.
- One undo step and all-or-nothing rollback per run.
- Failed plugins are disabled, not fatal.
- Spec, schema and samples live in `Managerplugin`; loader and UI live in AbsentMotion.
- This design doc lives in `docs/superpowers/specs/` and is meant to be copied into the `Managerplugin` repo.
- The AbsentMotion work targets `kuroriu12-wq/AbsentMotion-test-plugin` (the repo you pointed me at; it is the test app).
- `api` is one integer with exact match in v1.
- Plugin ids starting with `absent.` are rejected; reinstalling an existing id is a confirmed update.
- Removing or disabling a plugin leaves its effects in projects, unrendered, and they come back when the plugin does.
- Plugin effects are plain shader effects only in v1 (no native-flagged effects).

## Left to the builder

- Exact list of recipe step types and host API function names.
- Where in the app the Plugins tab and plugin-provided tools appear.
- Exact folder paths and zip layout details per platform.
- Script budget numbers (instruction count, time).
- Test approach for the loader, validator and recipe runner. JVM unit tests already exist for the shared Kotlin code (`android/src/test`, `pc/src/test`), so the builder can follow that pattern.
