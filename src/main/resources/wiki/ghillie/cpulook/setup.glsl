/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * Copyright (c) 2026, ghillie.wiki (the CPU look)
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

// The CPU look's per-triangle maths, shared by the geometry stage (geom.glsl) and the compute pass that sets up the
// triangles for the record path (setup_comp.glsl), so both give the very same setup: every corner turned and projected
// as the client does, the face's culling and near-plane cut, and for each triangle the CPU's own setup (geom.glsl's
// header says what). The includer declares the uniforms block (zoom, centerX, centerY, cameraRotF) and viewSize.

#include "cpu_turn.glsl"

// Java's division: toward zero
int idiv(int a, int b) {
  int q = int(uint(abs(a)) / uint(abs(b)));
  return (a < 0) != (b < 0) ? -q : q;
}

// Java's float division (correctly rounded), in floats: fdiv.glsl
#include "fdiv.glsl"

// a corner as the client's Model.draw leaves it: turned (floats), its screen point (floats), its ints
struct corner {
  vec3 cam;     // camera space, floats
  vec2 screen;  // mid + x * zoom / z, floats (z >= 50)
  ivec3 ci;     // (int) of the camera floats: the textured routine's points, the near cut
};

corner toCorner(vec3 rel) {
  corner k;
  k.cam = cpu_turn(rel);
  precise float px = k.cam.x * float(zoom);
  precise float py = k.cam.y * float(zoom);
  precise float sx = float(centerX) + fdiv(px, k.cam.z);
  precise float sy = float(centerY) + fdiv(py, k.cam.z);
  k.screen = vec2(sx, sy);
  k.ci = ivec3(int(k.cam.x), int(k.cam.y), int(k.cam.z));
  return k;
}

// the CPU's depth key for a corner at camera depth z (the face's bias already taken off)
float depthKey(float z) {
  precise float a = (z - 75.0) * 1.0100503;
  precise float k = fdiv(150.75377, a) + 1.0100503;
  return k;
}

// ((long) v << sh) / zoom, then (int), exactly as the CPU's Java does it: the true quotient's low 32 bits (q << sh
// wraps just as the cast does)
int ldiv(int v, int sh) {
  int q = idiv(v, zoom);
  int r = v - q * zoom;
  return q * (1 << sh) + idiv(r * (1 << sh), zoom);
}

// the textured routines' per-face terms (Rasterizer3D's textured triangle; for ground tiles its drawTexturedTile,
// whose steps across the screen are << 14 where the triangle's are << 3 << 14), in the CPU's own 32-bit arithmetic:
// products and << 14 wrap as Java's do, and each pixel's own value comes out right through the wrap, as the CPU's
// does. (Until 11/18/2026 big texture triangles had their points halved here until nothing wrapped, which moved
// the texture by a few units: the tree canopies' texels.)
void textureTerms(ivec3 p, ivec3 m, ivec3 n, bool tile, out ivec4 t0, out ivec4 t1, out int t40) {
  int across = tile ? 14 : 17;
  int a10 = p.x - m.x, a13 = p.y - m.y, a16 = p.z - m.z;  // P - M
  int a11 = n.x - p.x, a14 = n.y - p.y, a17 = n.z - p.z;  // N - P
  t0.x = (a11 * p.y - p.x * a14) << 14;
  t0.y = ldiv(p.z * a14 - a17 * p.y, across);
  t0.z = ldiv(a17 * p.x - a11 * p.z, 14);
  t0.w = (a10 * p.y - a13 * p.x) << 14;
  t1.x = ldiv(a13 * p.z - a16 * p.y, across);
  t1.y = ldiv(a16 * p.x - a10 * p.z, 14);
  t1.z = (a13 * a11 - a10 * a14) << 14;
  t1.w = ldiv(a16 * a14 - a13 * a17, across);
  t40 = ldiv(a17 * a10 - a11 * a16, 14);
}

