"""Builds the launcher's home plan from the PlanStudio scan (GLB) and a furnished layout (.planstudio.json).

Output (meters, plan x = scan X, plan y = scan Z): rooms (floor outlines), walls/joints (footprint rects),
doors (opening segments), windows (segments), fixtures + furniture (rotated footprints with heights).
"""
import json, math, sys
sys.path.insert(0, __file__.rsplit('/', 1)[0])
import glb

scan_path, layout_path, out_path = sys.argv[1:4]
g, b = glb.load(scan_path)
layout = json.load(open(layout_path))['data']
names = layout.get('roomNames', {})
edits = layout.get('scanEdits', {})
r2 = lambda v: round(v, 3)

def outline(tris):
    """Boundary loop of the up-facing triangles, as an ordered polygon."""
    top = [t for t in tris if all(p[1] > -0.05 for p in t)]
    key = lambda p: (round(p[0], 3), round(p[2], 3))
    count = {}
    for t in top:
        for a, c in ((t[0], t[1]), (t[1], t[2]), (t[2], t[0])):
            e = tuple(sorted((key(a), key(c))))
            count[e] = count.get(e, 0) + 1
    edges = [e for e, n in count.items() if n == 1]
    nxt = {}
    for a, c in edges:
        nxt.setdefault(a, []).append(c); nxt.setdefault(c, []).append(a)
    loops, seen = [], set()
    for start in list(nxt):
        if start in seen: continue
        loop, prev, cur = [start], None, start
        seen.add(start)
        while True:
            cand = [p for p in nxt[cur] if p != prev and (p not in seen or p == start)]
            if not cand: break
            prev, cur = cur, cand[0]
            if cur == start: break
            loop.append(cur); seen.add(cur)
        loops.append(loop)
    best = max(loops, key=lambda l: abs(sum(l[i][0]*l[(i+1)%len(l)][1] - l[(i+1)%len(l)][0]*l[i][1] for i in range(len(l)))))
    # Drop collinear points.
    simp = []
    for i, p in enumerate(best):
        a, c = best[i-1], best[(i+1) % len(best)]
        if abs((p[0]-a[0])*(c[1]-a[1]) - (p[1]-a[1])*(c[0]-a[0])) > 1e-4: simp.append(p)
    return [[r2(x), r2(y)] for x, y in simp]

def bounds(tris):
    xs = [p[0] for t in tris for p in t]; ys = [p[1] for t in tris for p in t]; zs = [p[2] for t in tris for p in t]
    return min(xs), max(xs), min(ys), max(ys), min(zs), max(zs)

def rect(cx, cz, w, d, rot):
    c, s = math.cos(rot), math.sin(rot)
    pts = []
    for lx, lz in ((-w/2, -d/2), (w/2, -d/2), (w/2, d/2), (-w/2, d/2)):
        pts.append([r2(cx + lx*c + lz*s), r2(cz - lx*s + lz*c)])
    return pts

rooms, walls, doors, fixtures = [], [], [], []
for name, m, mesh in glb.walk(g):
    if mesh is None: continue
    tris = glb.world_triangles(g, b, mesh, m)
    x0, x1, y0, y1, z0, z1 = bounds(tris)
    if name.startswith('Floor_'):
        rooms.append({'id': name, 'name': names.get(name, name), 'poly': outline(tris)})
    elif name.startswith(('Wall_', 'Joint_')):
        walls.append({'rect': [r2(x0), r2(z0), r2(x1), r2(z1)], 'h': r2(y1)})
    elif name.startswith('Door_'):
        doors.append([[r2(x0), r2(z0)], [r2(x1), r2(z1)]])
    elif name.startswith(('Ceiling_', 'Window_', 'Opening_')):
        continue
    else:
        e = edits.get(name, {})
        if e.get('deleted'): continue
        w, d = x1 - x0, z1 - z0
        cx, cz = (x0 + x1) / 2, (z0 + z1) / 2
        rot = 0.0
        if 'pos' in e:
            cx, cz = e['pos'][0], e['pos'][2]
            rot = e.get('rotY', 0.0)
        fixtures.append({'kind': name.rsplit('_', 1)[0], 'poly': rect(cx, cz, w, d, rot), 'h': r2(y1), 'base': r2(max(y0, 0))})

# Footprints (w along local X, d along local Z) and heights for the layout's catalog items.
SIZES = {
    'kenney:chairDesk': (0.55, 0.55, 0.9), 'kenney:fridge': (0.70, 0.70, 1.8), 'kenney:tv55': (1.24, 0.08, 0.72),
    'ikea:kivikChaise': (2.80, 1.63, 0.83), 'user:69002426-mini': (1.43, 1.99, 0.91), 'user:80562096-mini': (1.47, 0.42, 0.60),
}
LABELS = {'kenney:chairDesk': 'chair', 'kenney:fridge': 'fridge', 'kenney:tv55': 'tv', 'ikea:kivikChaise': 'sofa',
          'user:69002426-mini': 'bed', 'user:80562096-mini': 'tv bench'}
SKIP = ('kenney:laptop', 'proc:mirror', 'proc:curtain')
furniture, windows = [], []
for f in layout.get('furniture', []):
    cid, (x, y, z), rot = f['catalogId'], f['pos'], f.get('rotY', 0.0)
    if cid == 'proc:archwindow':
        w = f.get('w', 1.4); c, s = math.cos(rot), math.sin(rot)
        windows.append([[r2(x - w/2*c), r2(z + w/2*s)], [r2(x + w/2*c), r2(z - w/2*s)]])
        continue
    if cid in SKIP: continue
    if cid in SIZES:
        w, d, h = SIZES[cid]; label = LABELS[cid]
    elif cid.startswith('user:') and '×' in cid:
        label, dims = cid[5:].rsplit(' ', 1)
        a, bb = (float(v) / 100 for v in dims.split('×'))
        w, d = a, bb
        h = 0.75 if 'Masa' in label else (0.85 if 'Makine' in label else (0.5 if 'Alçak' in label else 2.0 if 'Boy' in label else 0.9))
    else:
        continue
    base = max(y, 0) if cid == 'kenney:tv55' else 0
    furniture.append({'label': label, 'poly': rect(x, z, w, d, rot), 'h': r2(base + h), 'base': r2(base)})

out = {'source': 'PlanStudio scan + layout', 'units': 'm',
       'rooms': rooms, 'walls': walls, 'doors': doors, 'windows': windows, 'fixtures': fixtures, 'furniture': furniture}
json.dump(out, open(out_path, 'w'), ensure_ascii=False, separators=(',', ':'))
print('rooms', [(r['name'], len(r['poly'])) for r in rooms])
print('walls', len(walls), 'doors', len(doors), 'windows', len(windows), 'fixtures', len(fixtures), 'furniture', len(furniture))
print([f['label'] for f in furniture]); print(sorted({f['kind'] for f in fixtures}))
