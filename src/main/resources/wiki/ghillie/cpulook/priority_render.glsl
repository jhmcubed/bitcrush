/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
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

layout(binding = 2) uniform isampler3D tileHeightSampler;

#include "cpu_turn.glsl"
#include "cpu_depth.glsl"

// Calculate adjusted priority for a face with a given priority, distance, and
// model global min10 and face distance averages. This allows positioning faces
// with priorities 10/11 into the correct 'slots' resulting in 18 possible
// adjusted priorities
int priority_map(int p, int distance, int _min10, int avg1, int avg2, int avg3) {
  // (10, 11)  0  1  2  (10, 11)  3  4  (10, 11)  5  6  7  8  9  (10, 11)
  //   0   1   2  3  4    5   6   7  8    9  10  11 12 13 14 15   16  17
  switch (p) {
    case 0:
      return 2;
    case 1:
      return 3;
    case 2:
      return 4;
    case 3:
      return 7;
    case 4:
      return 8;
    case 5:
      return 11;
    case 6:
      return 12;
    case 7:
      return 13;
    case 8:
      return 14;
    case 9:
      return 15;
    case 10:
      if (distance > avg1) {
        return 0;
      } else if (distance > avg2) {
        return 5;
      } else if (distance > avg3) {
        return 9;
      } else {
        return 16;
      }
    case 11:
      if (distance > avg1 && _min10 > avg1) {
        return 1;
      } else if (distance > avg2 && (_min10 > avg1 || _min10 > avg2)) {
        return 6;
      } else if (distance > avg3 && (_min10 > avg1 || _min10 > avg2 || _min10 > avg3)) {
        return 10;
      } else {
        return 17;
      }
    default:
      // this can't happen unless an invalid priority is sent. just assume 0.
      return 0;
  }
}

// calculate the number of faces with a lower adjusted priority than
// the given adjusted priority
int count_prio_offset(int priority) {
  // this shouldn't ever be outside of (0, 17) because it is the return value from priority_map
  priority = clamp(priority, 0, 17);
  int total = 0;
  for (int i = 0; i < priority; i++) {
    total += totalMappedNum[i];
  }
  return total;
}

void get_face(uint localId, modelinfo minfo, float cameraYaw, float cameraPitch, out int prio, out int dis, out vert o1, out vert o2, out vert o3) {
  int size = minfo.size;
  int offset = minfo.offset;
  int flags = minfo.flags;
  uint ssboOffset;

  if (localId < size) {
    ssboOffset = localId;
  } else {
    ssboOffset = 0;
  }

  vert thisA;
  vert thisB;
  vert thisC;

  // Grab triangle vertices from the correct buffer
  if (flags < 0) {
    thisA = vb[offset + ssboOffset * 3];
    thisB = vb[offset + ssboOffset * 3 + 1];
    thisC = vb[offset + ssboOffset * 3 + 2];
  } else {
    thisA = tempvb[offset + ssboOffset * 3];
    thisB = tempvb[offset + ssboOffset * 3 + 1];
    thisC = tempvb[offset + ssboOffset * 3 + 2];
  }

  if (localId < size) {
    int orientation = flags & 0x7ff;

    // rotate for model orientation
    vec4 thisrvA = rotate_vertex(vec4(thisA.pos, 0), orientation);
    vec4 thisrvB = rotate_vertex(vec4(thisB.pos, 0), orientation);
    vec4 thisrvC = rotate_vertex(vec4(thisC.pos, 0), orientation);

    // calculate distance to face
    int thisPriority = (thisA.ahsl >> 16) & 0xf;  // all vertices on the face have the same priority
    // the CPU look: the CPU's own whole-unit depth (cpu_depth.glsl), not RuneLite's float one, so faces at almost the
    // same depth (a tree's canopy is full of them) come in the CPU's order
    int thisDistance = cpu_face_distance(thisrvA, thisrvB, thisrvC, ivec3(minfo.x, minfo.y, minfo.z));

    o1.pos = thisrvA.xyz;
    o1.ahsl = thisA.ahsl;

    o2.pos = thisrvB.xyz;
    o2.ahsl = thisB.ahsl;

    o3.pos = thisrvC.xyz;
    o3.ahsl = thisC.ahsl;

    prio = thisPriority;
    dis = thisDistance;
  } else {
    o1.pos = vec3(0);
    o1.ahsl = 0;

    o2.pos = vec3(0);
    o2.ahsl = 0;

    o3.pos = vec3(0);
    o3.ahsl = 0;

    prio = 0;
    dis = 0;
  }
}

