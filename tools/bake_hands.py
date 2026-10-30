"""Bakes the hand assets of Sign Hands (run with `python -I tools/bake_hands.py`, numpy only).

Inputs
  spatial-app-factory/apps/sign-hands/handmodel/webxr-generic-hand/{right,left}.glb   (MIT, (c) 2019 Amazon)
  spatial-app-factory/apps/sign-hands/design-package.json                           (letters, tips, scored list)

Outputs (app/src/main/assets)
  hands/hand_right.glb, hands/hand_left.glb
      The WebXR generic-hand, re-rooted into the app's hand frame (wrist at the origin, fingers +Y, back of the
      hand +Z, thumb at -X for the right hand / +X for the left; middle-finger knuckle 8.81 cm from the wrist),
      its one mesh split into six primitives (thumb, index, middle, ring, pinky, palm: each vertex goes to the bone
      with the largest weight) so that each finger has its own material slot, and one animation "poses":
      letter k (0 = OPEN, 1..26 = A..Z) occupies [2k, 2k + 2) s: open hand -> letter over 0..1.2 s, then held.
      The app scrubs this clip (setTime + pause); orientation, J / Z motion and placement are the entity's job.
  asl_letters.json
      Per letter: the ideal joint positions (25 joints, hand frame, right hand) for the correct pose and for
      one finger / the thumb deliberately off (debug fakeHand and the matcher tests), orientation, motion, tips.

The pose solver is a port of the sample's hand.preview.js (same codes, same numbers): design package
letters.authoring.
"""
import json
import math
import struct
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent
FACTORY_APP = Path("C:/Users/Admin/Downloads/spatial-app-factory/apps/sign-hands")
SRC = FACTORY_APP / "handmodel" / "webxr-generic-hand"
ASSETS = PROJECT / "app" / "src" / "main" / "assets"

D2R = math.pi / 180
CODES = {"E": [0, 0, 0], "C": [35, 45, 30], "F": [85, 100, 70], "H": [0, 95, 75], "T": [50, 62, 40], "K": [45, 0, 0]}
FINGERS = ["index", "middle", "ring", "pinky"]
BASE = {"index": [-0.024, 0.085], "middle": [-0.004, 0.088], "ring": [0.015, 0.083], "pinky": [0.031, 0.075]}
LEN = {"index": [0.040, 0.024, 0.020], "middle": [0.045, 0.028, 0.021], "ring": [0.042, 0.027, 0.021], "pinky": [0.034, 0.019, 0.018]}
CMC = [-0.022, 0.022, -0.004]
THUMB = {
    "side": [-0.031, 0.086, -0.012], "out": [-0.092, 0.062, -0.004], "tuck": [0.004, 0.056, -0.024],
    "across": [0.012, 0.072, -0.046], "C": [-0.042, 0.078, -0.064], "between": [-0.014, 0.088, -0.030],
    "par": [-0.036, 0.104, -0.014], "underM": [0.024, 0.062, -0.026], "underN": [0.010, 0.064, -0.028],
    "underT": [-0.012, 0.070, -0.032], "open": [-0.080, 0.070, -0.010], "betweenK": [-0.014, 0.113, -0.030],
}
ORIENT = {"front": [0, 0, 0], "side": [0, 90, 0], "selfLeft": [180, 0, 90], "down": [180, 0, 0], "downK": [150, 0, 0]}


def f(code, extra=None):
    a = list(CODES[code])
    if extra:
        for i, v in enumerate(extra):
            a[i] += v
    return a


