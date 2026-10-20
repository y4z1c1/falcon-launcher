"""Minimal GLB reader: node names, world transforms, and mesh POSITION data (no dependencies)."""
import json, struct, math, sys

def load(path):
    data = open(path, 'rb').read()
    magic, ver, length = struct.unpack_from('<III', data, 0)
    off = 12; gltf = None; binc = b''
    while off < length:
        clen, ctype = struct.unpack_from('<II', data, off); off += 8
        chunk = data[off:off+clen]; off += clen
        if ctype == 0x4E4F534A: gltf = json.loads(chunk)
        elif ctype == 0x004E4942: binc = chunk
    return gltf, binc

def matmul(a, b):
    return [sum(a[r*4+k]*b[k*4+c] for k in range(4)) for r in range(4) for c in range(4)]

def node_matrix(n):
    if 'matrix' in n:
        m = n['matrix']  # column-major
        return [m[c*4+r] for r in range(4) for c in range(4)]
    t = n.get('translation', [0,0,0]); r = n.get('rotation', [0,0,0,1]); s = n.get('scale', [1,1,1])
    x,y,z,w = r
    R = [1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w),
         2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w),
         2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]
    return [R[0]*s[0], R[1]*s[1], R[2]*s[2], t[0],
            R[3]*s[0], R[4]*s[1], R[5]*s[2], t[1],
            R[6]*s[0], R[7]*s[1], R[8]*s[2], t[2],
            0,0,0,1]

def positions(gltf, binc, acc_index):
    acc = gltf['accessors'][acc_index]; bv = gltf['bufferViews'][acc['bufferView']]
    base = bv.get('byteOffset', 0) + acc.get('byteOffset', 0); stride = bv.get('byteStride', 12)
    return [struct.unpack_from('<fff', binc, base + i*stride) for i in range(acc['count'])]

def indices(gltf, binc, acc_index):
    acc = gltf['accessors'][acc_index]; bv = gltf['bufferViews'][acc['bufferView']]
    base = bv.get('byteOffset', 0) + acc.get('byteOffset', 0)
    fmt = {5121: 'B', 5123: 'H', 5125: 'I'}[acc['componentType']]; size = struct.calcsize(fmt)
    return [struct.unpack_from('<'+fmt, binc, base + i*size)[0] for i in range(acc['count'])]

def walk(gltf):
    """Yields (name, world_matrix, mesh_index or None) for every node."""
    scene = gltf['scenes'][gltf.get('scene', 0)]
    ident = [1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1]
    stack = [(i, ident) for i in scene['nodes']]
    while stack:
        i, parent = stack.pop()
        n = gltf['nodes'][i]; m = matmul(parent, node_matrix(n))
        yield n.get('name', f'node{i}'), m, n.get('mesh')
        for c in n.get('children', []): stack.append((c, m))

def world_triangles(gltf, binc, mesh_index, m):
    tris = []
    for prim in gltf['meshes'][mesh_index]['primitives']:
        pos = positions(gltf, binc, prim['attributes']['POSITION'])
        pts = [(m[0]*x+m[1]*y+m[2]*z+m[3], m[4]*x+m[5]*y+m[6]*z+m[7], m[8]*x+m[9]*y+m[10]*z+m[11]) for x,y,z in pos]
        idx = indices(gltf, binc, prim['indices']) if 'indices' in prim else list(range(len(pts)))
        tris += [(pts[idx[k]], pts[idx[k+1]], pts[idx[k+2]]) for k in range(0, len(idx)-2, 3)]
    return tris

if __name__ == '__main__':
    g, b = load(sys.argv[1])
    for name, m, mesh in walk(g):
        if mesh is None: continue
        tris = world_triangles(g, b, mesh, m)
        xs = [p[0] for t in tris for p in t]; ys = [p[1] for t in tris for p in t]; zs = [p[2] for t in tris for p in t]
        print(f'{name:28s} tris={len(tris):5d} x[{min(xs):6.2f},{max(xs):6.2f}] y[{min(ys):5.2f},{max(ys):5.2f}] z[{min(zs):6.2f},{max(zs):6.2f}]')