void add_face_prio_distance(uint localId, modelinfo minfo, vert thisrvA, vert thisrvB, vert thisrvC, int thisPriority, int thisDistance, ivec4 pos) {
  if (localId < minfo.size) {
    // if the face is not culled, it is calculated into priority distance averages
    if (face_visible(thisrvA.pos, thisrvB.pos, thisrvC.pos, pos)) {
      atomicAdd(totalNum[thisPriority], 1);
      atomicAdd(totalDistance[thisPriority], thisDistance);

      // calculate minimum distance to any face of priority 10 for positioning the 11 faces later
      if (thisPriority == 10) {
        atomicMin(min10, thisDistance);
      }
    }
  }
}

int map_face_priority(uint localId, modelinfo minfo, int thisPriority, int thisDistance, out int prio) {
  int size = minfo.size;

  // Compute average distances for 0/2, 3/4, and 6/8

  if (localId < size) {
    int avg1 = -6000;
    int avg2 = -6000;
    int avg3 = -6000;

    if (totalNum[1] > 0 || totalNum[2] > 0) {
      avg1 = cpu_fdiv(totalDistance[1] + totalDistance[2], totalNum[1] + totalNum[2]);
    }

    if (totalNum[3] > 0 || totalNum[4] > 0) {
      avg2 = cpu_fdiv(totalDistance[3] + totalDistance[4], totalNum[3] + totalNum[4]);
    }

    if (totalNum[6] > 0 || totalNum[8] > 0) {
      avg3 = cpu_fdiv(totalDistance[6] + totalDistance[8], totalNum[6] + totalNum[8]);
    }

    int adjPrio = priority_map(thisPriority, thisDistance, min10, avg1, avg2, avg3);
    int prioIdx = atomicAdd(totalMappedNum[adjPrio], 1);

    prio = adjPrio;
    return prioIdx;
  }

  prio = 0;
  return 0;
}

void insert_face(uint localId, modelinfo minfo, int adjPrio, int distance, int prioIdx) {
  int size = minfo.size;

  if (localId < size) {
    // calculate base offset into renderPris based on number of faces with a lower priority
    int baseOff = count_prio_offset(adjPrio);
    // the furthest faces draw first, and have the highest priority.
    // if two faces have the same distance, the one with the
    // lower id draws first.
    renderPris[baseOff + prioIdx] = distance << 16 | int(~localId & 0xffffu);
  }
}

#include "hillskew.glsl"

#ifndef COUNTING_SORT
// Each adjusted priority's stretch of renderPris put in the order it's drawn (the farthest first; at the same distance
// the lower face number first), in place, by a bitonic network whose comparators all put the larger key at the lower
// index, so a stretch needn't be a power of two long: the places past its end act as the smallest keys and never
// move. Every key is different (the face number is in it), so this is exactly the order the GPU plugin's count gave
// (kept behind COUNTING_SORT for ShaderCheck to compare), in far fewer steps: each face then finds its place with a
// binary search (sort_and_insert) instead of comparing itself with every other face of its priority (11/18/2026: the
// count made the compute pass the card's largest cost zoomed out).
void sort_priorities() {
  int t = int(gl_LocalInvocationID.x);
  for (int p = 0; p < 18; ++p) {
    int n = totalMappedNum[p];
    if (n < 2) {
      continue;
    }
    int base = count_prio_offset(p);
    int size = 2;
    while (size < n) {
      size <<= 1;
    }
    int pairs = size >> 1;
    for (int k = 2; k <= size; k <<= 1) {
      // the flip: within each block of k, the i-th place against the i-th from the end
      int hk = k >> 1;
      for (int i = t; i < pairs; i += THREAD_COUNT) {
        int a = (i / hk) * k + (i % hk);
        int b = (i / hk) * k + k - 1 - (i % hk);
        if (b < n) {
          int x = renderPris[base + a], y = renderPris[base + b];
          if (y > x) {
            renderPris[base + a] = y;
            renderPris[base + b] = x;
          }
        }
      }
      memoryBarrierShared();
      barrier();
      // then the halves, quarters, ... within each block
      for (int j = k >> 2; j > 0; j >>= 1) {
        for (int i = t; i < pairs; i += THREAD_COUNT) {
          int a = (i / j) * 2 * j + (i % j);
          int b = a + j;
          if (b < n) {
            int x = renderPris[base + a], y = renderPris[base + b];
            if (y > x) {
              renderPris[base + a] = y;
              renderPris[base + b] = x;
            }
          }
        }
        memoryBarrierShared();
        barrier();
      }
    }
  }
}
#endif

