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

// The CPU look, per pixel: is this pixel the triangle's (the CPU's fill rule), and what colour the
// CPU gives it (its 4-pixel bricks from its palette; its 8-pixel texture spans). Every term is the
// CPU's integer maths, worked out for this one pixel from the row's own start. Checked against a
// plain row-by-row loop: tools/release-kit/prototypes/cpu-look/check.js (the same maths in a browser).
#include frag_header

#include texture_config

#include "cpu_acc.glsl"

layout(std140) uniform uniforms {
  float cameraYaw;
  float cameraPitch;
  int centerX;
  int centerY;
  int zoom;
  float cameraX;
  float cameraY;
  float cameraZ;
  ivec4 cameraPos;
  ivec4 cameraRot;   // yaw sin, yaw cos, pitch sin, pitch cos at 16,384ths of a turn, in 65536ths (the model centre)
  vec4 cameraRotF;   // the same as the client's floats (Perspective.SINEF14): every corner's turn
};

uniform sampler2D palette;        // 65536 colours, 256 × 256: the CPU's palette at the client's brightness
uniform sampler2DArray textures;  // the game's textures at the client's brightness, 128 × 128
uniform ivec2 viewSize;
uniform int cpuFlags;             // 1 bricks, 2 bands, 4 texture spans, 8 depth buffer, 16 plain triangles (diagnostics), 32 the CPU's depth key, 64 the bias view
uniform int groundPass;           // "the ground hides what's under it": 1 the ground alone, into the depth buffer; 2 everything
uniform float groundAllowance;    // how far behind the ground (scene units) scenery still shows
uniform vec2 textureAnimations[TEXTURE_COUNT];
uniform int tick;
// See-through faces as the CPU draws them (11/19/2026, read from the client: claude/cpu-look.md, section 9, "The
// CPU's two passes"): its rasterizer draws the scene twice, every solid face first (writing the pixel and its depth
// key where its key is greater), then the see-through faces in the game's order, each blending where its key is
// greater than the solid one's and leaving the key alone. So the plugin's two passes are the CPU's own: no face order
// is kept between them. (Stage A4, 11/18/2026, kept each pixel's solid winner and dropped a see-through face handed
// over before it, as a CPU drawing in one pass would; the client's doesn't.)

// "Players' own faces: Both fixed" (11/19/2026, Bitcrush's own, not the CPU's look; claude/cpu-look.md, section 9): a
// render mode 2 model's colour pass keeps a face only where it lies within painterBand game units of the model's own
// nearest face at that pixel (its greatest key, drawn alone into painterDepth just before, in the same encoding as
// gl_FragDepth below); 0 off
uniform float painterBand;
uniform sampler2D painterDepth;

// a key's camera depth (the key's formula turned round; the face's bias still in it)
float keyDepth(float k) {
  return 150.75377 / ((k - 1.0100503) * 1.0100503) + 75.0;
}

// fog (11/18/2026, Jace: "I like my max draw distance being softened by fog rather than a hard cut"): the GPU plugin's,
// not the CPU's (which has none); 0 tiles is off, the CPU renderer's hard edge
uniform int fogDepth;        // in tiles
uniform int drawDistance;    // in tiles
uniform int expandedChunks;  // Extended map loading
uniform vec3 fogColor;       // the sky's

#ifdef RECORDS
// the record path (setup_comp.glsl): the triangle's setup read from its record, not handed on by every corner
flat in int fRec;
layout(std430, binding = 4) readonly buffer records_in {
  ivec4 recs[];
};
ivec4 fRows, fEdge, fCol, fT0, fT1, fT2;
vec4 fColF, fKey, fDepth;
void loadRecord() {
  int b = fRec * 8;
  fRows = recs[b];
  fEdge = recs[b + 1];
  fCol = recs[b + 2];
  fColF = vec4(intBitsToFloat(fCol.y), intBitsToFloat(fCol.z), intBitsToFloat(fCol.w), 0.0);
  fT0 = recs[b + 3];
  fT1 = recs[b + 4];
  fT2 = recs[b + 5];
  fKey = intBitsToFloat(recs[b + 6]);
  // the depth-buffer diagnostic and the old ground stopgap draw by the geometry stage; w: the middle-to-bottom edge's
  // start, in the record's last slot
  fDepth = vec4(0.0, 0.0, 0.0, intBitsToFloat(recs[b + 7].w));
}
#else
flat in ivec4 fRows, fEdge, fCol, fT0, fT1, fT2;
flat in vec4 fColF;  // plain faces: the float palette index where the top row meets x = 0, its steps across and down
flat in vec4 fKey;   // the depth key where the first drawn row meets x = 0, its steps across and down, that row
flat in vec4 fDepth;
#endif

layout(location = 0) out vec4 FragColor;