L = {
    "OPEN": {"f": [f("E"), f("E"), f("E"), f("E")], "spread": [10, 8, 10], "thumb": "open", "o": "front"},
    "A": {"f": [f("F"), f("F"), f("F"), f("F")], "spread": [0, 0, 0], "thumb": "side", "o": "front"},
    "B": {"f": [f("E"), f("E"), f("E"), f("E")], "spread": [0, 0, 0], "thumb": "tuck", "o": "front"},
    "C": {"f": [f("C"), f("C"), f("C"), f("C")], "spread": [0, 0, 0], "thumb": "C", "o": "side"},
    "D": {"f": [f("E"), f("T"), f("T"), f("T")], "spread": [0, 0, 0], "thumb": "touch:middle", "o": "front"},
    "E": {"f": [[28, 95, 78]] * 4, "spread": [0, 0, 0], "thumb": "tuck", "o": "front"},
    "F": {"f": [f("T", [5, 5, 5]), f("E"), f("E"), f("E")], "spread": [4, 10, 10], "thumb": "touch:index", "o": "front"},
    "G": {"f": [f("E"), f("F"), f("F"), f("F")], "spread": [0, 0, 0], "thumb": "par", "o": "selfLeft"},
    "H": {"f": [f("E"), f("E"), f("F"), f("F")], "spread": [0, 0, 0], "thumb": "across", "o": "selfLeft"},
    "I": {"f": [f("F"), f("F"), f("F"), f("E")], "spread": [0, 0, 6], "thumb": "across", "o": "front"},
    "J": {"f": [f("F"), f("F"), f("F"), f("E")], "spread": [0, 0, 6], "thumb": "across", "o": "front", "motion": "J"},
    "K": {"f": [f("E"), f("K"), f("F"), f("F")], "spread": [6, 0, 0], "thumb": "betweenK", "o": "front"},
    "L": {"f": [f("E"), f("F"), f("F"), f("F")], "spread": [0, 0, 0], "thumb": "out", "o": "front"},
    "M": {"f": [f("F", [-10]), f("F", [-10]), f("F", [-10]), f("F")], "spread": [0, 0, 0], "thumb": "underM", "o": "front"},
    "N": {"f": [f("F", [-10]), f("F", [-10]), f("F"), f("F")], "spread": [0, 0, 0], "thumb": "underN", "o": "front"},
    "O": {"f": [[48, 60, 38]] * 4, "spread": [0, 0, 0], "thumb": "touch:index", "o": "side"},
    "P": {"f": [f("E"), f("K"), f("F"), f("F")], "spread": [6, 0, 0], "thumb": "betweenK", "o": "downK"},
    "Q": {"f": [f("E"), f("F"), f("F"), f("F")], "spread": [0, 0, 0], "thumb": "par", "o": "down"},
    "R": {"f": [f("E"), f("E", [8]), f("F"), f("F")], "spread": [-14, 0, 0], "thumb": "across", "o": "front"},
    "S": {"f": [f("F"), f("F"), f("F"), f("F")], "spread": [0, 0, 0], "thumb": "across", "o": "front"},
    "T": {"f": [f("F", [-15]), f("F"), f("F"), f("F")], "spread": [0, 0, 0], "thumb": "underT", "o": "front"},
    "U": {"f": [f("E"), f("E"), f("F"), f("F")], "spread": [0, 0, 0], "thumb": "across", "o": "front"},
    "V": {"f": [f("E"), f("E"), f("F"), f("F")], "spread": [14, 0, 0], "thumb": "across", "o": "front"},
    "W": {"f": [f("E"), f("E"), f("E"), f("F")], "spread": [12, 12, 0], "thumb": "touch:pinky", "o": "front"},
    "X": {"f": [f("H"), f("F"), f("F"), f("F")], "spread": [0, 0, 0], "thumb": "across", "o": "side"},
    "Y": {"f": [f("F"), f("F"), f("F"), f("E")], "spread": [0, 0, 16], "thumb": "out", "o": "front"},
    "Z": {"f": [f("E"), f("F"), f("F"), f("F")], "spread": [0, 0, 0], "thumb": "across", "o": "front", "motion": "Z"},
}
ORDER = ["OPEN"] + [chr(c) for c in range(ord("A"), ord("Z") + 1)]
THUMB_FIRST = set("MNTSEA")
OFFS = ["thumb", "index", "middle", "ring", "pinky"]


# ------------------------------------------------------------------------------------------------ math
def qaxis(axis, ang):
    axis = np.asarray(axis, float)
    axis = axis / np.linalg.norm(axis)
    s = math.sin(ang / 2)
    return np.array([axis[0] * s, axis[1] * s, axis[2] * s, math.cos(ang / 2)])


def qmul(a, b):
    ax, ay, az, aw = a
    bx, by, bz, bw = b
    return np.array([
        aw * bx + ax * bw + ay * bz - az * by,
        aw * by - ax * bz + ay * bw + az * bx,
        aw * bz + ax * by - ay * bx + az * bw,
        aw * bw - ax * bx - ay * by - az * bz,
    ])


