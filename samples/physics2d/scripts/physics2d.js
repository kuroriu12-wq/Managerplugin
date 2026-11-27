const MAX_BODIES = 40;
const MAX_KEYS = 20000;
const ITERATIONS = 6;
const SLOP = 0.05;
const CORRECTION = 0.4;
const SLEEP_TIME = 0.5;
const STEPS = { fast: 1 / 60, normal: 1 / 120, high: 1 / 240 };

function polygon(body) {
  const c = Math.cos(body.angle);
  const s = Math.sin(body.angle);
  const corners = [[-body.hw, -body.hh], [body.hw, -body.hh], [body.hw, body.hh], [-body.hw, body.hh]];
  body.verts = corners.map(([lx, ly]) => ({ x: body.x + lx * c - ly * s, y: body.y + lx * s + ly * c }));
  body.norms = body.verts.map((a, i) => {
    const b = body.verts[(i + 1) % 4];
    const ex = b.x - a.x;
    const ey = b.y - a.y;
    const length = Math.hypot(ex, ey);
    return { x: ey / length, y: -ex / length };
  });
}

function maxSeparation(a, b) {
  let best = -Infinity;
  let index = 0;
  for (let i = 0; i < 4; i++) {
    const n = a.norms[i];
    const v = a.verts[i];
    let min = Infinity;
    for (const w of b.verts) min = Math.min(min, (w.x - v.x) * n.x + (w.y - v.y) * n.y);
    if (min > best) {
      best = min;
      index = i;
    }
  }
  return { sep: best, index };
}

function clipSegment(p, q, nx, ny, offset) {
  const dp = p.x * nx + p.y * ny - offset;
  const dq = q.x * nx + q.y * ny - offset;
  const out = [];
  if (dp <= 0) out.push(p);
  if (dq <= 0) out.push(q);
  if (dp * dq < 0) {
    const t = dp / (dp - dq);
    out.push({ x: p.x + (q.x - p.x) * t, y: p.y + (q.y - p.y) * t });
  }
  return out;
}

function collideBoxes(a, b) {
  const sa = maxSeparation(a, b);
  if (sa.sep > 0) return [];
  const sb = maxSeparation(b, a);
  if (sb.sep > 0) return [];
  const flip = sb.sep > sa.sep + 1e-4;
  const ref = flip ? b : a;
  const inc = flip ? a : b;
  const edge = flip ? sb.index : sa.index;
  const rn = ref.norms[edge];
  let ie = 0;
  let lowest = Infinity;
  for (let i = 0; i < 4; i++) {
    const d = inc.norms[i].x * rn.x + inc.norms[i].y * rn.y;
    if (d < lowest) {
      lowest = d;
      ie = i;
    }
  }
  const v1 = ref.verts[edge];
  const v2 = ref.verts[(edge + 1) % 4];
  const tl = Math.hypot(v2.x - v1.x, v2.y - v1.y);
  const tx = (v2.x - v1.x) / tl;
  const ty = (v2.y - v1.y) / tl;
  let points = clipSegment(inc.verts[ie], inc.verts[(ie + 1) % 4], -tx, -ty, -(v1.x * tx + v1.y * ty));
  if (points.length < 2) return [];
  points = clipSegment(points[0], points[1], tx, ty, v2.x * tx + v2.y * ty);
  const sign = flip ? -1 : 1;
  const out = [];
  for (const p of points) {
    const separation = (p.x - v1.x) * rn.x + (p.y - v1.y) * rn.y;
    if (separation <= 0) out.push({ a, b, nx: rn.x * sign, ny: rn.y * sign, px: p.x, py: p.y, pen: -separation });
  }
  return out;
}