int idiv(int a, int b) {
  int q = int(uint(abs(a)) / uint(abs(b)));
  return (a < 0) != (b < 0) ? -q : q;
}

vec3 pal(int i) {
  return texelFetch(palette, ivec2(i & 255, (i >> 8) & 255), 0).rgb;
}

ivec2 uvAt(int U, int V, int W) {
  int w = W >> 14;
  if (w == 0) return ivec2(0);
  return ivec2(clamp(idiv(U, w), 0, 16256), idiv(V, w));
}

// How much fog a pixel takes: RuneLite 1.11.26's GPU plugin's fog (its vert.glsl: how far a point is from the edge of
// the drawn square, the camera's square clipped to the loaded scene, its corners rounded off; full fog at the edge,
// none fogDepth tiles in), worked out for the pixel's own point in the world instead of at each corner: its depth from
// the CPU's depth key (the key's formula turned round, the face's bias put back), its place across from the screen and
// that depth, then the camera's turn undone (cpu_turn.glsl's, backwards) and the camera's place added.
float fogAmount(int px, int py, float key) {
  if (!(key > 1.0100503)) {
    return 1.0;  // as far as the key reaches
  }
  float z = 150.75377 / ((key - 1.0100503) * 1.0100503) + 75.0 + 2.0 * float(fT2.w);
  float xc = (float(px) - float(centerX)) * z / float(zoom);
  float yc = (float(py) - float(centerY)) * z / float(zoom);
  float sy = cameraRotF.x, cy = cameraRotF.y, sp = cameraRotF.z, cp = cameraRotF.w;
  float z1 = cp * z - sp * yc;
  float east = cy * xc - sy * z1 + float(cameraPos.x);
  float north = sy * xc + cy * z1 + float(cameraPos.z);
  const float TILE = 128.0, ROUND = 1.5, ROUND2 = ROUND * ROUND;
  float edgeMin = (-float(expandedChunks) * 8.0 + 1.0) * TILE;
  float edgeMax = (104.0 + float(expandedChunks) * 8.0 - 1.0) * TILE;
  float reach = float(drawDistance) * TILE;
  // (the client draws one less tile to the north and east than to the south and west)
  float west = max(edgeMin, float(cameraPos.x) - reach), eastEdge = min(edgeMax, float(cameraPos.x) + reach - TILE);
  float south = max(edgeMin, float(cameraPos.z) - reach), northEdge = min(edgeMax, float(cameraPos.z) + reach - TILE);
  float xd = min(east - west, eastEdge - east), zd = min(north - south, northEdge - north);
  float nearest = min(xd, zd), second = max(xd, zd);
  float d = nearest - ROUND * TILE * max(0.0, (nearest + ROUND2) / (second + ROUND2));
  return 1.0 - clamp(d / (float(fogDepth) * TILE), 0.0, 1.0);
}