// A triangle set up for the fragment stage: what the geometry stage hands each pixel (as flat varyings) or the record
// path stores (setup_comp.glsl), and its box on the screen.
struct Rec {
  ivec4 rows;   // top row, middle row, bottom row, flags (1 long edge on the left, 2 textured, 4/8 marks, 16 tile, texture << 8)
  ivec4 edge;   // top corner's x, middle corner's x, long edge's slope, top-to-middle slope
  ivec4 col;    // middle-to-bottom slope; textured faces' int shade where the top row meets x = 0, its step across, its step down
  vec4 colF;    // plain faces: the float palette index where the top row meets x = 0, its step across, its step down
  ivec4 t0;     // the texture's terms: u, u per 8 pixels, u per row, v
  ivec4 t1;     //                      v per 8 pixels, v per row, w, w per 8 pixels
  ivec4 t2;     //                      w per row; then the face's alpha, its colour, its bias
  vec4 key;     // the depth key where the first drawn row meets x = 0, its step across, its step down, that row
  vec4 depth;   // 1/z as a plane over the screen (the depth-buffer diagnostic and the old ground stopgap)
  ivec4 box;    // the box the fragment stage is run over: min x, min y, max x + 1, max y + 1
  ivec3 px;     // the rounded corners (the plain-triangles diagnostic)
  ivec3 py;
};

// Whether a triangle on these screen corners (floats, unrounded) draws anything: its corners rounded as the triangle
// routines round them, some area on whole pixels, some of its box on the screen. The one decision setupTriangle makes,
// asked first there, and all the record path's count asks (11/18/2026), so both decide alike by construction. Out: the
// rounded corners, twice the area (the routines' determinant), and the box the fragment stage is run over (min x, min
// y, max x + 1, max y + 1: the top corner's row is the least y, the bottom's the greatest).
bool coverage(vec2 f[3], out int x[3], out int y[3], out int det, out ivec4 box) {
  for (int i = 0; i < 3; i++) {
    precise float rx = f[i].x + 0.5;
    precise float ry = f[i].y + 0.5;
    x[i] = int(rx);
    y[i] = int(ry);
  }
  det = (x[1] - x[0]) * (y[2] - y[0]) - (x[2] - x[0]) * (y[1] - y[0]);
  if (det == 0) return false;  // no area once on whole pixels
  int minX = max(0, min(x[0], min(x[1], x[2])));
  int maxX = min(viewSize.x - 1, max(x[0], max(x[1], x[2])));
  int minY = max(0, min(y[0], min(y[1], y[2])));
  int maxY = min(viewSize.y, max(y[0], max(y[1], y[2]))) - 1;
  if (minX > maxX || minY > maxY) return false;
  box = ivec4(minX, minY, maxX + 1, maxY + 1);
  return true;
}

// a[i] for i 0 to 2 by selects, not by indexing with a variable (which can put the array in the card's slower local
// memory instead of its registers); the same value either way
int pick3(int a[3], int i) {
  return i == 0 ? a[0] : i == 1 ? a[1] : a[2];
}

float pick3(float a[3], int i) {
  return i == 0 ? a[0] : i == 1 ? a[1] : a[2];
}

vec2 pick3(vec2 a[3], int i) {
  return i == 0 ? a[0] : i == 1 ? a[1] : a[2];
}

bool covers(vec2 f0, vec2 f1, vec2 f2) {
  vec2 f[3] = vec2[3](f0, f1, f2);
  int x[3], y[3], det;
  ivec4 box;
  return coverage(f, x, y, det, box);
}