function collideCircleBox(circle, box) {
  let best = -Infinity;
  let edge = 0;
  for (let i = 0; i < 4; i++) {
    const sep = (circle.x - box.verts[i].x) * box.norms[i].x + (circle.y - box.verts[i].y) * box.norms[i].y;
    if (sep > circle.r) return null;
    if (sep > best) {
      best = sep;
      edge = i;
    }
  }
  const v1 = box.verts[edge];
  const v2 = box.verts[(edge + 1) % 4];
  const n = box.norms[edge];
  const u1 = (circle.x - v1.x) * (v2.x - v1.x) + (circle.y - v1.y) * (v2.y - v1.y);
  const u2 = (circle.x - v2.x) * (v1.x - v2.x) + (circle.y - v2.y) * (v1.y - v2.y);
  if (best >= 1e-9 && (u1 <= 0 || u2 <= 0)) {
    const v = u1 <= 0 ? v1 : v2;
    const dx = circle.x - v.x;
    const dy = circle.y - v.y;
    const dist = Math.hypot(dx, dy);
    if (dist > circle.r) return null;
    return { nx: dx / dist, ny: dy / dist, px: v.x, py: v.y, pen: circle.r - dist };
  }
  return { nx: n.x, ny: n.y, px: circle.x - n.x * circle.r, py: circle.y - n.y * circle.r, pen: circle.r - best };
}

function collide(a, b) {
  if (a.circle && b.circle) {
    const dx = b.x - a.x;
    const dy = b.y - a.y;
    const dist = Math.hypot(dx, dy);
    if (dist >= a.r + b.r) return [];
    const nx = dist > 0 ? dx / dist : 0;
    const ny = dist > 0 ? dy / dist : 1;
    return [{ a, b, nx, ny, px: a.x + nx * a.r, py: a.y + ny * a.r, pen: a.r + b.r - dist }];
  }
  if (!a.circle && !b.circle) return collideBoxes(a, b);
  const hit = a.circle ? collideCircleBox(a, b) : collideCircleBox(b, a);
  if (!hit) return [];
  const sign = a.circle ? -1 : 1;
  return [{ a, b, nx: hit.nx * sign, ny: hit.ny * sign, px: hit.px, py: hit.py, pen: hit.pen }];
}

function wallContacts(body, walls) {
  const out = [];
  for (const wall of walls) {
    if (body.circle) {
      const pen = body.x * wall.nx + body.y * wall.ny + body.r - wall.d;
      if (pen > 0) out.push({ a: body, b: null, nx: wall.nx, ny: wall.ny, px: body.x + wall.nx * body.r, py: body.y + wall.ny * body.r, pen });
    } else {
      for (const v of body.verts) {
        const pen = v.x * wall.nx + v.y * wall.ny - wall.d;
        if (pen > 0) out.push({ a: body, b: null, nx: wall.nx, ny: wall.ny, px: v.x, py: v.y, pen });
      }
    }
  }
  return out;
}

function prepare(c, restitution, threshold) {
  const a = c.a;
  const b = c.b;
  c.iMa = a.sleeping ? 0 : a.invMass;
  c.iIa = a.sleeping ? 0 : a.invInertia;
  c.iMb = b && !b.sleeping ? b.invMass : 0;
  c.iIb = b && !b.sleeping ? b.invInertia : 0;
  c.rax = c.px - a.x;
  c.ray = c.py - a.y;
  c.rbx = b ? c.px - b.x : 0;
  c.rby = b ? c.py - b.y : 0;
  c.tx = -c.ny;
  c.ty = c.nx;
  const rnA = c.rax * c.ny - c.ray * c.nx;
  const rnB = c.rbx * c.ny - c.rby * c.nx;
  const rtA = c.rax * c.ty - c.ray * c.tx;
  const rtB = c.rbx * c.ty - c.rby * c.tx;
  c.kn = c.iMa + c.iMb + rnA * rnA * c.iIa + rnB * rnB * c.iIb;
  c.kt = c.iMa + c.iMb + rtA * rtA * c.iIa + rtB * rtB * c.iIb;
  c.accN = 0;
  c.accT = 0;
  const vn = relative(c, c.nx, c.ny);
  c.bounce = vn < -threshold ? -restitution * vn : 0;
}

function relative(c, dx, dy) {
  const a = c.a;
  const b = c.b;
  const ax = a.vx - a.w * c.ray;
  const ay = a.vy + a.w * c.rax;
  const bx = b ? b.vx - b.w * c.rby : 0;
  const by = b ? b.vy + b.w * c.rbx : 0;
  return (bx - ax) * dx + (by - ay) * dy;
}

