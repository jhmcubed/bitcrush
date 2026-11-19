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

#include version_header

#include thread_config
#include cull_config

// A thread's faces' values are kept in arrays through the barriers, read and set at a face's number i by selects:
// every index a constant, so the arrays stay in registers. (11/19/2026, his card's compiled programs: an array indexed
// by a variable sits in the card's slow local memory, and asking its compiler to write every loop out, as the record
// setup does, left the loops with the most work in them rolled and copied the rest: the 1024 x 4 tier's program over
// three times the size and slower.) a and i are names or simple expressions, v a name: each is written several times.
#if FACES_PER_THREAD == 1
#define PICK(a, i) (a[0])
#define PUT(a, i, v) { a[0] = (v); }
#elif FACES_PER_THREAD == 2
#define PICK(a, i) ((i) == 1 ? a[1] : a[0])
#define PUT(a, i, v) { a[0] = (i) == 0 ? (v) : a[0]; a[1] = (i) == 1 ? (v) : a[1]; }
#elif FACES_PER_THREAD == 4
#define PICK(a, i) ((i) == 3 ? a[3] : (i) == 2 ? a[2] : (i) == 1 ? a[1] : a[0])
#define PUT(a, i, v) { a[0] = (i) == 0 ? (v) : a[0]; a[1] = (i) == 1 ? (v) : a[1]; a[2] = (i) == 2 ? (v) : a[2]; a[3] = (i) == 3 ? (v) : a[3]; }
#elif FACES_PER_THREAD == 6
#define PICK(a, i) ((i) == 5 ? a[5] : (i) == 4 ? a[4] : (i) == 3 ? a[3] : (i) == 2 ? a[2] : (i) == 1 ? a[1] : a[0])
#define PUT(a, i, v) { a[0] = (i) == 0 ? (v) : a[0]; a[1] = (i) == 1 ? (v) : a[1]; a[2] = (i) == 2 ? (v) : a[2]; a[3] = (i) == 3 ? (v) : a[3]; a[4] = (i) == 4 ? (v) : a[4]; a[5] = (i) == 5 ? (v) : a[5]; }
#else
#error FACES_PER_THREAD is 1, 2, 4 or 6 (PICK and PUT)
#endif

shared int totalNum[12];       // number of faces with a given priority
shared int totalDistance[12];  // sum of distances to faces of a given priority

shared int totalMappedNum[18];  // number of faces with a given adjusted priority

shared int min10;                                        // minimum distance to a face of priority 10
shared int renderPris[THREAD_COUNT * FACES_PER_THREAD];  // priority for face draw order

#include "comp_common.glsl"

layout(local_size_x = THREAD_COUNT) in;

#include "common.glsl"
#include "priority_render.glsl"

void main() {
  uint groupId = gl_WorkGroupID.x;
  uint localId = gl_LocalInvocationID.x * FACES_PER_THREAD;
  modelinfo minfo = ol[groupId];
  ivec4 pos = ivec4(minfo.x, minfo.y, minfo.z, 0);

  if ((minfo.flags & FACE_ORDER) != 0) {
    // a model drawn in its faces' own order (FACE_ORDER): each face straight to its place, no keys, no sort (the same
    // for the whole workgroup, so none of the barriers below is skipped by some threads and not others)
    for (int i = 0; i < FACES_PER_THREAD; i++) {
      if (localId + i < minfo.size) {
        write_face(localId + i, minfo, int(localId) + i);
      }
    }
    return;
  }

  // render mode 2 (players, most NPCs): sorted by their faces' priorities; every other model by its faces' order and
  // its see-through faces' depths (face_key), all of it one stretch (adjusted priority 2, priority_map's for 0)
  bool painter = ((minfo.flags >> 29) & 1) != 0;

  if (localId == 0) {
    min10 = 6000;
    for (int i = 0; i < 12; ++i) {
      totalNum[i] = 0;
      totalDistance[i] = 0;
    }
    for (int i = 0; i < 18; ++i) {
      totalMappedNum[i] = 0;
    }
    if (!painter) {
      totalMappedNum[2] = minfo.size;
    }
  }

  // kept through the barriers: each face's priority, distance and facing only; its corners are read again where
  // they're written (priority_render.glsl's load_face)
  int prio[FACES_PER_THREAD];
  int dis[FACES_PER_THREAD];
  bool vis[FACES_PER_THREAD];

  for (int i = 0; i < FACES_PER_THREAD; i++) {
    int p, d;
    bool v;
    face_key(localId + i, minfo, p, d, v);
    PUT(prio, i, p);
    PUT(dis, i, d);
    PUT(vis, i, v);
  }

  int prioAdj[FACES_PER_THREAD];
  if (!painter) {
    // (11/19/2026) each face's key in its own place: no totals, no counters handing out places. The sort puts the
    // stretch in the same order whatever order the keys start in, as every key is different (the face number is in
    // it), so every face lands where the counters' places would have put it
    for (int i = 0; i < FACES_PER_THREAD; i++) {
      int two = 2;
      PUT(prioAdj, i, two);
      if (localId + i < minfo.size) {
        renderPris[localId + i] = PICK(dis, i) << 16 | int(~(localId + i) & 0xffffu);
      }
    }
    memoryBarrierShared();
    barrier();
  } else {
    memoryBarrierShared();
    barrier();

    // the totals for the averages, a run of this thread's facing faces of one priority at once
    {
      int runP = -1, runN = 0, runD = 0, runLow = 6000;
      for (int i = 0; i < FACES_PER_THREAD; i++) {
        if (localId + i < minfo.size && PICK(vis, i)) {
          int p = PICK(prio, i), d = PICK(dis, i);
          if (p != runP) {
            add_priority_run(runP, runN, runD, runLow);
            runP = p;
            runN = 0;
            runD = 0;
            runLow = 6000;
          }
          runN++;
          runD += d;
          runLow = min(runLow, d);
        }
      }
      add_priority_run(runP, runN, runD, runLow);
    }

    memoryBarrierShared();
    barrier();

    // each face's adjusted priority, and its place in that stretch: a run of this thread's faces of one adjusted
    // priority takes its places at once (any places do: the stretch is sorted next)
    int idx[FACES_PER_THREAD];
    for (int i = 0; i < FACES_PER_THREAD; i++) {
      int pa = adjusted_priority(localId + i, minfo, PICK(prio, i), PICK(dis, i));
      PUT(prioAdj, i, pa);
    }
    for (int i = 0; i < FACES_PER_THREAD; i++) {
      int pa = PICK(prioAdj, i), at = 0;
      if (localId + i < minfo.size) {
        if (i > 0 && pa == PICK(prioAdj, i - 1)) {
          at = PICK(idx, i - 1) + 1;
        } else {
          int n = 1;
          for (int j = i + 1; j < FACES_PER_THREAD; j++) {
            if (localId + j < minfo.size && PICK(prioAdj, j) == pa && n == j - i) {
              n++;
            }
          }
          at = atomicAdd(totalMappedNum[pa], n);
        }
      }
      PUT(idx, i, at);
    }

    memoryBarrierShared();
    barrier();

    for (int i = 0; i < FACES_PER_THREAD; i++) {
      insert_face(localId + i, minfo, PICK(prioAdj, i), PICK(dis, i), PICK(idx, i));
    }

    memoryBarrierShared();
    barrier();
  }

#ifndef COUNTING_SORT
  sort_priorities();
#endif

  for (int i = 0; i < FACES_PER_THREAD; i++) {
    sort_and_insert(localId + i, minfo, PICK(prioAdj, i), PICK(dis, i));
  }
}
