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

#include "to_screen.glsl"

/*
 * Rotate a vertex by a given orientation in JAU: the client's own way (claude/cpu-look.md, "Read from today's
 * client", follow-up 1): the stored vertex cast to an int (toward zero), then turned in floats with the client's
 * float tables (Perspective.SINEF, 2,048 steps), only if there is an orientation
 */
layout(std430, binding = 7) readonly buffer sincos_in {
  vec2 sincos[];  // sine, cosine for each of the 2048 steps of a turn, as the client's floats
};

vec4 rotate_vertex(vec4 vertex, int orientation) {
  float x = float(int(vertex.x)), y = float(int(vertex.y)), z = float(int(vertex.z));
  if ((orientation & 2047) == 0) {
    return vec4(x, y, z, vertex.w);
  }
  vec2 sc = sincos[orientation & 2047];
  precise float nx = z * sc.x + x * sc.y;
  precise float nz = z * sc.y - x * sc.x;
  return vec4(nx, y, nz, vertex.w);
}

/*
 * Calculate the distance to a vertex given the camera angle
 */
float distance(vec4 vertex, float cameraYaw, float cameraPitch) {
  float yawSin = sin(cameraYaw);
  float yawCos = cos(cameraYaw);

  float pitchSin = sin(cameraPitch);
  float pitchCos = cos(cameraPitch);

  float j = vertex.z * yawCos - vertex.x * yawSin;
  float l = vertex.y * pitchSin + j * pitchCos;

  return l;
}

/*
 * Calculate the distance to a face
 */
int face_distance(vec4 vA, vec4 vB, vec4 vC, float cameraYaw, float cameraPitch) {
  float dvA = distance(vA, cameraYaw, cameraPitch);
  float dvB = distance(vB, cameraYaw, cameraPitch);
  float dvC = distance(vC, cameraYaw, cameraPitch);
  float faceDistance = (dvA + dvB + dvC) / 3;
  return int(faceDistance);
}

/*
 * Test if a face is visible (not backward facing)
 */
bool face_visible(vec3 vA, vec3 vB, vec3 vC, ivec4 position) {
  // Move model to scene location, and account for camera offset
  vec3 cameraPos = vec3(cameraX, cameraY, cameraZ);

  vec3 lA = vA + position.xyz - cameraPos;
  vec3 lB = vB + position.xyz - cameraPos;
  vec3 lC = vC + position.xyz - cameraPos;

  vec3 sA = toScreen(lA, cameraYaw, cameraPitch, centerX, centerY, zoom);
  vec3 sB = toScreen(lB, cameraYaw, cameraPitch, centerX, centerY, zoom);
  vec3 sC = toScreen(lC, cameraYaw, cameraPitch, centerX, centerY, zoom);

  return (sA.x - sB.x) * (sC.y - sB.y) - (sC.x - sB.x) * (sA.y - sB.y) > 0;
}