def qrot(q, v):
    u = q[:3]
    w = q[3]
    v = np.asarray(v, float)
    return v + 2 * np.cross(u, np.cross(u, v) + w * v)


def qinv(q):
    return np.array([-q[0], -q[1], -q[2], q[3]])


def qfrom_vectors(a, b):
    a = a / np.linalg.norm(a)
    b = b / np.linalg.norm(b)
    d = float(np.dot(a, b))
    if d < -0.999999:
        axis = np.cross([1, 0, 0], a)
        if np.linalg.norm(axis) < 1e-6:
            axis = np.cross([0, 1, 0], a)
        return qaxis(axis, math.pi)
    c = np.cross(a, b)
    q = np.array([c[0], c[1], c[2], 1 + d])
    return q / np.linalg.norm(q)


def qfrom_matrix(m):
    t = m[0, 0] + m[1, 1] + m[2, 2]
    if t > 0:
        s = 0.5 / math.sqrt(t + 1)
        q = [(m[2, 1] - m[1, 2]) * s, (m[0, 2] - m[2, 0]) * s, (m[1, 0] - m[0, 1]) * s, 0.25 / s]
    elif m[0, 0] > m[1, 1] and m[0, 0] > m[2, 2]:
        s = 2 * math.sqrt(1 + m[0, 0] - m[1, 1] - m[2, 2])
        q = [0.25 * s, (m[0, 1] + m[1, 0]) / s, (m[0, 2] + m[2, 0]) / s, (m[2, 1] - m[1, 2]) / s]
    elif m[1, 1] > m[2, 2]:
        s = 2 * math.sqrt(1 + m[1, 1] - m[0, 0] - m[2, 2])
        q = [(m[0, 1] + m[1, 0]) / s, 0.25 * s, (m[1, 2] + m[2, 1]) / s, (m[0, 2] - m[2, 0]) / s]
    else:
        s = 2 * math.sqrt(1 + m[2, 2] - m[0, 0] - m[1, 1])
        q = [(m[0, 2] + m[2, 0]) / s, (m[1, 2] + m[2, 1]) / s, 0.25 * s, (m[1, 0] - m[0, 1]) / s]
    q = np.array(q)
    return q / np.linalg.norm(q)


def mat_of(q):
    x, y, z, w = q
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ])


def trs(t, q, s):
    m = np.eye(4)
    m[:3, :3] = mat_of(q) * np.asarray(s, float)
    m[:3, 3] = t
    return m


def decompose(m):
    t = m[:3, 3].copy()
    s = np.linalg.norm(m[:3, :3], axis=0)
    r = m[:3, :3] / s
    return t, qfrom_matrix(r), s


def euler_xyz(deg):
    """three.js Euler(x, y, z, 'XYZ') -> quaternion = qx * qy * qz."""
    x, y, z = [d * D2R for d in deg]
    return qmul(qmul(qaxis([1, 0, 0], x), qaxis([0, 1, 0], y)), qaxis([0, 0, 1], z))


def slerp(a, b, t):
    d = float(np.dot(a, b))
    if d < 0:
        b = -b
        d = -d
    if d > 0.9995:
        q = a + (b - a) * t
        return q / np.linalg.norm(q)
    th = math.acos(d)
    return (math.sin((1 - t) * th) * a + math.sin(t * th) * b) / math.sin(th)


# ------------------------------------------------------------------------------------------- pose solver
def off_angles(ang):
    """The deliberately wrong finger: a bent finger straightens, a straight / half-bent one curls into a fist."""
    return [85, 100, 70] if sum(ang) < 150 else [10, 15, 10]