void main() {
#ifdef RECORDS
  loadRecord();
#endif
  int px = int(gl_FragCoord.x), py = viewSize.y - 1 - int(gl_FragCoord.y);
  // depth as 50 / z (nearer is larger). The depth-buffer diagnostic: what's in front decided per pixel, not by the
  // game's order. The ground hides what's under it: the ground's own depth first (pass 1), then everything in the
  // game's order (pass 2), scenery drawn only where it's no farther than the ground plus the allowance; the ground
  // itself and players, NPCs, items and projectiles always drawn
  float iz = fDepth.x * float(px) + fDepth.y * float(py) + fDepth.z;
  int yt = fRows.x, ym = fRows.y, yb = fRows.z, fl = fRows.w;
  // the row's two ends, as today's client walks a triangle (11/19/2026, claude/cpu-look-stage-b.md, "the client walks a
  // triangle's edges in floats"): each edge from its start, its slope added once a row in floats (cpu_acc.glsl), the
  // left end truncated up to the right one; then the depth key from the row's own start, a float addition a pixel
  int x1 = px, x2 = px + 1;
  if ((cpuFlags & 16) == 0) {
    if (py < yt || py >= yb) discard;
    // the two edges this row: the long one, and the top-to-middle or the middle-to-bottom one (picked first, so one
    // walk serves every pixel whichever part of the triangle it is in), the left and the right of them
    bool top = py < ym, longLeft = (fl & 1) != 0;
    float oStart = top ? intBitsToFloat(fEdge.y) : fDepth.w, oSlope = intBitsToFloat(top ? fEdge.w : fCol.x);
    int nLong = py - yt, nO = top ? py - yt : py - ym;
    float lStart = longLeft ? intBitsToFloat(fEdge.x) : oStart, lSlope = longLeft ? intBitsToFloat(fEdge.z) : oSlope;
    float rStart = longLeft ? oStart : intBitsToFloat(fEdge.x), rSlope = longLeft ? oSlope : intBitsToFloat(fEdge.z);
    int lN = longLeft ? nLong : nO, rN = longLeft ? nO : nLong;
    // a first look by one multiplication each: n roundings of a sum, each under a unit in the 24th bit of the
    // largest value on the way, keep the walk's sum within lTol (rTol) of it. A pixel clear of either end by more
    // goes now; the right end is walked only for a pixel near it (11/20/2026: the walk's cost)
    float lGuess = lStart + lSlope * float(lN), rGuess = rStart + rSlope * float(rN);
    float lTol = accTol(lStart, lGuess, lN), rTol = accTol(rStart, rGuess, rN);
    float fx = float(px);
    if (lGuess - lTol > fx + 1.0 || rGuess + rTol < fx) discard;
    // an end's whole number is the guess's wherever no whole number lies within its reach; else the walk decides
    x1 = lGuess + lTol < 0.0 ? 0 : sureInt(lGuess, lTol) ? int(lGuess) : max(int(cpu_acc(lStart, lSlope, lN)), 0);
    if (px < x1) discard;
    // (a textured ground tile's row steps evenly across its whole length, so it needs the right end always)
    if (fx + 1.0 >= rGuess - rTol || ((fl & 18) == 18 && (cpuFlags & 4) != 0)) {
      x2 = min(sureInt(rGuess, rTol) ? int(rGuess) : int(cpu_acc(rStart, rSlope, rN)), viewSize.x);
      if (px >= x2) discard;
    }
  }
  precise float keyRow = cpu_acc(fKey.x, fKey.z, py - int(fKey.w));
  precise float keyStart = keyRow + fKey.y * float(x1);
  float key = cpu_acc(keyStart, fKey.y, px - x1);
  if ((cpuFlags & 32) != 0 && (cpuFlags & 8) == 0 && groundPass == 0) {
    // the CPU's own depth test (claude/cpu-look.md, "Read from today's client", question 4): the key, linear over the
    // screen, the greatest kept, a key of 0 or less never kept. Written as a depth the hardware compares exactly the
    // way the CPU compares keys: a positive float's bits order as its value, so the key's bits, shifted down into
    // the depth range's own floats, keep every key distinct and in order (from 2^-8 to 2^56; keys past either end,
    // which no corner beyond z = 75 has, share the end)
    if (!(key > 0.0)) discard;
    if (painterBand > 0.0) {
      uint stored = floatBitsToUint(texelFetch(painterDepth, ivec2(gl_FragCoord.xy), 0).r) + 0x3B000000u;
      if (keyDepth(key) > keyDepth(uintBitsToFloat(stored)) + painterBand) discard;
    }
    uint kb = clamp(floatBitsToUint(key), 0x3B800000u, 0x5B7FFFFFu) - 0x3B000000u;
    gl_FragDepth = uintBitsToFloat(kb);
  } else if ((cpuFlags & 8) != 0 || groundPass == 1) {
    gl_FragDepth = clamp(iz * 50.0, 0.0, 1.0);
  } else if (groundPass == 2) {
    gl_FragDepth = (fRows.w & 12) != 0 || iz <= 0.0 ? 1.0 : clamp(50.0 / max(1.0 / iz - groundAllowance, 1.0), 0.0, 1.0);
  } else {
    gl_FragDepth = 0.5;
  }
  if ((cpuFlags & 16) != 0) {
    // plain triangles (diagnostic): one flat colour a face, the hardware's own fill
    FragColor = vec4(pal(fT2.z), 1.0 - float(fT2.y) / 255.0);
    return;
  }
  int d = px - x1;
  int start = fCol.y + fCol.w * (py - yt) + x1 * fCol.z;  // a textured face's shade where the row starts
  vec3 rgb;
  if ((fl & 2) == 0) {
    // a float palette index, as today's client: the row's seed added to once a row, the row's start, then four
    // pixels' step added once a 4-pixel brick, each a float addition; (int) c & 65535 the brick's lookup
    int j = (cpuFlags & 1) != 0 ? d >> 2 : d;
    float cStep = (cpuFlags & 1) != 0 ? fColF.y * 4.0 : fColF.y;
    // the lookup's whole number from a first look where none lies within its reach (the row's walk, the start's
    // rounding and the bricks' walk, each bounded as an edge's); else the walk itself
    float rowG = fColF.x + fColF.z * float(py - yt), startG = rowG + fColF.y * float(x1), cG = startG + cStep * float(j);
    float cTol = float(py - yt + j + 8) * (abs(fColF.x) + abs(rowG) + abs(startG) + abs(cG) + 1.0) * 2.4e-7;
    float c = cG;
    if (!(sureInt(cG, cTol) && (cpuFlags & 2) != 0)) {
      precise float row = cpu_acc(fColF.x, fColF.z, py - yt);
      precise float rowStart = row + fColF.y * float(x1);
      c = cpu_acc(rowStart, cStep, j);
    }
    int i0 = int(c) & 65535;
    if ((cpuFlags & 2) != 0) {
      rgb = pal(i0);
    } else {
      rgb = mix(pal(i0), pal((i0 & 127) == 127 ? i0 : i0 + 1), clamp(c - floor(c), 0.0, 1.0));
    }
  } else {
    int Ua = fT0.x + fT0.z * (py - centerY) + (x1 - centerX) * (fT0.y >> 3);
    int Va = fT0.w + fT1.y * (py - centerY) + (fT1.x >> 3) * (x1 - centerX);
    int Wa = fT1.z + fT2.x * (py - centerY) + (x1 - centerX) * (fT1.w >> 3);
    int texel, shade;
    if ((cpuFlags & 4) != 0 && (fl & 16) != 0) {
      // a ground tile (the CPU's drawTexturedTile and its row): the texture's point at both ends of the row, then
      // even steps across the whole row (no 8-pixel spans, no clamp); the shade steps every 8 pixels
      int n = x2 - x1, e = x1 - centerX;
      int Ut = fT0.x + fT0.z * (py - centerY) + e * fT0.y;
      int Vt = fT0.w + fT1.y * (py - centerY) + fT1.x * e;
      int Wt = fT1.z + fT2.x * (py - centerY) + e * fT1.w;
      int w0 = Wt >> 14, w1 = (Wt + n * fT1.w) >> 14;
      int u0 = w0 != 0 ? idiv(Ut, w0) : 0, v0 = w0 != 0 ? idiv(Vt, w0) : 0;
      int u1 = w1 != 0 ? idiv(Ut + n * fT0.y, w1) : 0, v1 = w1 != 0 ? idiv(Vt + fT1.x * n, w1) : 0;
      int val = (u0 << 18) + v0 + d * (idiv(v1 - v0, n) + (idiv(u1 - u0, n) << 18));
      texel = (val & 16256) + int(uint(val) >> 25u);
      shade = (start + (d >> 3) * (fCol.z << 3)) >> 8;
    } else if ((cpuFlags & 4) != 0) {
      int s = d >> 3, j = d & 7;
      int Us = Ua + s * fT0.y, Vs = Va + s * fT1.x, Ws = Wa + s * fT1.w;
      ivec2 a = uvAt(Us, Vs, Ws);
      ivec2 b = uvAt(Us + fT0.y, Vs + fT1.x, Ws + fT1.w);
      int pk = (a.x << 18) + a.y;
      int stp = ((b.y - a.y) >> 3) + (((b.x - a.x) >> 3) << 18);
      int val = pk + j * stp;
      texel = (val & 16256) + int(uint(val) >> 25u);
      shade = (start + s * (fCol.z << 3)) >> 8;
    } else {
      ivec2 a = uvAt(Ua + d * (fT0.y >> 3), Va + d * (fT1.x >> 3), Wa + d * (fT1.w >> 3));
      texel = (a.y & 16256) + (a.x >> 7);
      shade = (start + d * fCol.z) >> 8;
    }
    int texId = fl >> 8;
    ivec2 anim = ivec2(floor(textureAnimations[min(texId, TEXTURE_COUNT - 1)] * float(tick)));
    vec4 tc = texelFetch(textures, ivec3((texel + anim.x) & 127, ((texel >> 7) + anim.y) & 127, texId), 0);
    if (tc.a < 0.5) discard;  // a texel of 0: not drawn
    ivec3 tx = ivec3(tc.rgb * 255.0 + 0.5);
    rgb = vec3((tx * shade >> 8) & 255) / 255.0;
  }
  if ((cpuFlags & 64) != 0) {
    // the bias view (Diagnostics): faces with no bias dimmed, a bias of 1 to 12 in reds (brighter for more), over 12
    // in magenta: which faces the CPU's depth key lifts toward the camera, and by how much (twice the bias, in units);
    // a negative bias, which pushes the face away, in blues (-1 to -12), under -12 in cyan
    int b = fT2.w;
    rgb = b == 0 ? rgb * 0.3 : b < -12 ? vec3(0.0, 1.0, 1.0) : b < 0 ? vec3(0.0, 0.0, 0.35 - 0.65 * float(b) / 12.0)
      : b <= 12 ? vec3(0.35 + 0.65 * float(b) / 12.0, 0.0, 0.0) : vec3(1.0, 0.0, 1.0);
  }
  if (fogDepth > 0) {
    rgb = mix(rgb, fogColor, fogAmount(px, py, key));
  }
  FragColor = vec4(rgb, 1.0 - float(fT2.y) / 255.0);
}
