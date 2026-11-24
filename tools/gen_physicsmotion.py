#!/usr/bin/env python3
"""Writes samples/physicsmotion/effects/*.json from the shader sources below.

Run from anywhere: python3 tools/gen_physicsmotion.py
"""
import json
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "samples" / "physicsmotion" / "effects"

BOUNCE = """uniform shader image;
uniform float2 size;
uniform float time;
uniform float4 screen;
uniform float height;
uniform float gravity;
uniform float bounce;
uniform float squash;
uniform float delay;

half4 main(float2 coord) {
    float H = max(height, 0.0) * screen.w;
    float G = max(gravity, 0.01) * screen.w;
    float e = clamp(bounce, 0.0, 0.95);
    float t = max(time - delay, 0.0);
    float lift = 0.0;
    float since = 0.0;
    float power = 0.0;
    if (H > 0.5) {
        float t0 = sqrt(2.0 * H / G);
        if (t < t0) {
            lift = H - 0.5 * G * t * t;
        } else if (e < 0.01) {
            since = t - t0;
            power = 1.0;
        } else {
            float u = 1.0 - (t - t0) * (1.0 - e) / (2.0 * t0 * e);
            if (u > 0.0) {
                float m = clamp(floor(log(u) / log(e)), 0.0, 60.0);
                float start = t0 * (1.0 + 2.0 * e * (1.0 - pow(e, m)) / (1.0 - e));
                float s = max(t - start, 0.0);
                lift = max(G * t0 * pow(e, m + 1.0) * s - 0.5 * G * s * s, 0.0);
                since = s;
                power = pow(e, m);
            }
        }
    }
    float sq = clamp(squash, 0.0, 0.6) * power * exp(-since / 0.07);
    float sy = 1.0 - sq;
    float sx = 1.0 + 0.5 * sq;
    float2 q = coord + float2(0.0, lift);
    float2 p = float2(size.x * 0.5 + (q.x - size.x * 0.5) / sx, size.y - (size.y - q.y) / sy);
    return image.eval(p);
}
"""

SPRING = """uniform shader image;
uniform float time;
uniform float4 screen;
uniform float startX;
uniform float startY;
uniform float frequency;
uniform float damping;
uniform float delay;

half4 main(float2 coord) {
    float t = max(time - delay, 0.0);
    float w = 6.2831853 * max(frequency, 0.01);
    float z = clamp(damping, 0.0, 0.99);
    float wd = w * sqrt(1.0 - z * z);
    float f = exp(-z * w * t) * (cos(wd * t) + (z * w / wd) * sin(wd * t));
    float2 start = float2(startX, startY) * screen.w;
    return image.eval(coord - start * f);
}
"""

PENDULUM = """uniform shader image;
uniform float2 size;
uniform float time;
uniform float swing;
uniform float frequency;
uniform float damping;
uniform float delay;
uniform float pivotX;
uniform float pivotY;

half4 main(float2 coord) {
    float t = max(time - delay, 0.0);
    float w = 6.2831853 * max(frequency, 0.01);
    float z = clamp(damping, 0.0, 0.99);
    float wd = w * sqrt(1.0 - z * z);
    float f = exp(-z * w * t) * (cos(wd * t) + (z * w / wd) * sin(wd * t));
    float th = radians(swing) * f;
    float c = cos(th);
    float s = sin(th);
    float2 pivot = float2(pivotX, pivotY) * size;
    float2 v = coord - pivot;
    return image.eval(pivot + float2(c * v.x + s * v.y, -s * v.x + c * v.y));
}
"""


def p(id, label, default, lo, hi, step, **extra):
    d = {"id": id, "label": label, "type": "float", "default": default, "min": lo, "max": hi, "step": step}
    d.update(extra)
    return d


EFFECTS = {
    "bouncedrop": {
        "id": "bouncedrop",
        "name": "Bounce Drop",
        "category": "move",
        "description": "Drops the layer from above under gravity and lets it bounce to a stop, squashing on every hit.",
        "params": [
            p("height", "Drop height", 0.5, 0, 2, 0.01, percent=True),
            p("gravity", "Gravity", 4, 0.5, 40, 0.1),
            p("bounce", "Bounciness", 0.55, 0, 0.95, 0.01, percent=True),
            p("squash", "Squash", 0.25, 0, 0.6, 0.01, percent=True),
            p("delay", "Delay", 0, 0, 60, 0.05, unit="s"),
        ],
        "sksl": BOUNCE,
    },
    "springsettle": {
        "id": "springsettle",
        "name": "Spring Settle",
        "category": "move",
        "description": "Starts the layer away from its place and lets a damped spring pull it in, overshooting and settling.",
        "params": [
            p("startX", "Start X", 0, -2, 2, 0.01, percent=True),
            p("startY", "Start Y", -0.4, -2, 2, 0.01, percent=True),
            p("frequency", "Frequency", 2, 0.2, 10, 0.1, unit="Hz"),
            p("damping", "Damping", 0.25, 0, 0.99, 0.01),
            p("delay", "Delay", 0, 0, 60, 0.05, unit="s"),
        ],
        "sksl": SPRING,
    },
    "pendulum": {
        "id": "pendulum",
        "name": "Pendulum",
        "category": "move",
        "description": "Swings the layer about a pivot point, with the swing dying away.",
        "params": [
            p("swing", "Start angle", 40, -180, 180, 1, unit="deg"),
            p("frequency", "Frequency", 0.8, 0.1, 6, 0.05, unit="Hz"),
            p("damping", "Damping", 0.12, 0, 0.99, 0.01),
            p("pivotX", "Pivot X", 0.5, -1, 2, 0.01, percent=True),
            p("pivotY", "Pivot Y", 0, -1, 2, 0.01, percent=True),
            p("delay", "Delay", 0, 0, 60, 0.05, unit="s"),
        ],
        "sksl": PENDULUM,
    },
}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for key, data in EFFECTS.items():
        (OUT / f"{key}.json").write_text(json.dumps(data, indent=1) + "\n", encoding="utf-8")
    (OUT / "index.json").write_text(json.dumps(list(EFFECTS), indent=1) + "\n", encoding="utf-8")
    print(f"wrote {len(EFFECTS)} effects to {OUT}")


if __name__ == "__main__":
    main()