def solve(pose, off=None):
    out = {}
    sp = pose["spread"]
    spread_at = {"index": -sp[0], "middle": 0, "ring": sp[1], "pinky": sp[1] + sp[2]}
    for i, name in enumerate(FINGERS):
        ang = list(pose["f"][i])
        if off == name:
            ang = off_angles(ang)
        b = BASE[name]
        p = np.array([b[0], b[1], 0.0])
        pts = [p.copy()]
        abd = qaxis([0, 0, 1], -spread_at[name] * D2R)
        lat = qrot(abd, [1, 0, 0])
        q = abd.copy()
        for j in range(3):
            q = qmul(qaxis(lat, -ang[j] * D2R), q)
            p = p + qrot(q, [0, LEN[name][j], 0])
            pts.append(p.copy())
        out[name] = pts
    th = pose["thumb"]
    if off == "thumb":
        th = "tuck" if th in ("out", "open") else "out"
    if th.startswith("touch:"):
        tgt = out[th[6:]][3].copy()
        tgt[2] -= 0.004
    else:
        tgt = np.array(THUMB[th], float)
    out["thumb"] = thumb_chain(np.array(CMC, float), tgt)
    return out


def thumb_chain(a, b):
    total = 0.105
    d = b - a
    ln = float(np.linalg.norm(d))
    bulge = max(0.006, math.sqrt(max(0.0, total * total - ln * ln)) * 0.35)
    side = np.array([-0.6, 0, -0.8])
    side = side / np.linalg.norm(side)
    pts = [a.copy()]
    for t in (0.43, 0.74):
        pts.append(a + d * t + side * bulge * math.sin(math.pi * t))
    pts.append(b.copy())
    return pts


def pose_blend(frm, to, t, off=None):
    a = L[frm]
    b = L[to]
    t2 = th = t
    if to in THUMB_FIRST:
        t2 = min(1.0, max(0.0, (t - 0.4) / 0.6))
        th = min(1.0, t / 0.4)
    lf = [[x + (y - x) * t2 for x, y in zip(fa, fb)] for fa, fb in zip(a["f"], b["f"])]
    ls = [x + (y - x) * t2 for x, y in zip(a["spread"], b["spread"])]
    pa = solve({"f": lf, "spread": ls, "thumb": a["thumb"]}, off)
    pb = solve({"f": lf, "spread": ls, "thumb": b["thumb"]}, off)
    pa["thumb"] = [x + (y - x) * th for x, y in zip(pa["thumb"], pb["thumb"])]
    return pa


def joints25(p):
    """[WRIST, THUMB_MC, THUMB_PROX, THUMB_DIST, THUMB_TIP, then per finger MC, PROX, INT, DIST, TIP]."""
    pts = [np.zeros(3)] + list(p["thumb"])
    for name in FINGERS:
        mcp = p[name][0]
        pts.append(mcp * 0.3)
        pts.extend(p[name])
    return pts


# ------------------------------------------------------------------------------------------------- glb io
def read_glb(path):
    b = path.read_bytes()
    jl = struct.unpack("<I", b[12:16])[0]
    j = json.loads(b[20:20 + jl])
    off = 20 + jl
    bl = struct.unpack("<I", b[off:off + 4])[0]
    binbuf = b[off + 8:off + 8 + bl]
    return j, binbuf


COMP = {5126: ("f", 4), 5123: ("H", 2), 5121: ("B", 1), 5125: ("I", 4)}
NCOMP = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


def accessor(j, binbuf, i):
    a = j["accessors"][i]
    bv = j["bufferViews"][a["bufferView"]]
    fmt, size = COMP[a["componentType"]]
    n = NCOMP[a["type"]]
    start = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
    dt = {"f": np.float32, "H": np.uint16, "B": np.uint8, "I": np.uint32}[fmt]
    arr = np.frombuffer(binbuf, dtype=dt, count=a["count"] * n, offset=start)
    return arr.reshape(a["count"], n) if n > 1 else arr


class GlbWriter:
    def __init__(self):
        self.buf = bytearray()
        self.views = []
        self.accessors = []

    def add(self, arr, comp, typ, target=None, minmax=False):
        while len(self.buf) % 4:
            self.buf.append(0)
        data = arr.tobytes()
        view = {"buffer": 0, "byteOffset": len(self.buf), "byteLength": len(data)}
        if target:
            view["target"] = target
        self.views.append(view)
        self.buf += data
        acc = {"bufferView": len(self.views) - 1, "componentType": comp, "count": int(arr.shape[0]), "type": typ}
        if minmax:
            flat = arr.reshape(arr.shape[0], -1)
            acc["min"] = [float(v) for v in flat.min(axis=0)]
            acc["max"] = [float(v) for v in flat.max(axis=0)]
        self.accessors.append(acc)
        return len(self.accessors) - 1


