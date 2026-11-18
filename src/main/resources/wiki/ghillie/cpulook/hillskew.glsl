/*
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

// Scenery bent to the slope it stands on (hill skew; the includer declares tileHeightSampler at binding 2).
// The CPU look: in whole units as the client does it for the CPU (each corner raised by the ground's height under
// it, between the tile's four corners, each step floored by >> 7), not in floats, so corners and the texture's
// points land where the CPU's do (a unit off moves a texture by a texel).

int tile_height(int z, int x, int y) {
#define ESCENE_OFFSET 40  // (184-104)/2
  return texelFetch(tileHeightSampler, ivec3(x + ESCENE_OFFSET, y + ESCENE_OFFSET, z), 0).r << 3;
}

vec4 hillskew_vertexf(vec4 v, int hillskew, int y, int plane) {
  if (hillskew == 1) {
    int px = int(v.x), pz = int(v.z);
    int fx = px & 127, fz = pz & 127;
    int sx = px >> 7, sz = pz >> 7;
    int h0 = (tile_height(plane, sx, sz) * (128 - fx) + tile_height(plane, sx + 1, sz) * fx) >> 7;
    int h1 = (tile_height(plane, sx, sz + 1) * (128 - fx) + tile_height(plane, sx + 1, sz + 1) * fx) >> 7;
    int h = (h0 * (128 - fz) + h1 * fz) >> 7;
    return vec4(v.x, float(int(v.y) + h - y), v.z, v.w);
  }
  return v;
}

// The same for a corner the compute pass writes relative to the camera (11/18/2026): rv the corner turned for the
// object's facing, rel the object's place less the camera's, place the object's place in the scene. The ground is
// looked up where the corner is in the scene (rv + place, as before the corners were made camera-relative); only the
// height moves, by whole units, so the result is exactly rv + rel with the skew added.
vec4 hillskew_rel(vec4 rv, vec4 rel, vec4 place, int hillskew, int y, int plane) {
  vec4 v = rv + rel;
  if (hillskew == 1) {
    vec4 w = rv + place;
    v.y += hillskew_vertexf(w, 1, y, plane).y - w.y;
  }
  return v;
}
