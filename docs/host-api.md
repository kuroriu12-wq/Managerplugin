# Host API 2: scripts

A script is one JavaScript file (`scripts/<name>.js`) listed in `contributes.scripts`. The manifest must say `"api": 2`. A plugin that says `"api": 1` and lists scripts is rejected. Plugins that say `1` keep working unchanged.

Scripts run in an embedded QuickJS engine, the same on Android and PC. Modern JavaScript works (arrow functions, `let` and `const`, classes, destructuring, spread, template strings, Map and Set). There is no `import`, `require`, `fetch`, timer, file or network object. A script sees only the standard built-ins, `defineScript`, `console.log` and the `ctx` object below.

## Shape of a script

```js
defineScript({
  id: "nudge",
  name: "Nudge",
  inputs: [
    { id: "dx", label: "Move right (pixels)", type: "number", default: 100, min: -5000, max: 5000 }
  ],
  run(ctx) {
    for (const layer of ctx.selection()) {
      const from = layer.position(0);
      layer.setKeys("position", [0, 1], [from, { x: from.x + ctx.input.dx, y: from.y }]);
    }
  }
});
```

- Call `defineScript` exactly once. `id` starts with a letter or underscore and uses letters, digits and underscores. `name` is required. `run` must be a function.
- `inputs` use the same types and rules as recipe inputs: `number` (with `min`, `max`), `switch`, `choice` (with `options`), `color`, `text`. The app draws the form.
- The file is evaluated twice: once when the app lists scripts (only to read `id`, `name` and `inputs`; `ctx` does not exist then) and once per run. Keep top-level code free of side effects.

## ctx

| Member | Meaning |
|---|---|
| `ctx.scene` | `{ width, height, fps, duration }`. Pixels, frames per second, seconds. |
| `ctx.input` | The form values: numbers as numbers, switches as booleans, everything else as text. |
| `ctx.selection()` | Array of layer handles, in the order the user selected them (the selected layer if there is no multi-selection). |
| `ctx.log(text)`, `console.log(text)` | Collected and shown after the run. At most 100 lines of 500 characters. |

## Layer handle

| Member | Meaning |
|---|---|
| `id`, `kind`, `name`, `locked` | Layer id, kind (`shape`, `text`, `drawing`, `embedScene`, `camera`, `audio`, `nullobj`, `model`, `light`), label, lock state. |
| `shape` | The shape id for shape layers (for example `.circle`, `.rect`, `.star`), empty text for other kinds. |
| `start`, `finish` | Scene time in seconds. |
| `size()` | `{ width, height }` for shape layers, `null` for every other kind. The app has no bounds call for text or media yet. |
| `position(t)` | `{ x, y }` at `t` seconds after the layer start. |
| `rotation(t)` | Degrees at `t` seconds after the layer start. |
| `scale(t)` | `{ x, y }` scale factor at `t` seconds after the layer start. |
| `setKeys(property, times, values)` | Replace the keyframes of `"position"`, `"rotation"`, `"scale"` or `"opacity"`. |
| `addEffect(effectId, { param: value })` | Add an effect to the end of the stack, with optional parameter values. |
| `setEffectParam(effectId, param, value)` | Set a parameter on the last instance of the effect. |
| `keyframeEffectParam(effectId, param, times, values)` | Keyframe a parameter. |

Effect operations are the recipe operations, so effect ids, parameters, ranges and error messages are the same as for recipes. Values may be numbers, text or booleans.

### Coordinates and units (read from the app and pinned by tests)

- Position is in scene pixels. The origin is the top left of the scene and **y grows downward**. A position can be outside the frame.
- Rotation is in degrees, clockwise positive, the same as the Rotation field in the app. (The file stores the negative value; the host converts both ways.)
- Scale is a factor: `1` is 100 percent. A number scales both axes; `{ x, y }` scales them separately.
- Opacity is 0 to 1.
- All times in handle methods are seconds from the layer start, not from the scene start. `start` and `finish` are the only scene-time values.

### setKeys

- `times` must be a non-empty, strictly ascending list inside the layer (from 0 up to its length).
- `values` has one entry per time: a number for `rotation` and `opacity`; a number or `{ x, y }` for `scale`; `{ x, y }` for `position`.
- It replaces every existing key of that property on that layer. Keys are written linear.
- Numbers must be finite.

## Rules and limits

- Only layers in the selection can be read or changed. A locked layer is refused. Ids a script invents are refused.
- A run cannot create or delete layers, touch files or the network, or draw.
- Time limit 15 seconds, memory 64 MB, stack 256 KB. The user can cancel.
- At most 20,000 keyframes and 200 layers per run.
- A run is all or nothing and one undo step. If anything fails, nothing changes and the message names the line. If the project is edited while a script runs, the result is discarded.
- Host objects are frozen. Replacing `ctx` members or `console` has no effect.
- Script source is limited to 512 KB, and loading a script is limited to 3 seconds.
