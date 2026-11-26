# Physics 2D

A script plugin (`samples/physics2d`, id `kuep.physics2d`, host API 2) that drops the selected layers under gravity, bounces them off each other and the frame, and writes the result as position and rotation keyframes. The keys are plain keyframes: scrub, play and export work as for any animation, and the plugin can be removed afterwards without changing the project.

## Use

1. Put the layers where the simulation should start (their position and rotation at the layer start are the starting state).
2. Select them (multi-select), open the Effects panel menu, choose Physics 2D, adjust the form and Run.
3. One undo removes all the keys.

The simulation runs for the length of the shortest selected layer.

## Form

| Input | Meaning | Default |
|---|---|---|
| Gravity | Screen heights per second squared, pulling down | 3 |
| Bounciness | 0 (no bounce) to 1 (perfectly elastic) | 0.5 |
| Friction | 0 to 1 | 0.4 |
| Floor, Left wall, Right wall, Ceiling | Which edges of the frame are solid | floor, left, right |
| Pin the first selected layer | The first selected layer never moves; others collide with it | off |
| Push right, Push down | Starting speed for every layer, screen heights per second | 0 |
| Spin | Starting rotation speed, degrees per second | 0 |
| Start with the layer's existing motion | Use the speed and spin the layer already has from its own keyframes at the start | off |
| Radius for layers without a size | Screen height fraction, used only when the layer has neither a size nor a measured outline | 0.05 |
| Quality | fast (1/60 s steps), normal (1/120), high (1/240) | normal |
| Keyframe every N frames | Bake step | 1 |

## What it simulates

- A shape layer whose shape is a circle and whose size is square is a circle. Any other shape layer is a box of its size (the layer's scale is applied). A layer without a size (a star, a drawn shape, text) is a box measured from its outline with `bounds()`. Only when that measurement is missing is it a circle of the fallback radius.
- Mass follows area. Semi-implicit Euler, impulse contacts with Coulomb friction, 6 solver iterations, positional correction, sleeping after half a second of rest. No randomness: the same input gives the same result.
- Position is in scene pixels with y downward; rotation is clockwise degrees (see `host-api.md`).

## Limits

- At most 40 layers. Keys are `layers x 2 x frames` and at most 20,000 per run: a 10 s layer at 30 fps with a keyframe every frame fits 33 layers; raise "Keyframe every N frames" for more.
- Boxes stand in for every non-circle shape (a star collides as its bounding box).
- Parent transforms and layer anchors are not considered: layers should have no parent and rotate about their centre.
- Failures are plain: nothing selected, a locked layer, more than 40 layers, too many keyframes.

## Cost

Measured on a PC JVM (Node and QuickJS agree within noise): 30 mixed bodies for 12 s run in about 100 ms at fast quality. The phone spike measured 0.14 s for 30 bodies and 1200 steps on a Galaxy A07, so the time limit is far away.
