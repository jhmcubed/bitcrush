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

#ifndef CPU_TURN_GLSL
#define CPU_TURN_GLSL
// The client's camera turn for every corner (its Model.draw and its tiles; claude/cpu-look.md, "Read from today's
// client", follow-up 1): the corner given relative to the camera, turned by the yaw and then the pitch in floats,
// in the client's order. precise: no fused multiply-adds, so each product and sum rounds as Java's floats do.
// Needs cameraRotF (yaw sin, yaw cos, pitch sin, pitch cos) from the includer.
vec3 cpu_turn(vec3 v) {
  precise float x1 = v.z * cameraRotF.x + v.x * cameraRotF.y;
  precise float z1 = v.z * cameraRotF.y - v.x * cameraRotF.x;
  precise float y2 = v.y * cameraRotF.w - z1 * cameraRotF.z;
  precise float z2 = v.y * cameraRotF.z + z1 * cameraRotF.w;
  return vec3(x1, y2, z2);
}
#endif
