defineScript({
  id: "nudge",
  name: "Nudge",
  inputs: [
    { id: "dx", label: "Move right (pixels)", type: "number", default: 100, min: -5000, max: 5000 },
    { id: "dy", label: "Move down (pixels)", type: "number", default: 0, min: -5000, max: 5000 },
    { id: "seconds", label: "Duration (seconds)", type: "number", default: 1, min: 0.1, max: 60 }
  ],
  run(ctx) {
    const layers = ctx.selection();
    if (layers.length === 0) throw new Error("Select at least one layer first");
    for (const layer of layers) {
      const length = layer.finish - layer.start;
      if (length <= 0) throw new Error((layer.name || layer.kind) + " has no length");
      const end = Math.min(ctx.input.seconds, length);
      const from = layer.position(0);
      const to = { x: from.x + ctx.input.dx, y: from.y + ctx.input.dy };
      layer.setKeys("position", [0, end], [from, to]);
      ctx.log((layer.name || layer.kind) + ": moved by " + ctx.input.dx + ", " + ctx.input.dy);
    }
  }
});
