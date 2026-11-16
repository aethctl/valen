# SPDX-License-Identifier: GPL-3.0-only
"""Raymarched retro CRT television, rendered as a turntable of yaw angles.

Each frame gives two layers:
  body-NN.png   grey shading of the tintable plastic (premultiplied luminance, alpha = coverage)
  detail-NN.png everything else, plus the gloss on the plastic and the glass over the tube
and quads.json holds the tube's four corners in pixels for each frame, so a picture can be laid
on it in perspective.
"""
import sys, json, math, os
import numpy as np
from PIL import Image
from multiprocessing import Pool

OUT = sys.argv[1]
W = int(sys.argv[2]) if len(sys.argv) > 2 else 600
H = int(W * 1.04)
SS = int(sys.argv[3]) if len(sys.argv) > 3 else 2
YAWS = [float(a) for a in sys.argv[4].split(',')] if len(sys.argv) > 4 else [-30 + 3 * i for i in range(21)]
f32 = np.float32

# ---------------------------------------------------------------- geometry
SX, SY = -0.27, 0.03          # tube centre on the front
FRONT = 0.62                  # front face z
R_BULGE = 3.0
TUBE_HALF = (0.55, 0.43)

def length(x, y, z): return np.sqrt(x * x + y * y + z * z)

def round_box(px, py, pz, bx, by, bz, r):
    qx = np.abs(px) - bx + r; qy = np.abs(py) - by + r; qz = np.abs(pz) - bz + r
    out = length(np.maximum(qx, 0), np.maximum(qy, 0), np.maximum(qz, 0))
    return out + np.minimum(np.maximum(qx, np.maximum(qy, qz)), 0) - r

def capsule(px, py, pz, a, b, r):
    bax, bay, baz = b[0] - a[0], b[1] - a[1], b[2] - a[2]
    pax, pay, paz = px - a[0], py - a[1], pz - a[2]
    h = np.clip((pax * bax + pay * bay + paz * baz) / (bax * bax + bay * bay + baz * baz), 0, 1)
    return length(pax - bax * h, pay - bay * h, paz - baz * h) - r

def ellipsoid(px, py, pz, rx, ry, rz):
    k0 = length(px / rx, py / ry, pz / rz); k1 = length(px / rx / rx, py / ry / ry, pz / rz / rz)
    return k0 * (k0 - 1) / np.maximum(k1, 1e-6)

def cyl_z(px, py, pz, r, h):
    dx = np.sqrt(px * px + py * py) - r; dz = np.abs(pz) - h
    return np.minimum(np.maximum(dx, dz), 0) + np.sqrt(np.maximum(dx, 0) ** 2 + np.maximum(dz, 0) ** 2)

def smin(a, b, k):
    h = np.clip(0.5 + 0.5 * (b - a) / k, 0, 1)
    return b * (1 - h) + a * h - k * h * (1 - h)

ANT_A = (-0.06, 0.78, -0.25); ANT_B = (-0.80, 1.58, -0.42)
ANT_C = (0.06, 0.78, -0.25);  ANT_D = (0.62, 1.62, -0.12)