function push(c, jx, jy) {
  const a = c.a;
  const b = c.b;
  a.vx -= c.iMa * jx;
  a.vy -= c.iMa * jy;
  a.w -= c.iIa * (c.rax * jy - c.ray * jx);
  if (b) {
    b.vx += c.iMb * jx;
    b.vy += c.iMb * jy;
    b.w += c.iIb * (c.rbx * jy - c.rby * jx);
  }
}

function solve(c, friction) {
  let dj = (c.bounce - relative(c, c.nx, c.ny)) / c.kn;
  const accN = Math.max(c.accN + dj, 0);
  dj = accN - c.accN;
  c.accN = accN;
  push(c, dj * c.nx, dj * c.ny);
  const limit = friction * c.accN;
  let jt = -relative(c, c.tx, c.ty) / c.kt;
  const accT = Math.max(-limit, Math.min(limit, c.accT + jt));
  jt = accT - c.accT;
  c.accT = accT;
  push(c, jt * c.tx, jt * c.ty);
}

function makeBody(layer, index, setup) {
  const size = layer.size();
  const scale = layer.scale(0);
  const at = layer.position(0);
  const later = setup.inherit ? layer.position(setup.probe) : at;
  const turn = setup.inherit ? -(layer.rotation(setup.probe) - layer.rotation(0)) * Math.PI / 180 / setup.probe : 0;
  const body = {
    layer,
    x: at.x,
    y: at.y,
    angle: -layer.rotation(0) * Math.PI / 180,
    vx: setup.pushX + (later.x - at.x) / setup.probe,
    vy: setup.pushY + (later.y - at.y) / setup.probe,
    w: setup.spin + turn,
    pinned: setup.pin && index === 0,
    sleeping: false,
    idle: 0,
    touching: false,
  };
  let area;
  body.ox = 0;
  body.oy = 0;
  if (size === null) {
    const box = layer.bounds();
    if (box === null) {
      body.circle = true;
      body.r = setup.fallbackRadius;
      area = Math.PI * body.r * body.r;
      body.note = "no size known, treated as a circle of radius " + Math.round(body.r);
    } else {
      const c = Math.abs(Math.cos(body.angle));
      const s = Math.abs(Math.sin(body.angle));
      const spanX = Math.max(box.right - box.left, 1);
      const spanY = Math.max(box.bottom - box.top, 1);
      const det = c * c - s * s;
      let width = Math.abs(det) < 0.05 ? (spanX + spanY) / (2 * (c + s)) : (spanX * c - spanY * s) / det;
      let height = Math.abs(det) < 0.05 ? width : (spanY * c - spanX * s) / det;
      if (!(width >= 1 && height >= 1)) {
        width = spanX;
        height = spanY;
      }
      body.circle = false;
      body.hw = width / 2;
      body.hh = height / 2;
      area = width * height;
      const dx = (box.left + box.right) / 2 - at.x;
      const dy = (box.top + box.bottom) / 2 - at.y;
      const cos = Math.cos(body.angle);
      const sin = Math.sin(body.angle);
      body.ox = dx * cos + dy * sin;
      body.oy = -dx * sin + dy * cos;
      body.x = at.x + dx;
      body.y = at.y + dy;
      body.note = "box " + Math.round(width) + " x " + Math.round(height) + ", measured from its outline";
    }
  } else {
    const width = Math.max(size.width * Math.abs(scale.x), 1);
    const height = Math.max(size.height * Math.abs(scale.y), 1);
    body.circle = layer.shape === ".circle" && Math.abs(width - height) <= 0.5;
    if (body.circle) {
      body.r = width / 2;
      area = Math.PI * body.r * body.r;
      body.note = "circle of radius " + Math.round(body.r);
    } else {
      body.hw = width / 2;
      body.hh = height / 2;
      area = width * height;
      body.note = "box " + Math.round(width) + " x " + Math.round(height);
    }
  }
  body.bound = body.circle ? body.r : Math.hypot(body.hw, body.hh);
  const inertia = body.circle ? 0.5 * area * body.r * body.r : area * (4 * body.hw * body.hw + 4 * body.hh * body.hh) / 12;
  body.invMass = body.pinned ? 0 : 1 / area;
  body.invInertia = body.pinned ? 0 : 1 / inertia;
  if (body.pinned) {
    body.vx = 0;
    body.vy = 0;
    body.w = 0;
  }
  if (!body.circle) polygon(body);
  return body;
}