// One triangle: f the screen corners as floats (unrounded), s their depth keys, C their colours (hsl, or a textured
// face's lightness). The triangle routines round the corners; the depth key's steps come from the unrounded ones.
// False if nothing of it is drawn (no area on whole pixels, or off the screen: coverage).
bool setupTriangle(vec2 f0, vec2 f1, vec2 f2, int c0, int c1, int c2, float s0, float s1, float s2, vec3 zc, int flags, ivec4 t0, ivec4 t1, int t40, int alpha, int bias, out Rec r) {
  vec2 f[3] = vec2[3](f0, f1, f2);
  float s[3] = float[3](s0, s1, s2);
  int x[3], y[3], det;
  ivec4 box;
  if (!coverage(f, x, y, det, box)) return false;
  int C[3] = int[3](c0, c1, c2);

  int s01 = y[0] != y[1] ? idiv((x[1] - x[0]) << 14, y[1] - y[0]) : 0;
  int s12 = y[1] != y[2] ? idiv((x[2] - x[1]) << 14, y[2] - y[1]) : 0;
  int s02 = y[0] != y[2] ? idiv((x[2] - x[0]) << 14, y[2] - y[0]) : 0;

  // the top corner, the middle one, and which edge is on the left
  int t, m, b;
  bool longLeft;
  if (y[0] <= y[1] && y[0] <= y[2]) {
    t = 0;
    if (y[1] < y[2]) { m = 1; b = 2; longLeft = (y[0] != y[1] && s02 < s01) || (y[0] == y[1] && s02 > s12); }
    else { m = 2; b = 1; longLeft = !((y[0] != y[2] && s02 < s01) || (y[0] == y[2] && s12 > s01)); }
  } else if (y[1] <= y[2]) {
    t = 1;
    if (y[2] < y[0]) { m = 2; b = 0; longLeft = (y[1] != y[2] && s01 < s12) || (y[1] == y[2] && s01 > s02); }
    else { m = 0; b = 2; longLeft = !(s01 < s12); }
  } else {
    t = 2;
    if (y[0] < y[1]) { m = 0; b = 1; longLeft = s12 < s02; }
    else { m = 1; b = 0; longLeft = !(s12 < s02); }
  }
  int sTB = (t + b) == 1 ? s01 : (t + b) == 3 ? s12 : s02;
  int sTM = (t + m) == 1 ? s01 : (t + m) == 3 ? s12 : s02;
  int sMB = (m + b) == 1 ? s01 : (m + b) == 3 ? s12 : s02;
  // the top, middle and bottom corners' values
  int xt = pick3(x, t), xm = pick3(x, m), yt = pick3(y, t), ym = pick3(y, m), yb = pick3(y, b), Ct = pick3(C, t);
  vec2 ft = pick3(f, t);
  float st = pick3(s, t);
  // the colour: textured faces the client's int fixed point (<< 9) from float steps; plain faces a float palette index
  float X0 = float(x[0]), X1 = float(x[1]), X2 = float(x[2]), Y0 = float(y[0]), Y1 = float(y[1]), Y2 = float(y[2]);
  float fdet = float(det);
  float dc1 = float(C[1] - C[0]), dc2 = float(C[2] - C[0]);
  precise float ax = dc1 * (Y2 - Y0) - dc2 * (Y1 - Y0);
  precise float ay = dc2 * (X1 - X0) - dc1 * (X2 - X0);
  ivec4 col = ivec4(sMB, 0, 0, 0);
  vec4 colF = vec4(0.0);
  if ((flags & 2) != 0) {
    precise float axs = ax * 512.0;
    precise float ays = ay * 512.0;
    int gxI = int(fdiv(axs, fdet));
    int gyI = int(fdiv(ays, fdet));
    col = ivec4(sMB, (Ct << 9) - gxI * xt + gxI, gxI, gyI);
  } else {
    float gx = fdiv(ax, fdet), gy = fdiv(ay, fdet);
    precise float seed = float(Ct) - gx * float(xt) + gx;
    colF = vec4(seed, gx, gy, 0.0);
  }

  // the depth key: steps from the unrounded corners, seeded at the top corner (the client's cj)
  precise float kd = (f[1].x - f[0].x) * (f[2].y - f[0].y) - (f[2].x - f[0].x) * (f[1].y - f[0].y);
  precise float kax = (s[1] - s[0]) * (f[2].y - f[0].y) - (s[2] - s[0]) * (f[1].y - f[0].y);
  precise float kay = (s[2] - s[0]) * (f[1].x - f[0].x) - (s[1] - s[0]) * (f[2].x - f[0].x);
  float sx = fdiv(kax, kd), sy = fdiv(kay, kd);
  precise float key = st - sx * ft.x + sx;
  if (ft.y < 0.0) {
    key -= sy * float(int(ft.y));
  }
  precise float frac = ft.y - float(yt);
  key -= sy * frac;
  vec4 keyF = vec4(key, sx, sy, float(max(yt, 0)));

  ivec4 rows = ivec4(yt, ym, yb, (longLeft ? 1 : 0) | flags);
  ivec4 edge = ivec4(xt, xm, sTB, sTM);
  ivec4 tt2 = ivec4(t40, alpha, (flags & 2) != 0 ? C[0] & 127 : C[0], bias);  // w: the face's bias (the bias view)

  // 1/z over the screen (the depth-buffer diagnostic and the old ground stopgap)
  vec3 iz = vec3(1.0 / max(zc.x, 1.0), 1.0 / max(zc.y, 1.0), 1.0 / max(zc.z, 1.0));
  float dax = float(x[1] - x[0]), day = float(y[1] - y[0]), dbx = float(x[2] - x[0]), dby = float(y[2] - y[0]);
  float ad = iz.y - iz.x, bd = iz.z - iz.x;
  float dA = (ad * dby - bd * day) / fdet, dB = (bd * dax - ad * dbx) / fdet;
  vec4 depth = vec4(dA, dB, iz.x - dA * float(x[0]) - dB * float(y[0]), 0.0);

  r.rows = rows;
  r.edge = edge;
  r.col = col;
  r.colF = colF;
  r.t0 = t0;
  r.t1 = t1;
  r.t2 = tt2;
  r.key = keyF;
  r.depth = depth;
  r.box = box;
  r.px = ivec3(x[0], x[1], x[2]);
  r.py = ivec3(y[0], y[1], y[2]);
  return true;
}

