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

// A face's corners read and turned for the model's orientation (11/19/2026: read again where they're needed instead of
// kept in registers through the sort, every face of a thread's, which crowded the big tiers' registers: his Prifddinas
// timings had the 1024 x 2 and 1024 x 4 tiers at 2.2 ms for 720 models; the same arithmetic, so the same bytes:
// ShaderCheck's golden copy)
void load_face(uint localId, modelinfo minfo, out vert o1, out vert o2, out vert o3) {
  int size = minfo.size;
  int offset = minfo.offset;
  int flags = minfo.flags;
  uint ssboOffset = localId < size ? localId : 0;
  vert thisA, thisB, thisC;
  if (flags < 0) {
    thisA = vb[offset + ssboOffset * 3];
    thisB = vb[offset + ssboOffset * 3 + 1];
    thisC = vb[offset + ssboOffset * 3 + 2];
  } else {
    thisA = tempvb[offset + ssboOffset * 3];
    thisB = tempvb[offset + ssboOffset * 3 + 1];
    thisC = tempvb[offset + ssboOffset * 3 + 2];
  }
  if (localId < size && (flags & CAM_SPACE) != 0) {
    // a model on a boat: its corners already in camera space, as the client's matrix leaves them (CAM_SPACE)
    o1 = thisA;
    o2 = thisB;
    o3 = thisC;
  } else if (localId < size) {
    int orientation = flags & 0x7ff;
    o1 = vert(rotate_vertex(vec4(thisA.pos, 0), orientation).xyz, thisA.ahsl);
    o2 = vert(rotate_vertex(vec4(thisB.pos, 0), orientation).xyz, thisB.ahsl);
    o3 = vert(rotate_vertex(vec4(thisC.pos, 0), orientation).xyz, thisC.ahsl);
  } else {
    o1 = vert(vec3(0), 0);
    o2 = vert(vec3(0), 0);
    o3 = vert(vec3(0), 0);
  }
}

// what the sort keeps of a face through its barriers: its priority, its distance, and whether it faces the camera
// (face_visible, for the priorities' averages)
void face_key(uint localId, modelinfo minfo, out int prio, out int dis, out bool visible) {
  vert a, b, c;
  load_face(localId, minfo, a, b, c);
  if (localId < minfo.size) {
    prio = (a.ahsl >> 16) & 0xf;  // all vertices on the face have the same priority
    if ((minfo.flags & CAM_SPACE) != 0) {
      // a model on a boat (fo.mi, then bi): each corner's depth is (int) of its camera z less the model origin's, which
      // the client takes from the same matrix, (int) of its z (CpuLookPlugin's boat upload puts it in y)
      dis = cpu_tdiv((int(a.pos.z) - minfo.y) + (int(b.pos.z) - minfo.y) + (int(c.pos.z) - minfo.y), 3);
      visible = face_visible_cam(a.pos, b.pos, c.pos);
    } else {
      dis = cpu_face_distance(vec4(a.pos, 0), vec4(b.pos, 0), vec4(c.pos, 0), ivec3(minfo.x, minfo.y, minfo.z));
      visible = face_visible(a.pos, b.pos, c.pos, ivec4(minfo.x, minfo.y, minfo.z, 0));
    }
    // The CPU's order of a model's faces (its model draw, fo.java 1.13.7, read 11/19/2026 for the ToA fire's core): only
    // render mode 2 models (players, most NPCs: the painter mark, bit 29) are drawn with their faces' priorities (ei(true),
    // bt). Every other model's solid faces go in their own order, by index (the solid pass, mode 3, bq), and its
    // see-through faces by depth alone, the farthest first, at the same depth the lower index first (the see-through
    // pass, mode 1, ei(false)): one stretch, the solid faces first (each list keeps only its own faces' order)
    if (((minfo.flags >> 29) & 1) == 0) {
      prio = 0;
      if (((a.ahsl >> 24) & 0xff) == 0) {
        dis = 32767;
      }
    }
  } else {
    prio = 0;
    dis = 0;
    visible = false;
  }
}