function step(bodies, walls, dt, world) {
  for (const body of bodies) {
    body.touching = false;
    if (body.pinned || body.sleeping) continue;
    body.vy += world.gravity * dt;
  }
  for (const body of bodies) if (!body.circle) polygon(body);
  const contacts = [];
  const order = bodies.slice().sort((p, q) => p.x - p.bound - (q.x - q.bound));
  for (let i = 0; i < order.length; i++) {
    const a = order[i];
    for (let j = i + 1; j < order.length; j++) {
      const b = order[j];
      if (b.x - b.bound > a.x + a.bound) break;
      if (Math.abs(a.y - b.y) > a.bound + b.bound) continue;
      if ((a.pinned || a.sleeping) && (b.pinned || b.sleeping)) continue;
      contacts.push(...collide(a, b));
    }
    if (!a.pinned && !a.sleeping) contacts.push(...wallContacts(a, walls));
  }
  for (const c of contacts) {
    c.a.touching = true;
    if (c.b) c.b.touching = true;
    if (c.b && (c.a.sleeping !== c.b.sleeping)) {
      const sleeper = c.a.sleeping ? c.a : c.b;
      const mover = c.a.sleeping ? c.b : c.a;
      if (Math.hypot(mover.vx, mover.vy) > world.wakeSpeed) {
        sleeper.sleeping = false;
        sleeper.idle = 0;
      }
    }
  }
  for (const c of contacts) prepare(c, world.restitution, world.bounceThreshold);
  const active = contacts.filter((c) => c.kn > 0);
  for (let k = 0; k < ITERATIONS; k++) for (const c of active) solve(c, world.friction);
  for (const c of active) {
    const share = c.iMa + c.iMb;
    const move = Math.max(c.pen - SLOP, 0) * CORRECTION / share;
    c.a.x -= c.nx * move * c.iMa;
    c.a.y -= c.ny * move * c.iMa;
    if (c.b) {
      c.b.x += c.nx * move * c.iMb;
      c.b.y += c.ny * move * c.iMb;
    }
  }
  for (const body of bodies) {
    if (body.pinned || body.sleeping) continue;
    body.x += body.vx * dt;
    body.y += body.vy * dt;
    body.angle += body.w * dt;
    const speed = Math.hypot(body.vx, body.vy) + Math.abs(body.w) * body.bound;
    body.idle = body.touching && speed < world.sleepSpeed ? body.idle + dt : 0;
    if (body.idle >= SLEEP_TIME) {
      body.sleeping = true;
      body.vx = 0;
      body.vy = 0;
      body.w = 0;
    }
  }
}

function anchor(body) {
  const c = Math.cos(body.angle);
  const s = Math.sin(body.angle);
  return { x: body.x - (body.ox * c - body.oy * s), y: body.y - (body.ox * s + body.oy * c) };
}

function frameTimes(duration, fps, every) {
  const times = [];
  const spacing = every / fps;
  for (let k = 0; k * spacing < duration - 1e-6; k++) times.push(k * spacing);
  times.push(duration);
  return times;
}

function advance(bodies, walls, from, to, quality, world) {
  const span = to - from;
  const count = Math.max(1, Math.ceil(span / quality - 1e-9));
  for (let i = 0; i < count; i++) step(bodies, walls, span / count, world);
}