def parts(px, py, pz):
    """Distance to each part, by material: 1 plastic (tinted), 2 dark plastic, 3 tube glass,
    4 cream knobs, 5 chrome, 6 lamp (tinted, lit)."""
    front = round_box(px, py, pz, 1.0, 0.72, 0.62, 0.16)
    back = round_box(px, py - 0.02, pz + 0.78, 0.72, 0.52, 0.38, 0.24)
    body = smin(front, back, 0.18)
    recess = round_box(px - SX, py - SY, pz - FRONT, 0.63, 0.51, 0.09, 0.13)
    body = np.maximum(body, -recess)
    # speaker slots, five rows
    gy = py + 0.36
    idx = np.clip(np.round(gy / 0.07), -2, 2)
    qy = gy - idx * 0.07
    slots = round_box(px - 0.70, qy, pz - FRONT, 0.2, 0.013, 0.035, 0.008)
    body = np.maximum(body, -slots)
    tips = np.minimum(length(px - ANT_B[0], py - ANT_B[1], pz - ANT_B[2]) - 0.05,
                      length(px - ANT_D[0], py - ANT_D[1], pz - ANT_D[2]) - 0.05)
    plastic = np.minimum(body, tips)

    # the recess walls and floor read as dark bezel: a thin shell just inside the recess
    bezel = np.maximum(round_box(px - SX, py - SY, pz - (FRONT - 0.1), 0.62, 0.5, 0.08, 0.12), -recess - 0.0)
    bezel = np.maximum(round_box(px - SX, py - SY, pz - (FRONT - 0.06), 0.64, 0.52, 0.06, 0.13), -(recess + 0.012))
    plate = round_box(px - 0.70, py - 0.50, pz - FRONT, 0.17, 0.05, 0.014, 0.012)
    base = ellipsoid(px, py - 0.73, pz + 0.25, 0.25, 0.1, 0.21)
    feet = np.minimum(round_box(px - 0.68, py + 0.80, pz - 0.15, 0.1, 0.1, 0.32, 0.05),
                      round_box(px + 0.68, py + 0.80, pz - 0.15, 0.1, 0.1, 0.32, 0.05))
    dark = np.minimum(np.minimum(bezel, plate), base)

    zc = 0.6 - R_BULGE
    tube = np.maximum(round_box(px - SX, py - SY, pz - 0.45, TUBE_HALF[0], TUBE_HALF[1], 0.2, 0.12),
                      length(px - SX, py - SY, pz - zc) - R_BULGE)

    a1 = np.arctan2(py - 0.27, px - 0.70)
    k1 = cyl_z(px - 0.70, py - 0.27, pz - 0.67, 0.12 + 0.005 * np.sin(a1 * 24), 0.055) - 0.012
    k1 = np.minimum(k1, round_box(px - 0.70, py - 0.335, pz - 0.735, 0.013, 0.05, 0.012, 0.006))
    a2 = np.arctan2(py + 0.0, px - 0.70)
    k2 = cyl_z(px - 0.70, py + 0.0, pz - 0.66, 0.085 + 0.004 * np.sin(a2 * 20), 0.045) - 0.01
    k2 = np.minimum(k2, round_box(px - 0.745, py + 0.0, pz - 0.715, 0.04, 0.01, 0.01, 0.005))
    button = round_box(px - 0.82, py + 0.58, pz - FRONT, 0.075, 0.035, 0.035, 0.016)
    cream = np.minimum(np.minimum(k1, k2), button)

    chrome = np.minimum(capsule(px, py, pz, ANT_A, ANT_B, 0.017), capsule(px, py, pz, ANT_C, ANT_D, 0.017))
    # a bright trim round the tube's opening, and chrome feet
    trim = np.maximum(round_box(px - SX, py - SY, pz - FRONT, 0.665, 0.545, 0.022, 0.15),
                      -round_box(px - SX, py - SY, pz - FRONT, 0.632, 0.512, 0.2, 0.13))
    chrome = np.minimum(np.minimum(chrome, trim), feet)
    lamp = length(px - 0.56, py + 0.58, pz - FRONT) - 0.024
    return [plastic, dark, tube, cream, chrome, lamp]

def scene(px, py, pz):
    d = parts(px, py, pz)
    out = d[0]
    for x in d[1:]:
        out = np.minimum(out, x)
    return out

def material(px, py, pz):
    d = np.stack(parts(px, py, pz))
    return np.argmin(d, axis=0) + 1

# ---------------------------------------------------------------- camera
CAM_DIST = 7.2
CAM_Y = 1.05
LOOK_Y = 0.32
HALF_H = 1.58  # visible half height at the target

def camera():
    eye = np.array([0, CAM_Y, CAM_DIST], f32)
    target = np.array([0, LOOK_Y, 0], f32)
    fwd = target - eye; fwd /= np.linalg.norm(fwd)
    right = np.cross(fwd, [0, 1, 0]).astype(f32); right /= np.linalg.norm(right)
    up = np.cross(right, fwd).astype(f32)
    focal = np.linalg.norm(target - eye) / HALF_H
    return eye, fwd, right, up, focal