void add_face_prio_distance(uint localId, modelinfo minfo, bool visible, int thisPriority, int thisDistance) {
  if (localId < minfo.size) {
    // if the face is not culled, it is calculated into priority distance averages
    if (visible) {
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

#ifdef SKIP_EMPTY
uniform ivec2 viewSize;

// A corner's screen point by the card's own division (not the setup's exact one, fdiv.glsl), and how far it may be
// from the exact one: the card's quotient is within a few rounding steps of the true one (GLSL allows 2.5), here
// allowed a hundred, and the sum's rounding on top.
vec2 roughScreen(vec3 k, out float margin) {
  precise float px = k.x * float(zoom);
  precise float py = k.y * float(zoom);
  float qx = px / k.z;
  float qy = py / k.z;
#ifdef ROUGH_SKEW
  // ShaderCheck only: a card whose division is this far off (ROUGH_SKEW times the quotient), to show the margin
  // covers it; and with ROUGH_NO_MARGIN, that the skip check catches a margin too small
  qx += qx * ROUGH_SKEW;
  qy -= qy * ROUGH_SKEW;
#endif
  margin = 1e-5 * (abs(qx) + abs(qy) + abs(float(centerX)) + abs(float(centerY)) + 1.0);
#ifdef ROUGH_NO_MARGIN
  margin = 0.0;
#endif
  return vec2(float(centerX) + qx, float(centerY) + qy);
}

// a rounded corner's coordinate as the setup gets it (int of the point + 0.5), when the rough point leaves no doubt
// which whole number that is: no whole number within the margin of it
bool roundsSurely(float f, float margin, out int v) {
  float r = f + 0.5;
  v = int(r);
  float fr = fract(r);
  return abs(r) < 4.0e6 && min(fr, 1.0 - fr) > margin + 1e-6 * abs(r);
}

// Whether a face surely draws nothing (11/19/2026: the compute pass then writes only a mark, alpha 255, in place of
// its corners, and the count, the geometry stage and the painter's draw all skip it on that mark): turned away by a
// clear margin, or with all three corners leaving no doubt where they round to, rounded to no area or off the screen.
// Asked of the corners exactly as the compute pass writes them, turned exactly as the setup turns them (cpu_turn), so
// only the screen points differ, by the card's division, and the margins cover that many times over: a face is
// never skipped that the setup (setup.glsl's setupFace) would draw (ShaderCheck's skip check). Faces across the near
// plane are left to the setup.
bool surelyEmpty(vec3 a, vec3 b, vec3 c, bool cam) {
  vec3 k0 = cam ? a : cpu_turn(a), k1 = cam ? b : cpu_turn(b), k2 = cam ? c : cpu_turn(c);
  if (!(k0.z >= 50.0 && k1.z >= 50.0 && k2.z >= 50.0)) {
    return false;
  }
  float m0, m1, m2;
  vec2 s0 = roughScreen(k0, m0), s1 = roughScreen(k1, m1), s2 = roughScreen(k2, m2);
  // the setup's facing test, (s0 - s1) x (s2 - s1) > 0 to be drawn, here with all its error allowed against it
  float ax = s0.x - s1.x, ay = s0.y - s1.y, cx = s2.x - s1.x, cy = s2.y - s1.y;
  float e01 = m0 + m1, e21 = m2 + m1;
  float facing = ax * cy - cx * ay;
  float slack = 2.0 * (e01 * (abs(cx) + abs(cy)) + e21 * (abs(ax) + abs(ay)) + 2.0 * e01 * e21) + 1e-5 * (abs(ax * cy) + abs(cx * ay));
#ifdef ROUGH_NO_MARGIN
  slack = 0.0;
#endif
  if (facing + slack <= 0.0) {
    return true;
  }
  // the setup's coverage test (setup.glsl's coverage) on the very corners it rounds to
  int x0, y0, x1, y1, x2, y2;
  if (!(roundsSurely(s0.x, m0, x0) && roundsSurely(s0.y, m0, y0) && roundsSurely(s1.x, m1, x1) && roundsSurely(s1.y, m1, y1)
      && roundsSurely(s2.x, m2, x2) && roundsSurely(s2.y, m2, y2))) {
    return false;
  }
  int det = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
  if (det == 0) {
    return true;
  }
  int minX = max(0, min(x0, min(x1, x2)));
  int maxX = min(viewSize.x - 1, max(x0, max(x1, x2)));
  int minY = max(0, min(y0, min(y1, y2)));
  int maxY = min(viewSize.y, max(y0, max(y1, y2))) - 1;
  return minX > maxX || minY > maxY;
}
#endif

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

void sort_and_insert(uint localId, modelinfo minfo, int thisPriority, int thisDistance) {
  int size = minfo.size;

  if (localId < size) {
    vert thisrvA, thisrvB, thisrvC;
    load_face(localId, minfo, thisrvA, thisrvB, thisrvC);
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
    bool cam = (flags & CAM_SPACE) != 0;
    vec4 vertA = cam ? vec4(thisrvA.pos, 0) : hillskew_rel(vec4(thisrvA.pos, 0), pos, place, hillskew, minfo.y, plane);
    vec4 vertB = cam ? vec4(thisrvB.pos, 0) : hillskew_rel(vec4(thisrvB.pos, 0), pos, place, hillskew, minfo.y, plane);
    vec4 vertC = cam ? vec4(thisrvC.pos, 0) : hillskew_rel(vec4(thisrvC.pos, 0), pos, place, hillskew, minfo.y, plane);

    // the CPU look, for "the ground hides what's under it": grass and other ground decorations marked as the ground
    // (bit 22), players, NPCs, items and projectiles as never hidden (bit 23)
    int mark = ((flags & (1 << 28)) != 0 ? 0x800000 : 0) | ((flags & (1 << 27)) != 0 ? 0x400000 : 0)
      | ((flags & (1 << 29)) != 0 ? 0x200000 : 0)  // render mode 2 (geom.glsl's painterPass)
      | ((flags & (1 << 30)) != 0 ? 0x80000 : 0)  // the CPU's solid pass only: never its see-through faces
      | (cam ? 0x40000 : 0);  // already in camera space: the setup doesn't turn it (setup.glsl's toCorner)
    // (the face's bias rides on in its texture slot, uv.x's bits 9-16: SceneUploader.pushModel)
    thisrvA.ahsl &= ~0xff0000;
    thisrvB.ahsl &= ~0xff0000;
    thisrvC.ahsl &= ~0xff0000;

#ifdef SKIP_EMPTY
    // a face that surely draws nothing (fully see-through, or surelyEmpty): only the mark that says so
    if (((thisrvA.ahsl >> 24) & 0xff) == 255 || surelyEmpty(vertA.xyz, vertB.xyz, vertC.xyz, cam)) {
      vout[outOffset + myOffset * 3].ahsl = int(0xff000000u);
      return;
    }
#endif

    // write to out buffer
    vout[outOffset + myOffset * 3] = vert(vertA.xyz, thisrvA.ahsl | mark);
    vout[outOffset + myOffset * 3 + 1] = vert(vertB.xyz, thisrvB.ahsl | mark);
    vout[outOffset + myOffset * 3 + 2] = vert(vertC.xyz, thisrvC.ahsl | mark);

    if (toffset < 0) {
      uvout[outOffset + myOffset * 3] = vec4(0);
      uvout[outOffset + myOffset * 3 + 1] = vec4(0);
      uvout[outOffset + myOffset * 3 + 2] = vec4(0);
    } else if (cam) {
      // the texture's points in camera space too (the boat upload turned them with the corners)
      uvout[outOffset + myOffset * 3] = temptexb[toffset + localId * 3];
      uvout[outOffset + myOffset * 3 + 1] = temptexb[toffset + localId * 3 + 1];
      uvout[outOffset + myOffset * 3 + 2] = temptexb[toffset + localId * 3 + 2];
    } else {
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
      uvout[outOffset + myOffset * 3] = texA.wxyz;
      uvout[outOffset + myOffset * 3 + 1] = texB.wxyz;
      uvout[outOffset + myOffset * 3 + 2] = texC.wxyz;
    }
  }
}