void sort_and_insert(uint localId, modelinfo minfo, int thisPriority, int thisDistance, vert thisrvA, vert thisrvB, vert thisrvC) {
  int size = minfo.size;

  if (localId < size) {
    int outOffset = minfo.idx;
    int toffset = minfo.toffset;
    int flags = minfo.flags;

    // we only have to order faces against others of the same priority
    const int priorityOffset = count_prio_offset(thisPriority);
    const int numOfPriority = totalMappedNum[thisPriority];
    const int start = priorityOffset;                // index of first face with this priority
    const int end = priorityOffset + numOfPriority;  // index of last face with this priority
    const int renderPriority = thisDistance << 16 | int(~localId & 0xffffu);
    int myOffset = priorityOffset;

#ifdef COUNTING_SORT
    // calculate position this face will be in
    for (int i = start; i < end; ++i) {
      if (renderPriority < renderPris[i]) {
        ++myOffset;
      }
    }
#else
    // its place in its priority's stretch, sorted largest key first (sort_priorities): where its own key is
    int lo = start, hi = end - 1;
    while (lo < hi) {
      int mid = (lo + hi) >> 1;
      if (renderPris[mid] > renderPriority) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    myOffset = lo;
#endif

    // the CPU look: each corner relative to the camera (the object's place less the camera's, ints, added to the
    // turned corner in floats, as the client adds them); the geometry stage turns it by the camera
    // Scenery from the scene buffer comes unbent and is bent to its slope here (hill skew), the ground looked up at
    // the corner's place in the scene, not relative to the camera (hillskew_rel)
    vec4 pos = vec4(minfo.x - cameraPos.x, minfo.y - cameraPos.y, minfo.z - cameraPos.z, 0);
    vec4 place = vec4(minfo.x, minfo.y, minfo.z, 0);
    int plane = (flags >> 24) & 3;
    int hillskew = (flags >> 26) & 1;
    vec4 vertA = hillskew_rel(vec4(thisrvA.pos, 0), pos, place, hillskew, minfo.y, plane);
    vec4 vertB = hillskew_rel(vec4(thisrvB.pos, 0), pos, place, hillskew, minfo.y, plane);
    vec4 vertC = hillskew_rel(vec4(thisrvC.pos, 0), pos, place, hillskew, minfo.y, plane);

    // the CPU look, for "the ground hides what's under it": grass and other ground decorations marked as the ground
    // (bit 22), players, NPCs, items and projectiles as never hidden (bit 23)
    int mark = ((flags & (1 << 28)) != 0 ? 0x800000 : 0) | ((flags & (1 << 27)) != 0 ? 0x400000 : 0)
      | ((flags & (1 << 29)) != 0 ? 0x200000 : 0);  // render mode 2 (geom.glsl's painterPass)
    // (the face's bias rides on in its texture slot, uv.x's bits 9-16: SceneUploader.pushModel)
    thisrvA.ahsl &= ~0xff0000;
    thisrvB.ahsl &= ~0xff0000;
    thisrvC.ahsl &= ~0xff0000;

    // the texture slots, worked out before anything is written (the fused count needs them)
    vec4 uA = vec4(0), uB = vec4(0), uC = vec4(0);
    if (toffset >= 0) {
      vec4 texA, texB, texC;

      if (flags >= 0) {
        texA = temptexb[toffset + localId * 3];
        texB = temptexb[toffset + localId * 3 + 1];
        texC = temptexb[toffset + localId * 3 + 2];
      } else {
        texA = texb[toffset + localId * 3];
        texB = texb[toffset + localId * 3 + 1];
        texC = texb[toffset + localId * 3 + 2];
      }

      int orientation = flags & 0x7ff;
      // swizzle from (tex,x,y,z) to (x,y,z,tex) for rotate and hillskew
      texA = texA.yzwx;
      texB = texB.yzwx;
      texC = texC.yzwx;
      // rotate
      texA = rotate_vertex(texA, orientation);
      texB = rotate_vertex(texB, orientation);
      texC = rotate_vertex(texC, orientation);
      // position and hillskew, relative to the camera
      texA = hillskew_rel(texA, pos, place, hillskew, minfo.y, plane);
      texB = hillskew_rel(texB, pos, place, hillskew, minfo.y, plane);
      texC = hillskew_rel(texC, pos, place, hillskew, minfo.y, plane);
      uA = texA.wxyz;
      uB = texB.wxyz;
      uC = texC.wxyz;
    }

#ifdef FUSED_COUNT
    bool keep = countFace(outOffset / 3 + myOffset, vertA.xyz, vertB.xyz, vertC.xyz, thisrvA.ahsl | mark,
      thisrvB.ahsl | mark, thisrvC.ahsl | mark, uA, uB, uC);
#else
    bool keep = true;
#endif
    if (keep) {
      // write to out buffer
      vout[outOffset + myOffset * 3] = vert(vertA.xyz, thisrvA.ahsl | mark);
      vout[outOffset + myOffset * 3 + 1] = vert(vertB.xyz, thisrvB.ahsl | mark);
      vout[outOffset + myOffset * 3 + 2] = vert(vertC.xyz, thisrvC.ahsl | mark);
      uvout[outOffset + myOffset * 3] = uA;
      uvout[outOffset + myOffset * 3 + 1] = uB;
      uvout[outOffset + myOffset * 3 + 2] = uC;
    }
  }
}