def rot_y(x, z, deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return c * x + s * z, -s * x + c * z

def project(point, yaw, w, h):
    eye, fwd, right, up, focal = camera()
    x, z = rot_y(point[0], point[2], yaw)  # the set turns by yaw
    p = np.array([x, point[1], z], f32) - eye
    cz = p @ fwd
    u = (p @ right) / cz * focal
    v = (p @ up) / cz * focal
    aspect = w / h
    return ((u / aspect + 1) * 0.5 * w, (1 - (v + 1) * 0.5) * h)

# ---------------------------------------------------------------- shading
KEY = np.array([-0.55, 0.75, 0.55], f32); KEY /= np.linalg.norm(KEY)
FILL = np.array([0.75, 0.15, 0.5], f32); FILL /= np.linalg.norm(FILL)
RIM = np.array([0.3, 0.55, -0.8], f32); RIM /= np.linalg.norm(RIM)

def env(dx, dy, dz):
    sky = 0.18 + 0.82 * np.clip((dy + 0.25) / 1.0, 0, 1) ** 1.5
    box = np.exp(-((dx + 0.45) ** 2 * 7 + (dy - 0.7) ** 2 * 7 + (dz - 0.55) ** 2 * 4)) * 2.2
    strip = np.exp(-((dx - 0.85) ** 2 * 20 + (dy - 0.1) ** 2 * 3)) * 0.8
    return sky + box + strip

def render(yaw):
    w, h = W * SS, H * SS
    eye, fwd, right, up, focal = camera()
    js, is_ = np.meshgrid(np.arange(h, dtype=f32), np.arange(w, dtype=f32), indexing='ij')
    u = ((is_ + 0.5) / w * 2 - 1) * (w / h)
    v = 1 - (js + 0.5) / h * 2
    dx = fwd[0] * focal + right[0] * u + up[0] * v
    dy = fwd[1] * focal + right[1] * u + up[1] * v
    dz = fwd[2] * focal + right[2] * u + up[2] * v
    n = np.sqrt(dx * dx + dy * dy + dz * dz); dx /= n; dy /= n; dz /= n
    # into the set's own frame: turn the rays the other way
    ox, oz = rot_y(eye[0], eye[2], -yaw); oy = eye[1]
    dx, dz = rot_y(dx, dz, -yaw)
    ox = np.full_like(dx, ox); oy = np.full_like(dx, oy); oz = np.full_like(dx, oz)

    t = np.full(dx.shape, 4.0, f32)
    alive = np.ones(dx.shape, bool)
    hit = np.zeros(dx.shape, bool)
    for _ in range(140):
        idx = np.nonzero(alive)
        if idx[0].size == 0:
            break
        px = ox[idx] + dx[idx] * t[idx]; py = oy[idx] + dy[idx] * t[idx]; pz = oz[idx] + dz[idx] * t[idx]
        d = scene(px, py, pz)
        t[idx] += d * 0.9
        done = d < 0.0015
        far = t[idx] > 12
        hit[idx[0][done], idx[1][done]] = True
        stop = done | far
        alive[idx[0][stop], idx[1][stop]] = False

    body = np.zeros((h, w, 2), f32)    # luminance (premul), alpha
    detail = np.zeros((h, w, 4), f32)  # rgb (premul), alpha
    hi = np.nonzero(hit)
    px = ox[hi] + dx[hi] * t[hi]; py = oy[hi] + dy[hi] * t[hi]; pz = oz[hi] + dz[hi] * t[hi]
    rdx, rdy, rdz = dx[hi], dy[hi], dz[hi]
    e = 0.0015
    k = [(1, -1, -1), (-1, -1, 1), (-1, 1, -1), (1, 1, 1)]
    nx = ny = nz = 0
    for a, b, c in k:
        s = scene(px + a * e, py + b * e, pz + c * e)
        nx = nx + a * s; ny = ny + b * s; nz = nz + c * s
    nl = np.sqrt(nx * nx + ny * ny + nz * nz) + 1e-9; nx /= nl; ny /= nl; nz /= nl
    mat = material(px, py, pz)

    # ambient occlusion
    ao = np.zeros_like(px)
    for i, hh in enumerate([0.02, 0.06, 0.12, 0.2, 0.3]):
        ao += (hh - scene(px + nx * hh, py + ny * hh, pz + nz * hh)) * (0.75 ** i)
    ao = np.clip(1 - 2.2 * ao, 0, 1)
    # soft shadow toward the key light
    sh = np.ones_like(px); st = np.full_like(px, 0.02)
    sx, sy, sz = px + nx * 0.004, py + ny * 0.004, pz + nz * 0.004
    for _ in range(40):
        d = scene(sx + KEY[0] * st, sy + KEY[1] * st, sz + KEY[2] * st)
        sh = np.minimum(sh, np.clip(9 * d / st, 0, 1))
        st += np.clip(d, 0.01, 0.15)
    sh = np.clip(sh, 0, 1)

    vx, vy, vz = -rdx, -rdy, -rdz
    ndv = np.clip(nx * vx + ny * vy + nz * vz, 0, 1)
    def lam(L): return np.clip(nx * L[0] + ny * L[1] + nz * L[2], 0, 1)
    def spec(L, power):
        hx, hy, hz = L[0] + vx, L[1] + vy, L[2] + vz
        hn = np.sqrt(hx * hx + hy * hy + hz * hz) + 1e-9
        return np.clip((nx * hx + ny * hy + nz * hz) / hn, 0, 1) ** power
    rx = rdx - 2 * (rdx * nx + rdy * ny + rdz * nz) * nx
    ry = rdy - 2 * (rdx * nx + rdy * ny + rdz * nz) * ny
    rz = rdz - 2 * (rdx * nx + rdy * ny + rdz * nz) * nz
    envr = env(rx, ry, rz)
    fres = 0.04 + 0.96 * (1 - ndv) ** 5
    diffuse = 0.3 * ao + 0.85 * lam(KEY) * sh + 0.3 * lam(FILL) * (0.5 + 0.5 * ao) + 0.3 * lam(RIM)
    gloss = (0.95 * spec(KEY, 110) * sh + 0.25 * spec(FILL, 60) + 0.3 * spec(RIM, 50)) + fres * envr * 0.75 * (0.35 + 0.65 * ao)

    lum = np.zeros_like(px); drgb = np.zeros((px.size, 3), f32); da = np.zeros_like(px)
    m = mat == 1  # tinted plastic: shading to body, gloss to detail as white
    lum[m] = np.clip(diffuse[m] * 0.95, 0, 1)
    ga = np.clip(gloss[m], 0, 1)
    drgb[m] = ga[:, None]; da[m] = ga
    m = mat == 6  # lamp: lit, tinted
    lum[m] = 1.0; drgb[m] = 0.45; da[m] = 0.45
    m = mat == 2  # dark plastic
    c = 0.055 * diffuse[m] + 0.6 * gloss[m] * 0.6
    drgb[m] = np.clip(c, 0, 1)[:, None] * np.array([1.0, 0.97, 1.02], f32); da[m] = 1
    m = mat == 4  # cream knobs
    c = 0.86 * diffuse[m] + 0.8 * gloss[m]
    drgb[m] = np.clip(c[:, None] * np.array([1.0, 0.95, 0.88], f32), 0, 1); da[m] = 1
    m = mat == 5  # chrome
    c = 0.08 + 0.85 * envr[m] * (0.5 + 0.5 * ao[m]) + 0.8 * spec(KEY, 120)[m] * sh[m]
    drgb[m] = np.clip(c, 0, 1)[:, None] * np.array([0.95, 0.97, 1.0], f32); da[m] = 1
    m = mat == 3  # tube glass: darkens toward the rim, reflects the room
    lx = px[m] - SX; ly = py[m] - SY
    edge = np.clip(np.maximum(np.abs(lx) / TUBE_HALF[0], np.abs(ly) / TUBE_HALF[1]), 0, 1)
    dark = np.clip((edge - 0.55) / 0.45, 0, 1) ** 1.6 * 0.75 + (1 - ao[m]) * 0.5
    refl = np.clip(fres[m] * envr[m] * 0.5 + 0.9 * spec(KEY, 160)[m] * sh[m] + 0.06, 0, 1)
    a = np.clip(dark + refl, 0, 1)
    drgb[m] = refl[:, None]; da[m] = a

    body[hi[0], hi[1], 0] = lum
    body[hi[0], hi[1], 1] = np.where((mat == 1) | (mat == 6), 1, 0)
    detail[hi[0], hi[1], :3] = drgb
    detail[hi[0], hi[1], 3] = da

    def down(img):
        return img.reshape(H, SS, W, SS, img.shape[2]).mean(axis=(1, 3))
    body = down(body); detail = down(detail)
    return body, detail

def save(yaw_index):
    i, yaw = yaw_index
    body, detail = render(yaw)
    b = np.zeros((H, W, 4), f32)
    a = body[..., 1]
    lumv = np.where(a > 1e-4, body[..., 0] / np.maximum(a, 1e-4), 0)
    b[..., 0] = b[..., 1] = b[..., 2] = np.clip(lumv, 0, 1)
    b[..., 3] = a
    d = np.zeros((H, W, 4), f32)
    da = detail[..., 3]
    d[..., :3] = np.where(da[..., None] > 1e-4, detail[..., :3] / np.maximum(da[..., None], 1e-4), 0)
    d[..., 3] = da
    for name, img in (('body', b), ('detail', d)):
        im = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8), 'RGBA')
        im.save(f'{OUT}/{name}-{i:02d}.png')
        im.save(f'{OUT}/tv_{name}_{i:02d}.webp', quality=90, alpha_quality=95, method=6)
    zq = 0.555
    corners = [(SX - TUBE_HALF[0], SY + TUBE_HALF[1], zq), (SX + TUBE_HALF[0], SY + TUBE_HALF[1], zq),
               (SX + TUBE_HALF[0], SY - TUBE_HALF[1], zq), (SX - TUBE_HALF[0], SY - TUBE_HALF[1], zq)]
    return i, yaw, [project(c, yaw, W, H) for c in corners]

if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    with Pool(min(12, len(YAWS))) as pool:
        quads = pool.map(save, list(enumerate(YAWS)))
    json.dump({'w': W, 'h': H, 'frames': [{'yaw': y, 'quad': [[round(float(x), 2), round(float(yv), 2)] for x, yv in q]} for _, y, q in sorted(quads)]},
              open(f'{OUT}/quads.json', 'w'))
    print('done', len(quads))