def group_of(name):
    for g in ("thumb", "index", "middle", "ring", "pinky"):
        if name.startswith(g):
            return g
    return "palm"


GROUPS = ["thumb", "index", "middle", "ring", "pinky", "palm"]
WX = {"index": "index-finger", "middle": "middle-finger", "ring": "ring-finger", "pinky": "pinky-finger"}
WXSEG = ["metacarpal", "phalanx-proximal", "phalanx-intermediate", "phalanx-distal", "tip"]
WXTH = ["thumb-metacarpal", "thumb-phalanx-proximal", "thumb-phalanx-distal", "thumb-tip"]
MIDDLE_KNUCKLE = 0.0881


def bake(side):
    left = side == "left"
    j, binbuf = read_glb(SRC / f"{side}.glb")
    nodes = j["nodes"]
    names = {n.get("name"): i for i, n in enumerate(nodes)}
    arm_i = next(i for i, n in enumerate(nodes) if n.get("name") == "Armature")
    mesh_node_i = next(i for i, n in enumerate(nodes) if "mesh" in n)
    joint_ids = j["skins"][0]["joints"]
    for i in joint_ids:
        assert i in nodes[arm_i]["children"], "joints must be direct children of the armature"
    rest_local = {}
    for i in joint_ids:
        n = nodes[i]
        rest_local[n["name"]] = (np.array(n.get("translation", [0, 0, 0]), float), np.array(n.get("rotation", [0, 0, 0, 1]), float),
                                 np.array(n.get("scale", [1, 1, 1]), float))

    def P(name):
        return rest_local[name][0]

    # model (armature) space -> hand frame: wrist at 0, fingers +Y, back +Z, thumb -X (right) / +X (left)
    w = P("wrist")
    y = P("middle-finger-phalanx-proximal") - w
    y /= np.linalg.norm(y)
    v = P("index-finger-phalanx-proximal") - P("pinky-finger-phalanx-proximal")  # towards the thumb
    v = v - y * np.dot(v, y)
    v /= np.linalg.norm(v)
    x = v if left else -v
    z = np.cross(x, y)
    basis = np.stack([x, y, z], axis=1)  # columns = hand axes in model space
    rot = qfrom_matrix(basis.T)  # model -> hand
    scale = MIDDLE_KNUCKLE / float(np.linalg.norm(P("middle-finger-phalanx-proximal") - w))
    align = trs(-qrot(rot, w) * scale, rot, [scale] * 3)
    align_inv = np.linalg.inv(align)
    # rest in hand space
    rest = {}
    for name, (t, q, s) in rest_local.items():
        m = align @ trs(t, q, s)
        rest[name] = decompose(m)

    chains = [(WX[fg], [WX[fg] + "-" + s for s in WXSEG], 2, fg) for fg in FINGERS] + [("thumb", WXTH, 1, "thumb")]

    def pose_locals(p):
        """Our joint targets (hand frame) -> local TRS of every joint node (armature space)."""
        if left:
            p = {k: [np.array([-q[0], q[1], q[2]]) for q in v] for k, v in p.items()}
        out = {}
        for _, cn, fixed, fg in chains:
            pts = p[fg]
            heads = [rest[cn[i]][0].copy() for i in range(fixed)]
            for k in range(3):
                seg = float(np.linalg.norm(rest[cn[fixed + k]][0] - rest[cn[fixed + k - 1]][0]))
                d = pts[k + 1] - pts[k]
                d = d / np.linalg.norm(d)
                heads.append(heads[-1] + d * seg)
            for i, n in enumerate(cn):
                a0 = i if i < len(cn) - 1 else i - 1
                rd = rest[cn[a0 + 1]][0] - rest[cn[a0]][0]
                td = heads[a0 + 1] - heads[a0]
                q = qmul(qfrom_vectors(rd, td), rest[n][1])
                m = trs(heads[i], q, rest[n][2])
                out[n] = decompose(align_inv @ m)
        out["wrist"] = rest_local["wrist"]
        return out

    # ---- animation samples
    times = []
    frames = []
    steps = 24
    for k, letter in enumerate(ORDER):
        t0 = 2.0 * k
        for s in range(steps + 1):
            u = s / steps
            times.append(t0 + 1.2 * u)
            frames.append(pose_locals(pose_blend("OPEN", letter, u)))
        times.append(t0 + 1.95)
        frames.append(frames[-1])

    # ---- mesh split
    prim = j["meshes"][0]["primitives"][0]
    attrs = prim["attributes"]
    pos = accessor(j, binbuf, attrs["POSITION"]).astype(np.float32)
    nor = accessor(j, binbuf, attrs["NORMAL"]).astype(np.float32)
    uv = accessor(j, binbuf, attrs["TEXCOORD_0"]).astype(np.float32)
    jnt = accessor(j, binbuf, attrs["JOINTS_0"]).astype(np.uint8)
    wgt = accessor(j, binbuf, attrs["WEIGHTS_0"]).astype(np.float32)
    idx = accessor(j, binbuf, prim["indices"]).astype(np.uint16).reshape(-1, 3)
    ibm = accessor(j, binbuf, j["skins"][0]["inverseBindMatrices"]).astype(np.float32)
    joint_names = [nodes[i]["name"] for i in joint_ids]
    vgroup = np.array([GROUPS.index(group_of(joint_names[jnt[i][int(np.argmax(wgt[i]))]])) for i in range(len(pos))])
    tri_group = []
    for tri in idx:
        g = vgroup[tri]
        vals, counts = np.unique(g, return_counts=True)
        best = vals[np.argmax(counts)] if counts.max() > 1 else GROUPS.index("palm")
        tri_group.append(best)
    tri_group = np.array(tri_group)

    wtr = GlbWriter()
    a_pos = wtr.add(pos, 5126, "VEC3", 34962, minmax=True)
    a_nor = wtr.add(nor, 5126, "VEC3", 34962)
    a_uv = wtr.add(uv, 5126, "VEC2", 34962)
    a_j = wtr.add(jnt, 5121, "VEC4", 34962)
    a_w = wtr.add(wgt, 5126, "VEC4", 34962)
    prims = []
    materials = []
    counts = {}
    for gi, g in enumerate(GROUPS):
        tris = idx[tri_group == gi]
        counts[g] = int(len(tris))
        a_i = wtr.add(tris.reshape(-1).astype(np.uint16), 5123, "SCALAR", 34963)
        materials.append({"name": f"hand_{g}", "pbrMetallicRoughness": {"baseColorFactor": [0.79, 0.56, 0.45, 1], "metallicFactor": 0, "roughnessFactor": 0.6}})
        prims.append({"attributes": {"POSITION": a_pos, "NORMAL": a_nor, "TEXCOORD_0": a_uv, "JOINTS_0": a_j, "WEIGHTS_0": a_w}, "indices": a_i, "material": gi})
    a_ibm = wtr.add(ibm, 5126, "MAT4")

    # ---- nodes: armature carries the align transform
    out_nodes = []
    for i, n in enumerate(nodes):
        n2 = {k: v for k, v in n.items()}
        if i == arm_i:
            t, q, s = decompose(align)
            n2["translation"] = [float(v) for v in t]
            n2["rotation"] = [float(v) for v in q]
            n2["scale"] = [float(v) for v in s]
        out_nodes.append(n2)
    for n in out_nodes:
        n.pop("name", None) if False else None

    # ---- animation
    t_arr = np.array(times, np.float32)
    a_time = wtr.add(t_arr, 5126, "SCALAR", minmax=True)
    samplers = []
    channels = []
    for i in joint_ids:
        name = nodes[i]["name"]
        if name == "wrist":
            continue
        tr = np.array([fr[name][0] for fr in frames], np.float32)
        ro = np.array([fr[name][1] for fr in frames], np.float32)
        # keep quaternions in one hemisphere so LINEAR does not take the long way
        for r in range(1, len(ro)):
            if np.dot(ro[r], ro[r - 1]) < 0:
                ro[r] = -ro[r]
        a_tr = wtr.add(tr, 5126, "VEC3")
        a_ro = wtr.add(ro, 5126, "VEC4")
        samplers.append({"input": a_time, "output": a_tr, "interpolation": "LINEAR"})
        channels.append({"sampler": len(samplers) - 1, "target": {"node": i, "path": "translation"}})
        samplers.append({"input": a_time, "output": a_ro, "interpolation": "LINEAR"})
        channels.append({"sampler": len(samplers) - 1, "target": {"node": i, "path": "rotation"}})

    gj = {
        "asset": {"version": "2.0", "generator": "sign-hands bake_hands.py", "copyright": "WebXR Input Profiles generic-hand (c) 2019 Amazon, MIT"},
        "scene": 0,
        "scenes": [{"name": "Scene", "nodes": [arm_i]}],
        "nodes": out_nodes,
        "meshes": [{"name": f"hand_{side}", "primitives": prims}],
        "materials": materials,
        "skins": [{"joints": joint_ids, "inverseBindMatrices": a_ibm}],
        "animations": [{"name": "poses", "channels": channels, "samplers": samplers}],
        "accessors": wtr.accessors,
        "bufferViews": wtr.views,
        "buffers": [{"byteLength": len(wtr.buf)}],
    }
    while len(wtr.buf) % 4:
        wtr.buf.append(0)
    gj["buffers"][0]["byteLength"] = len(wtr.buf)
    js = json.dumps(gj, separators=(",", ":")).encode()
    while len(js) % 4:
        js += b" "
    total = 12 + 8 + len(js) + 8 + len(wtr.buf)
    out = bytearray()
    out += struct.pack("<III", 0x46546C67, 2, total)
    out += struct.pack("<II", len(js), 0x4E4F534A) + js
    out += struct.pack("<II", len(wtr.buf), 0x004E4942) + bytes(wtr.buf)
    dst = ASSETS / "hands" / f"hand_{side}.glb"
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_bytes(out)
    # model's own tip / knuckle positions in the hand frame (rest = open hand as modelled)
    model_len = float(np.linalg.norm(rest["middle-finger-phalanx-proximal"][0]) + sum(
        np.linalg.norm(rest[WX["middle"] + "-" + WXSEG[s + 1]][0] - rest[WX["middle"] + "-" + WXSEG[s]][0]) for s in range(1, 4)))
    print(f"{side}: {len(out)} bytes, tris per group {counts}, model hand length {model_len:.4f} m, keys {len(times)}")
    return model_len


