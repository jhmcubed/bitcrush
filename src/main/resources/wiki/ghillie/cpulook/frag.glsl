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
// See-through faces exactly as the CPU (stage A4, 11/18/2026). The CPU draws everything in the game's order in one
// pass: a solid face writes the pixel and its depth key where its key is greater; a see-through face blends where its
// key is greater but leaves the key alone, so a solid face drawn after it covers it again. So at a pixel, after the
// solid face that wins it (the earliest with the greatest key), only the see-through faces nearer than it and after it
// in the order show. The solid pass keeps each pixel's winner's place in the order (Winner, + 1); the see-through pass
// (seeThroughPass) drops a pixel of a face that came before it.
uniform int seeThroughPass;

// fog (11/18/2026, Jace: "I like my max draw distance being softened by fog rather than a hard cut"): the GPU plugin's,
// not the CPU's (which has none); 0 tiles is off, the CPU renderer's hard edge
uniform int fogDepth;        // in tiles
uniform int drawDistance;    // in tiles
uniform int expandedChunks;  // Extended map loading
uniform vec3 fogColor;       // the sky's
uniform isampler2D winners;

#ifdef RECORDS
// the record path (setup_comp.glsl): the triangle's setup read from its record, not handed on by every corner
flat in int fRec;
layout(std430, binding = 4) readonly buffer records_in {
  ivec4 recs[];
};
ivec4 fRows, fEdge, fCol, fT0, fT1, fT2;
vec4 fColF, fKey, fDepth;
int fFace;

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
  fFace = recs[b + 7].z;
  fDepth = vec4(0.0);  // the depth-buffer diagnostic and the old ground stopgap draw by the geometry stage
}
#else
flat in int fFace;
flat in ivec4 fRows, fEdge, fCol, fT0, fT1, fT2;
flat in vec4 fColF;  // plain faces: the float palette index where the top row meets x = 0, its steps across and down
flat in vec4 fKey;   // the depth key where the first drawn row meets x = 0, its steps across and down, that row
flat in vec4 fDepth;
#endif

layout(location = 0) out vec4 FragColor;
layout(location = 1) out int Winner;

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
float fogAmount(int px, int py) {
  float key = fKey.x + fKey.z * float(py - int(fKey.w)) + fKey.y * float(px);
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
  if ((cpuFlags & 32) != 0 && (cpuFlags & 8) == 0 && groundPass == 0) {
    // the CPU's own depth test (claude/cpu-look.md, "Read from today's client", question 4): the key, linear over the
    // screen, the greatest kept, a key of 0 or less never kept. Written as a depth the hardware compares exactly the
    // way the CPU compares keys: a positive float's bits order as its value, so the key's bits, shifted down into
    // the depth range's own floats, keep every key distinct and in order (from 2^-8 to 2^56; keys past either end,
    // which no corner beyond z = 75 has, share the end)
    precise float key = fKey.x + fKey.z * float(py - int(fKey.w)) + fKey.y * float(px);
    if (!(key > 0.0)) discard;
    if (seeThroughPass == 1 && fFace + 1 <= texelFetch(winners, ivec2(gl_FragCoord.xy), 0).r) discard;
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
    Winner = fFace + 1;
    return;
  }
  int yt = fRows.x, ym = fRows.y, yb = fRows.z, fl = fRows.w;
  if (py < yt || py >= yb) discard;

  // the row's two ends
  int L = (fEdge.x << 14) + fEdge.z * (py - yt);
  int S = py < ym ? (fEdge.x << 14) + fEdge.w * (py - yt) : (fEdge.y << 14) + fCol.x * (py - ym);
  bool longLeft = (fl & 1) != 0;
  int x1 = max((longLeft ? L : S) >> 14, 0);
  int x2 = min((longLeft ? S : L) >> 14, viewSize.x);
  if (px < x1 || px >= x2) discard;

  int d = px - x1;
  int start = fCol.y + fCol.w * (py - yt) + x1 * fCol.z;  // a textured face's shade where the row starts
  vec3 rgb;
  if ((fl & 2) == 0) {
    // a float palette index, as today's client: (int) c & 65535, one lookup a 4-pixel brick
    precise float row = fColF.x + fColF.z * float(py - yt) + float(x1) * fColF.y;
    precise float c = (cpuFlags & 1) != 0 ? row + float(d >> 2) * (fColF.y * 4.0) : row + float(d) * fColF.y;
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
    // in magenta: which faces the CPU's depth key lifts toward the camera, and by how much (twice the bias, in units)
    int b = fT2.w;
    rgb = b == 0 ? rgb * 0.3 : b <= 12 ? vec3(0.35 + 0.65 * float(b) / 12.0, 0.0, 0.0) : vec3(1.0, 0.0, 1.0);
  }
  if (fogDepth > 0) {
    rgb = mix(rgb, fogColor, fogAmount(px, py));
  }
  FragColor = vec4(rgb, 1.0 - float(fT2.y) / 255.0);
  Winner = fFace + 1;
}