defineScript({
  id: "physics2d",
  name: "Physics 2D",
  description: "Select two or more layers. They fall, bounce and collide, and the result is baked into keyframes.",
  inputs: [
    { id: "gravity", label: "Gravity (screen heights per second squared)", type: "number", default: 3, min: 0, max: 40 },
    { id: "restitution", label: "Bounciness (0 to 1)", type: "number", default: 0.5, min: 0, max: 1 },
    { id: "friction", label: "Friction (0 to 1)", type: "number", default: 0.4, min: 0, max: 1 },
    { id: "floor", label: "Floor", type: "switch", default: true },
    { id: "left", label: "Left wall", type: "switch", default: true },
    { id: "right", label: "Right wall", type: "switch", default: true },
    { id: "ceiling", label: "Ceiling", type: "switch", default: false },
    { id: "pin", label: "Pin the first selected layer", type: "switch", default: false },
    { id: "pushX", label: "Push right (screen heights per second)", type: "number", default: 0, min: -20, max: 20 },
    { id: "pushY", label: "Push down (screen heights per second)", type: "number", default: 0, min: -20, max: 20 },
    { id: "spin", label: "Spin (degrees per second, clockwise)", type: "number", default: 0, min: -3600, max: 3600 },
    { id: "inherit", label: "Start with the layer's existing motion", type: "switch", default: false },
    { id: "fallbackRadius", label: "Radius for layers without a size (screen height fraction)", type: "number", default: 0.05, min: 0.005, max: 0.5 },
    { id: "quality", label: "Quality", type: "choice", default: "normal", options: ["fast", "normal", "high"] },
    { id: "bake", label: "Keyframe every N frames", type: "number", default: 1, min: 1, max: 30 }
  ],
  run(ctx) {
    const layers = ctx.selection();
    if (layers.length === 0) throw new Error("Select at least one layer first");
    if (layers.length > MAX_BODIES) throw new Error("Physics 2D handles at most " + MAX_BODIES + " layers; " + layers.length + " are selected");
    for (const layer of layers) if (layer.locked) throw new Error("The layer '" + (layer.name || layer.kind) + "' is locked");
    const { width, height, fps } = ctx.scene;
    const input = ctx.input;
    const duration = Math.min(...layers.map((layer) => layer.finish - layer.start));
    if (!(duration > 0)) throw new Error("A selected layer has no length");
    const times = frameTimes(duration, fps, Math.round(input.bake));
    const keys = layers.length * 2 * times.length;
    if (keys > MAX_KEYS) {
      throw new Error("That needs " + keys + " keyframes (the limit is " + MAX_KEYS + "). Use fewer layers, a larger keyframe step or a shorter layer");
    }
    const quality = STEPS[input.quality];
    const gravity = input.gravity * height;
    const bounceThreshold = 2 * gravity * quality + 1e-6;
    const world = {
      gravity,
      restitution: input.restitution,
      friction: input.friction,
      bounceThreshold,
      sleepSpeed: Math.max(0.02 * height, bounceThreshold),
      wakeSpeed: 0.1 * height,
    };
    world.wakeSpeed = Math.max(world.wakeSpeed, 3 * world.sleepSpeed);
    const setup = {
      pushX: input.pushX * height,
      pushY: input.pushY * height,
      spin: input.spin * Math.PI / 180,
      pin: input.pin,
      inherit: input.inherit,
      probe: Math.max(1, Math.round(1000 / fps)) / 1000,
      fallbackRadius: input.fallbackRadius * height,
    };
    const walls = [];
    if (input.floor) walls.push({ nx: 0, ny: 1, d: height });
    if (input.left) walls.push({ nx: -1, ny: 0, d: 0 });
    if (input.right) walls.push({ nx: 1, ny: 0, d: width });
    if (input.ceiling) walls.push({ nx: 0, ny: -1, d: 0 });
    const bodies = layers.map((layer, index) => makeBody(layer, index, setup));
    const track = bodies.map((body) => {
      const at = anchor(body);
      return { x: [at.x], y: [at.y], angle: [-body.angle * 180 / Math.PI] };
    });
    for (let i = 1; i < times.length; i++) {
      advance(bodies, walls, times[i - 1], times[i], quality, world);
      bodies.forEach((body, n) => {
        const at = anchor(body);
        track[n].x.push(at.x);
        track[n].y.push(at.y);
        track[n].angle.push(-body.angle * 180 / Math.PI);
      });
    }
    bodies.forEach((body, n) => {
      const points = track[n].x.map((x, i) => ({ x, y: track[n].y[i] }));
      body.layer.setKeys("position", times, points);
      body.layer.setKeys("rotation", times, track[n].angle);
    });
    bodies.forEach((body) => ctx.log((body.layer.name || body.layer.kind) + ": " + body.note));
    ctx.log("Baked " + bodies.length + " layer(s), " + times.length + " keyframes each, over " + duration.toFixed(2) + " s");
  }
});