def main():
    pkg = json.loads((FACTORY_APP / "design-package.json").read_text(encoding="utf-8"))
    table = {row["l"]: row for row in pkg["letters"]["table"]}
    scored = set(pkg["letters"]["scored"])
    model_len = bake("right")
    bake("left")
    letters = []
    for k, letter in enumerate(ORDER):
        pose = L[letter]
        entry = {
            "l": letter,
            "clipStart": 2.0 * k,
            "orient": ORIENT[pose["o"]],
            "orientName": pose["o"],
            "thumb": pose["thumb"],
            "thumbFirst": letter in THUMB_FIRST,
            "motion": pose.get("motion"),
            "scored": letter in scored,
            "joints": {"ok": [[round(float(c), 5) for c in p] for p in joints25(solve(pose))]},
        }
        if letter != "OPEN":
            row = table[letter]
            entry["tipZh"] = row["tipZh"]
            entry["tipEn"] = row["tipEn"]
            for off in OFFS:
                entry["joints"][off] = [[round(float(c), 5) for c in p] for p in joints25(solve(pose, off))]
        letters.append(entry)
    doc = {
        "about": "Generated by tools/bake_hands.py from the design package (letters.authoring); right hand, hand frame: wrist 0, fingers +Y, back +Z, thumb -X. Joint order: WRIST, THUMB_MC/PROX/DIST/TIP, then INDEX, MIDDLE, RING, LITTLE x MC/PROX/INT/DIST/TIP.",
        "clipSegment": 2.0,
        "morphSec": 1.2,
        "modelHandLength": round(model_len, 5),
        "lessons": pkg["lessons"]["list"],
        "letters": letters,
    }
    (ASSETS / "asl_letters.json").write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
    lic = ASSETS / "licenses"
    lic.mkdir(parents=True, exist_ok=True)
    (lic / "webxr-input-profiles-LICENSE.md").write_bytes((SRC / "LICENSE.md").read_bytes())
    print("asl_letters.json", (ASSETS / "asl_letters.json").stat().st_size, "bytes")


if __name__ == "__main__":
    sys.exit(main())
