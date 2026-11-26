# Managerplugin

Plugin spec, JSON schemas and sample plugins for the AbsentMotion Manager.

Host API versions: **1** (recipes, effects) and **2** (adds scripts). The app that loads these plugins lives in `kuroriu12-wq/AbsentMotion-test-plugin`.

## What a plugin is

A `.zip` you install from the Plugins screen in AbsentMotion. It works the same on PC and Android. It holds data only: no compiled or native code. It can contribute three kinds of things:

- **Recipes**: a form plus a list of steps that edit the selected layer. One run is one undo step. If any step fails, nothing is changed.
- **Effect packs**: shader effects (SkSL) that appear in the effect browser next to the built-in ones.

- **Scripts** (host API 2): a JavaScript file that reads the selected layers and writes keyframes and effects, for tools a recipe cannot express (physics, distribution, procedural motion). See `docs/host-api.md` and `samples/hello_script/`.

## Zip layout

```
myplugin.zip
  manifest.json
  recipes/*.json
  effects/index.json
  effects/<key>.json
  scripts/*.js
  assets/
```

Files go at the zip root, not inside a folder. Paths use `/`. Entries with `..`, a leading `/`, a drive prefix or `\` are rejected. Limits: 500 files, 20 MB unpacked.

## manifest.json

```json
{
  "id": "kuep.shakeintro",
  "name": "Shake Intro",
  "version": "1.0",
  "api": 1,
  "permissions": [],
  "contributes": {
    "recipes": ["recipes/shake.json"],
    "effects": [],
    "scripts": []
  }
}
```

| Field | Rule |
|---|---|
| `id` | `owner.name`, lowercase letters, digits, underscores. `absent.` is reserved. Installing an id that exists asks to replace it. |
| `name`, `version` | Non-empty text. |
| `api` | The integer `1` or `2`. Scripts need `2`. |
| `permissions` | List of text. Shown at install. Nothing is granted yet. |
| `contributes.recipes` | Paths of recipe files inside the package. |
| `contributes.effects` | Effect keys (letters, digits, underscores). Each needs `effects/<key>.json` and an entry in `effects/index.json`. |
| `contributes.scripts` | Paths of `.js` files inside the package. Needs `api` 2. |

A plugin that fails to load is disabled and shows its error. It never blocks startup.

## Recipes

```json
{
  "id": "shake",
  "name": "Shake intro",
  "inputs": [
    { "id": "strength", "label": "Strength", "type": "number", "default": 0.6, "min": 0, "max": 1 }
  ],
  "steps": [
    { "do": "addEffect", "effect": "absent.glitch", "set": { "intensity": "$strength" } },
    { "do": "setEffectParam", "effect": "absent.glitch", "param": "blockHeight", "value": 20 },
    { "do": "keyframeEffectParam", "effect": "absent.glitch", "param": "intensity", "at": [0, 0.3], "values": ["$strength", 0] }
  ]
}
```

Input types: `number`, `color`, `choice` (needs `options`, default must be one of them), `text`, `switch`. Number inputs must be finite and inside `min`..`max` when given. Any value can be `"$inputId"`.

Steps:

- `addEffect`: `effect`, optional `preset` (preset name) and `set` (param id to value). Appends the effect to the layer.
- `setEffectParam`: `effect`, `param`, `value`. Acts on the last effect on the layer with that id.
- `keyframeEffectParam`: `effect`, `param`, `at` (seconds from the layer start) and `values`, same length.

An unknown effect, an undefined `$input` or a bad number fails the whole run and changes nothing.

## Effect packs

`effects/` has the same layout as the app's built-in effects: `index.json` lists the keys, and each `<key>.json` has `id`, `name`, `category`, `description`, `params` and `sksl`. See `samples/pulseglow/`.

- The effect id in the app becomes `<pluginId>.<key>`.
- Plain shader effects only: no `native` flag.
- A broken effect is dropped on its own; the others in the pack still load.
- Enabling, disabling or removing an effect pack takes effect after the next app start.
- Disabling or removing a plugin does not touch projects. Layers keep the effect and it shows greyed out until the plugin is back.

## Permissions

Listed in the manifest and shown at install. Version 1 grants none: no file access, no network.

## Pack and check

```
pip install jsonschema
python3 tools/check_samples.py
python3 tools/pack.py samples/shakeintro out/shakeintro.zip
python3 tools/pack.py samples/pulseglow out/pulseglow.zip
python3 tools/pack.py samples/physicsmotion out/physicsmotion.zip
```

`check_samples.py` validates every folder in `samples/` against the schemas and checks the rules a schema cannot (`$input` references, matching keyframe lists, listed files exist). It also runs the `tools/fixtures/bad_*.json` files and expects each to be invalid. Exit code 0 means everything behaved as expected.

## Layout

- `schema/`: `manifest.schema.json`, `recipe.schema.json`
- `samples/`: `shakeintro` (recipe), `pulseglow` (effect pack), `physicsmotion` (effect pack plus recipes, see `docs/physicsmotion.md`)
- `tools/`: `pack.py`, `check_samples.py`, `gen_physicsmotion.py`, `fixtures/`
- `docs/design.md`: the full design
- `docs/physicsmotion.md`: the Physics Motion sample and what it can and cannot do