// A face: its three corners relative to the camera, their colour words (alpha << 24 | marks | colour), its texture's
// number + 1 (0 none) and bias, its texture's P, M, N relative to the camera. Up to two triangles (a face cut by the
// near plane can leave four corners): how many, in r0 and r1. The callers have already left out fully see-through faces
// and those of other passes. full false: only how many (the record path's count), every decision made by the same
// code (the corners, the facing tests, the near cut, coverage) and the rest of the setup left out; r0 and r1 unset.
int setupFace(vec3 v[3], int ahsl[3], int texPlus1, int bias, vec3 tp[3], bool full, out Rec r0, out Rec r1) {
  corner k[3];
  int col[3];
  for (int i = 0; i < 3; i++) {
    k[i] = toCorner(v[i]);
    col[i] = ahsl[i] & 0xffff;
  }
  int alpha = (ahsl[0] >> 24) & 0xff;
  float bias2 = float(bias * 2);

  int texId = texPlus1 - 1;
  int flags = 0;
  ivec4 t0 = ivec4(0), t1 = ivec4(0);
  int t40 = 0;
  bool tile = (ahsl[0] & 0x100000) != 0;
  if (full && texId >= 0) {
    textureTerms(toCorner(tp[0]).ci, toCorner(tp[1]).ci, toCorner(tp[2]).ci, tile, t0, t1, t40);
    flags = 2 | (texId << 8);
  }
  flags |= ((ahsl[0] >> 20) & 12) | (tile ? 16 : 0);  // 4 the ground, 8 never hidden by it, 16 a ground tile

  bool inside[3] = bool[3](k[0].cam.z >= 50.0, k[1].cam.z >= 50.0, k[2].cam.z >= 50.0);
  float key[3] = float[3](0.0, 0.0, 0.0);
  if (full) {
    for (int i = 0; i < 3; i++) {
      precise float zb = k[i].cam.z - bias2;
      key[i] = depthKey(zb);
    }
  }
  if (inside[0] && inside[1] && inside[2]) {
    // the client's test for a face turned away, on its float screen corners
    precise float facing = (k[0].screen.x - k[1].screen.x) * (k[2].screen.y - k[1].screen.y) - (k[2].screen.x - k[1].screen.x) * (k[0].screen.y - k[1].screen.y);
    if (!(facing > 0.0)) return 0;
    if (!full) return covers(k[0].screen, k[1].screen, k[2].screen) ? 1 : 0;
    return setupTriangle(k[0].screen, k[1].screen, k[2].screen, col[0], col[1], col[2], key[0], key[1], key[2], vec3(k[0].cam.z, k[1].cam.z, k[2].cam.z), flags, t0, t1, t40, alpha, bias, r0) ? 1 : 0;
  }
  if (!inside[0] && !inside[1] && !inside[2]) return 0;

  // a face across the plane z = 50: the client's test in camera space (its ints), then its cut at z = 50: each corner
  // in turn, an outside one giving where its edges to the previous and the next corner cross the plane
  ivec3 c[3] = ivec3[3](k[0].ci, k[1].ci, k[2].ci);
  int ax = c[0].x - c[1].x, bx = c[2].x - c[1].x, ay = c[0].y - c[1].y, by = c[2].y - c[1].y, az = c[0].z - c[1].z, bz = c[2].z - c[1].z;
  int nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
  if (!(c[1].x * nx + c[1].y * ny + c[1].z * nz > 0)) return 0;
  vec2 sp[4];
  int sc[4];
  float sk[4];
  float sz[4];
  int n = 0;
  for (int i = 0; i < 3; i++) {
    if (inside[i]) {
      sp[n] = k[i].screen; sc[n] = col[i]; sk[n] = key[i]; sz[n] = k[i].cam.z; n++;
      continue;
    }
    int nb[2] = int[2]((i + 2) % 3, (i + 1) % 3);
    for (int j = 0; j < 2; j++) {
      int o = nb[j];
      if (!inside[o] || n >= 4) continue;
      int fct = (65536 / (c[o].z - c[i].z)) * (50 - c[i].z);
      int cx = c[i].x + ((c[o].x - c[i].x) * fct >> 16);
      int cy = c[i].y + ((c[o].y - c[i].y) * fct >> 16);
      sp[n] = vec2(float(centerX + idiv(zoom * cx, 50)), float(centerY + idiv(zoom * cy, 50)));
      sc[n] = col[i] + ((col[o] - col[i]) * fct >> 16);
      sk[n] = full ? depthKey(50.0 - bias2) : 0.0;
      sz[n] = 50.0;
      n++;
    }
  }
  // the two triangles in the order the client draws them; whichever of them has something to draw, in that order
  if (!full) {
    return (n >= 3 && covers(sp[0], sp[1], sp[2]) ? 1 : 0) + (n == 4 && covers(sp[0], sp[2], sp[3]) ? 1 : 0);
  }
  int count = 0;
  if (n >= 3 && setupTriangle(sp[0], sp[1], sp[2], sc[0], sc[1], sc[2], sk[0], sk[1], sk[2], vec3(sz[0], sz[1], sz[2]), flags, t0, t1, t40, alpha, bias, r0)) {
    count = 1;
  }
  if (n == 4) {
    Rec second;
    if (setupTriangle(sp[0], sp[2], sp[3], sc[0], sc[2], sc[3], sk[0], sk[2], sk[3], vec3(sz[0], sz[2], sz[3]), flags, t0, t1, t40, alpha, bias, second)) {
      if (count == 0) {
        r0 = second;
      } else {
        r1 = second;
      }
      count++;
    }
  }
  return count;
}
