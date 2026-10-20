"""Writes the sample apartment that ships with the app (app/src/main/res/raw/home_plan.json).

A made-up 1+1 flat in the same format build_home_plan.py produces from a PlanStudio scan, so the lights
page has something to show out of the box. Units are meters; plan x is right, plan y is "down" on a
top view. Run: python3 tools/make_sample_plan.py
"""
import json, math, os

def rect(cx, cy, w, d, rot=0.0):
    c, s = math.cos(rot), math.sin(rot)
    return [[round(cx + lx * c + ly * s, 3), round(cy - lx * s + ly * c, 3)] for lx, ly in ((-w/2, -d/2), (w/2, -d/2), (w/2, d/2), (-w/2, d/2))]

def box(x0, y0, x1, y1):
    return [[x0, y0], [x1, y0], [x1, y1], [x0, y1]]

rooms = [
    {'id': 'living', 'name': 'living room', 'poly': box(-4.0, -3.5, 1.0, 0.0)},
    {'id': 'bedroom', 'name': 'bedroom', 'poly': box(1.0, -3.5, 4.6, 0.0)},
    {'id': 'kitchen', 'name': 'kitchen', 'poly': box(-4.0, 0.0, -1.5, 3.0)},
    {'id': 'bathroom', 'name': 'bathroom', 'poly': box(-1.5, 0.0, 0.5, 2.0)},
    {'id': 'entry', 'name': 'entry', 'poly': box(0.5, 0.0, 2.0, 1.6)},
]

# Thin wall footprints along every room edge (used to tell which way furniture faces).
walls = []
for r in rooms:
    p = r['poly']
    for (ax, ay), (bx, by) in zip(p, p[1:] + p[:1]):
        walls.append({'rect': [min(ax, bx) - 0.05, min(ay, by) - 0.05, max(ax, bx) + 0.05, max(ay, by) + 0.05], 'h': 2.6})

doors = [
    [[-3.2, 0.0], [-2.4, 0.0]],   # living ↔ kitchen
    [[0.55, 0.0], [0.95, 0.0]],   # living ↔ entry
    [[1.2, 0.0], [1.9, 0.0]],     # entry ↔ bedroom
    [[0.5, 0.6], [0.5, 1.3]],     # entry ↔ bathroom
]
windows = [
    [[-3.4, -3.5], [-1.8, -3.5]],
    [[-1.0, -3.5], [0.4, -3.5]],
    [[2.0, -3.5], [3.4, -3.5]],
    [[-4.0, 0.8], [-4.0, 2.4]],
]

def item(key, label, poly, h, base=0.0):
    return {key: label, 'poly': poly, 'h': h, 'base': base}

fixtures = [
    item('kind', 'storage_cabinet_mid', rect(-3.7, 0.55, 0.6, 1.0), 0.9),
    item('kind', 'oven', rect(-3.7, 1.35, 0.6, 0.6), 0.9),
    item('kind', 'storage_cabinet_mid', rect(-3.7, 2.05, 0.6, 0.8), 0.9),
    item('kind', 'sink', rect(-3.7, 2.05, 0.5, 0.6), 0.92, 0.75),
    item('kind', 'toilet', rect(-1.2, 1.55, 0.42, 0.65), 0.75),
    item('kind', 'storage_cabinet_mid', rect(0.15, 0.3, 0.6, 0.45), 0.8),
    item('kind', 'sink', rect(0.15, 0.3, 0.45, 0.35), 0.85, 0.7),
    item('kind', 'storage_cabinet_low1', rect(1.8, 1.15, 0.35, 0.8), 1.0),
]
furniture = [
    item('label', 'sofa', rect(-2.0, -2.95, 2.6, 0.95), 0.83),
    item('label', 'tv bench', rect(-2.0, -0.28, 1.6, 0.42), 0.55),
    item('label', 'tv', rect(-2.0, -0.3, 1.2, 0.08), 1.2, 0.55),
    item('label', 'table', rect(0.0, -2.0, 1.2, 0.8), 0.75),
    item('label', 'chair', rect(0.0, -1.3, 0.5, 0.5), 0.9),
    item('label', 'bed', rect(3.0, -2.45, 1.6, 2.0), 0.9),
    item('label', 'wardrobe', rect(4.25, -0.8, 0.6, 1.2), 2.0),
    item('label', 'fridge', rect(-3.65, 2.65, 0.7, 0.6), 1.8),
    item('label', 'washer', rect(-0.25, 1.65, 0.6, 0.6), 0.85),
]

out = {'source': 'sample apartment (tools/make_sample_plan.py)', 'units': 'm', 'rooms': rooms, 'walls': walls,
       'doors': doors, 'windows': windows, 'fixtures': fixtures, 'furniture': furniture}
path = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'res', 'raw', 'home_plan.json')
with open(path, 'w') as f:
    json.dump(out, f, ensure_ascii=False, separators=(',', ':'))
print('wrote', os.path.normpath(path))
