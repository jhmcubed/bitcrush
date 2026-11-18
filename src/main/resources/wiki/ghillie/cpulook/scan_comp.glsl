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

// The record path's scan (setup_comp.glsl says what for): each face's counts of triangles (in list A and list B) turned
// into its place in its list, keeping the faces' order. Phase 0, one workgroup a block of 4,096 faces: the places within
// the block, in place of the counts, and the block's sums. Phase 1, one workgroup: each block's first place, the lists'
// totals, and the draw commands for the record draws (list A's, then list B's after it), cut short if the records
// don't fit (flagged, for the plugin to grow the buffer), and the write's dispatch, a thread a record. Phase 2, a thread
// a face: each of its records' face noted at the record's place (its count is the next face's place less its own), so
// the write sets up only faces that draw (11/18/2026 evening).
#include version_header

layout(local_size_x = 1024) in;

uniform int phase;
uniform int faceCount;
uniform int blockCount;
uniform int capacity;

layout(std430, binding = 2) buffer counts_buffer {
  uvec2 counts[];
};
layout(std430, binding = 3) buffer blocks_buffer {
  uvec4 totals;
  uvec2 blockPlaces[];
};
layout(std430, binding = 5) writeonly buffer commands_out {
  uvec4 commands[3];  // glDrawArraysIndirect's (vertices, instances, first, base instance), list A's and B's; then
                      // glDispatchComputeIndirect's for the write (workgroups x, y, z)
};

layout(std430, binding = 6) writeonly buffer faces_of {
  uint faceOf[];
};

#define SCAN_BLOCK 4096

shared uvec2 sums[1024];

void main() {
  int t = int(gl_LocalInvocationID.x);
  if (phase == 2) {
    int f = int(gl_GlobalInvocationID.x);
    if (f >= faceCount) {
      return;
    }
    uvec2 p = counts[f] + blockPlaces[f / SCAN_BLOCK];
    uvec2 next = f + 1 < faceCount ? counts[f + 1] + blockPlaces[(f + 1) / SCAN_BLOCK] : totals.xy;
    uvec2 n = next - p;
    for (uint k = 0u; k < n.x; k++) {
      uint at = p.x + k;
      if (at < totals.w) {
        faceOf[at] = uint(f) | (k << 31);
      }
    }
    for (uint k = 0u; k < n.y; k++) {
      uint at = totals.x + p.y + k;
      if (at < totals.w) {
        faceOf[at] = uint(f) | (k << 31);
      }
    }
    return;
  }
  if (phase == 0) {
    int base = int(gl_WorkGroupID.x) * SCAN_BLOCK + t * 4;
    uvec2 c[4];
    uvec2 run = uvec2(0u);
    for (int i = 0; i < 4; i++) {
      c[i] = base + i < faceCount ? counts[base + i] : uvec2(0u);
      run += c[i];
    }
    sums[t] = run;
    barrier();
    // inclusive sums across the workgroup (Hillis and Steele)
    for (int d = 1; d < 1024; d <<= 1) {
      uvec2 add = t >= d ? sums[t - d] : uvec2(0u);
      barrier();
      sums[t] += add;
      barrier();
    }
    uvec2 place = sums[t] - run;
    for (int i = 0; i < 4; i++) {
      if (base + i < faceCount) {
        counts[base + i] = place;
      }
      place += c[i];
    }
    if (t == 1023) {
      blockPlaces[gl_WorkGroupID.x] = sums[1023];  // the block's sum, for phase 1
    }
    return;
  }
  // phase 1: the blocks' sums into their first places (up to 2,048 blocks, two a thread)
  uvec2 s0 = t * 2 < blockCount ? blockPlaces[t * 2] : uvec2(0u);
  uvec2 s1 = t * 2 + 1 < blockCount ? blockPlaces[t * 2 + 1] : uvec2(0u);
  uvec2 run = s0 + s1;
  sums[t] = run;
  barrier();
  for (int d = 1; d < 1024; d <<= 1) {
    uvec2 add = t >= d ? sums[t - d] : uvec2(0u);
    barrier();
    sums[t] += add;
    barrier();
  }
  uvec2 place = sums[t] - run;
  if (t * 2 < blockCount) {
    blockPlaces[t * 2] = place;
  }
  if (t * 2 + 1 < blockCount) {
    blockPlaces[t * 2 + 1] = place + s0;
  }
  if (t == 1023) {
    uvec2 total = sums[1023];
    uint cap = uint(capacity);
    uint a = min(total.x, cap);
    uint b = min(total.y, cap - a);
    // (list B starts at total.x; when list A is cut short, b is 0, so the records written are always 0 to a + b)
    totals = uvec4(total.x, total.y, (a < total.x || b < total.y) ? 1u : 0u, a + b);
    // a list cut short draws only whole faces' records that fit (setup_comp.glsl writes none that don't)
    commands[0] = uvec4(a * 6u, 1u, 0u, 0u);
    commands[1] = uvec4(b * 6u, 1u, 0u, 0u);
    commands[2] = uvec4((a + b + 255u) / 256u, 1u, 1u, 0u);
  }
}
