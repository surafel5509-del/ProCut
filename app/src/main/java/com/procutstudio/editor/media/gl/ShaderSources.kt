package com.procutstudio.editor.media.gl

import com.procutstudio.editor.domain.model.VideoFxType

/**
 * GLSL sources shared by preview and export. Both are plain GLSL ES 1.00 so they compile on every
 * Android device that Media3 supports.
 */
internal object ShaderSources {

    /** Pass-through vertex shader matching Media3's `aFramePosition` conventions. */
    const val VERTEX = """
attribute vec4 aFramePosition;
uniform mat4 uTransformationMatrix;
uniform mat4 uTexTransformationMatrix;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = uTransformationMatrix * aFramePosition;
  vec4 texturePosition = vec4(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);
  vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;
}
"""

    const val PRECISION = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
"""

    // ------------------------------------------------------------------------------------------
    // Clip look: grade + mask + chroma key + cut-out. Mirrors GradeMath.kt step by step.
    // Output is always opaque: "transparent" pixels are mixed towards the canvas colour because
    // Media3 frames carry no alpha channel through the encoder.
    // ------------------------------------------------------------------------------------------
    val CLIP_FRAGMENT: String = PRECISION + """
uniform sampler2D uTexSampler;
uniform sampler2D uCutoutTex;
varying vec2 vTexSamplingCoord;

uniform vec2 uTexel;
uniform float uAspect;
uniform float uTime;

uniform vec3 uGain;
uniform float uContrast;
uniform float uBrightness;
uniform float uFade;
uniform float uSat;
uniform mat3 uHue;
uniform float uShadows;
uniform float uHighlights;
uniform vec3 uShadowChroma;
uniform vec3 uHighlightChroma;
uniform float uVignette;
uniform float uGrain;
uniform float uSharpen;

uniform float uMaskType;
uniform vec4 uMaskA;
uniform vec3 uMaskB;

uniform float uChromaOn;
uniform vec3 uChromaKey;
uniform vec3 uChromaParams;

uniform float uCutoutOn;
uniform vec3 uCanvas;
uniform float uOpacity;

const vec3 LUMA = vec3(0.299, 0.587, 0.114);

float hash12(vec2 p) {
  return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float dot2(vec2 v) { return dot(v, v); }

float sdHeart(vec2 p) {
  p.x = abs(p.x);
  if (p.y + p.x > 1.0) {
    return sqrt(dot2(p - vec2(0.25, 0.75))) - 0.35355338;
  }
  float m = 0.5 * max(p.x + p.y, 0.0);
  return sqrt(min(dot2(p - vec2(0.0, 1.0)), dot2(p - vec2(m, m)))) * (p.x - p.y >= 0.0 ? 1.0 : -1.0);
}

float sdStar(vec2 p, float r, float rf) {
  vec2 k1 = vec2(0.809017, -0.5877853);
  vec2 k2 = vec2(-0.809017, -0.5877853);
  p.x = abs(p.x);
  p -= 2.0 * max(dot(k1, p), 0.0) * k1;
  p -= 2.0 * max(dot(k2, p), 0.0) * k2;
  p.x = abs(p.x);
  p.y -= r;
  vec2 ba = rf * vec2(0.5877853, 0.809017) - vec2(0.0, 1.0);
  float h = clamp(dot(p, ba) / dot(ba, ba), 0.0, r);
  return length(p - ba * h) * ((p.y * ba.x - p.x * ba.y) >= 0.0 ? 1.0 : -1.0);
}

float maskAlpha(vec2 uv) {
  vec2 p0 = vec2((uv.x - uMaskA.x) * uAspect, -(uv.y - uMaskA.y));
  float c = cos(uMaskB.x);
  float s = sin(uMaskB.x);
  vec2 p = vec2(c * p0.x - s * p0.y, s * p0.x + c * p0.y);
  float w = uMaskA.z;
  float h = uMaskA.w;
  float d = -1.0;
  if (uMaskType < 1.5) {
    d = p.y;
  } else if (uMaskType < 2.5) {
    d = abs(p.y) - h * 0.5;
  } else if (uMaskType < 3.5) {
    vec2 q = p / (vec2(w, h) * 0.5);
    d = (length(q) - 1.0) * min(w, h) * 0.5;
  } else if (uMaskType < 4.5) {
    vec2 q = abs(p) - vec2(w, h) * 0.5;
    d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0);
  } else if (uMaskType < 5.5) {
    vec2 q = vec2(p.x / (w * 0.5) * 0.7, p.y / (h * 0.5) * 0.55 + 0.5);
    d = sdHeart(q) * min(w, h) * 0.5 / 0.7;
  } else {
    d = sdStar(vec2(p.x * (h / w), p.y), min(w, h) * 0.5, 0.45);
  }
  float f = uMaskB.y * 0.35 + 0.003;
  float a = 1.0 - smoothstep(-f, f, d);
  return uMaskB.z > 0.5 ? 1.0 - a : a;
}

vec2 cbcr(vec3 c) {
  return vec2(
    0.5 - 0.168736 * c.r - 0.331264 * c.g + 0.5 * c.b,
    0.5 + 0.5 * c.r - 0.418688 * c.g - 0.081312 * c.b);
}

void main() {
  vec2 uv = vTexSamplingCoord;
  vec4 src = texture2D(uTexSampler, uv);
  vec3 col = src.rgb;
  float alpha = src.a;

  if (uSharpen > 0.001) {
    vec3 n = texture2D(uTexSampler, uv + vec2(uTexel.x, 0.0)).rgb
           + texture2D(uTexSampler, uv - vec2(uTexel.x, 0.0)).rgb
           + texture2D(uTexSampler, uv + vec2(0.0, uTexel.y)).rgb
           + texture2D(uTexSampler, uv - vec2(0.0, uTexel.y)).rgb;
    col = col + (col - n * 0.25) * uSharpen * 2.0;
  }

  if (uCutoutOn > 0.5) {
    alpha *= texture2D(uCutoutTex, vec2(uv.x, 1.0 - uv.y)).a;
  }

  if (uChromaOn > 0.5) {
    float d = distance(cbcr(col), cbcr(uChromaKey));
    float edge = uChromaParams.x * 0.7;
    float a = smoothstep(edge, edge + uChromaParams.y * 0.5 + 0.0001, d);
    float spill = uChromaParams.z * (1.0 - smoothstep(edge, edge + 0.25, d));
    col = mix(col, vec3(dot(col, LUMA)), spill);
    alpha *= a;
  }

  if (uMaskType > 0.5) {
    // The mask works in a y-down coordinate system like the editor UI.
    alpha *= maskAlpha(vec2(uv.x, 1.0 - uv.y));
  }

  // ---- grade (same order as GradeMath.gradePixel)
  col *= uGain;
  col = (col - 0.5) * uContrast + 0.5 + uBrightness;
  col = col * (1.0 - 0.3 * uFade) + 0.15 * uFade;
  float l = dot(col, LUMA);
  col = vec3(l) + (col - vec3(l)) * uSat;
  col = uHue * col;
  l = clamp(dot(col, LUMA), 0.0, 1.0);
  float shadowW = 1.0 - smoothstep(0.0, 0.55, l);
  float highW = smoothstep(0.45, 1.0, l);
  col += vec3(uShadows * 0.25 * shadowW + uHighlights * 0.25 * highW);
  col += uShadowChroma * shadowW + uHighlightChroma * highW;
  col = clamp(col, 0.0, 1.0);

  if (uVignette > 0.001) {
    float dist = length(uv - 0.5) * 1.4142135;
    col *= 1.0 - uVignette * 0.85 * smoothstep(0.3, 1.0, dist);
  }
  if (uGrain > 0.001) {
    float n = hash12(vec2(uv.x * 811.0, (1.0 - uv.y) * 613.0) + vec2(floor(uTime * 24.0) * 17.0, 0.0)) - 0.5;
    col = clamp(col + vec3(n * uGrain * 0.22), 0.0, 1.0);
  }

  gl_FragColor = vec4(mix(uCanvas, col, clamp(alpha * uOpacity, 0.0, 1.0)), 1.0);
}
"""

    // ------------------------------------------------------------------------------------------
    // Timeline effects ("uber shader"). One section per VideoFxType; intensities are packed in
    // eight vec4 uniforms (uFx0..uFx7) so even low-end GPUs have enough uniform slots.
    // ------------------------------------------------------------------------------------------
    const val FX_VECTOR_COUNT = 8

    private fun fxDefines(): String = buildString {
        VideoFxType.entries.forEachIndexed { index, type ->
            append("#define FX_").append(type.name).append(" uFx").append(index / 4).append('.')
                .append("xyzw"[index % 4]).append('\n')
        }
    }

    val FX_FRAGMENT: String by lazy { PRECISION + fxDefines() + FX_BODY }

    fun fxUniformVectorName(index: Int): String = "uFx$index"

    private const val FX_BODY = """
uniform sampler2D uTexSampler;
varying vec2 vTexSamplingCoord;
uniform vec4 uFx0;
uniform vec4 uFx1;
uniform vec4 uFx2;
uniform vec4 uFx3;
uniform vec4 uFx4;
uniform vec4 uFx5;
uniform vec4 uFx6;
uniform vec4 uFx7;
uniform float uTime;
uniform float uAspect;
uniform vec2 uTexel;

const vec3 LUMA = vec3(0.299, 0.587, 0.114);

float hash11(float p) { return fract(sin(p * 127.1) * 43758.5453); }
float hash12(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }

vec3 samp(vec2 u) { return texture2D(uTexSampler, clamp(u, 0.001, 0.999)).rgb; }

float lum(vec2 u) { return dot(samp(u), LUMA); }

vec3 hueShift(vec3 c, float a) {
  float cs = cos(a);
  float sn = sin(a);
  return vec3(
    dot(c, vec3(0.213 + cs * 0.787 - sn * 0.213, 0.715 - cs * 0.715 - sn * 0.715, 0.072 - cs * 0.072 + sn * 0.928)),
    dot(c, vec3(0.213 - cs * 0.213 + sn * 0.143, 0.715 + cs * 0.285 + sn * 0.140, 0.072 - cs * 0.072 - sn * 0.283)),
    dot(c, vec3(0.213 - cs * 0.213 - sn * 0.787, 0.715 - cs * 0.715 + sn * 0.715, 0.072 + cs * 0.928 + sn * 0.072)));
}

vec3 ring(vec2 uv, float radius) {
  vec2 r = vec2(radius / uAspect, radius);
  vec3 acc = vec3(0.0);
  for (int i = 0; i < 8; i++) {
    float a = float(i) * 0.7853982;
    acc += samp(uv + vec2(cos(a), sin(a)) * r);
  }
  return acc * 0.125;
}

void main() {
  vec2 uv = vTexSamplingCoord;
  vec2 uv0 = uv;
  float t = uTime;
  vec2 ctr = vec2(0.5, 0.5);

  // ---------------------------------------------------------------- geometry
  if (FX_ZOOM_PULSE > 0.001) {
    float beat = pow(0.5 + 0.5 * sin(t * 12.5664 - 1.5708), 3.0);
    uv = (uv - ctr) / (1.0 + 0.14 * beat * FX_ZOOM_PULSE) + ctr;
  }
  if (FX_HEARTBEAT > 0.001) {
    float ph = fract(t * 1.2);
    float beat = smoothstep(0.0, 0.08, ph) * (1.0 - smoothstep(0.08, 0.25, ph))
               + 0.6 * smoothstep(0.30, 0.38, ph) * (1.0 - smoothstep(0.38, 0.55, ph));
    uv = (uv - ctr) / (1.0 + 0.09 * beat * FX_HEARTBEAT) + ctr;
  }
  if (FX_SHAKE > 0.001) {
    float f = floor(t * 24.0);
    uv += (vec2(hash11(f), hash11(f + 31.0)) - 0.5) * 0.04 * FX_SHAKE;
  }
  if (FX_JITTER > 0.001) {
    float f = floor(t * 18.0);
    float row = floor(uv.y * 70.0);
    float on = step(0.82, hash12(vec2(row, f)));
    uv.x += (hash12(vec2(row * 1.7, f + 9.0)) - 0.5) * 0.04 * FX_JITTER * on;
  }
  if (FX_GLITCH > 0.001) {
    float f = floor(t * 10.0);
    float band = floor(uv.y * 16.0);
    float on = step(0.82, hash12(vec2(band, f)));
    uv.x += (hash12(vec2(band, f + 7.0)) - 0.5) * 0.3 * FX_GLITCH * on;
  }
  if (FX_VHS > 0.001) {
    float roll = floor(t * 14.0);
    uv.x += sin(uv.y * 38.0 + t * 3.0) * 0.0018 * FX_VHS
          + (hash12(vec2(floor(uv.y * 120.0), roll)) - 0.5) * 0.012 * FX_VHS * step(0.93, hash12(vec2(floor(uv.y * 30.0), roll)));
  }
  if (FX_WAVE > 0.001) {
    uv.y += sin(uv.x * 14.0 + t * 4.0) * 0.02 * FX_WAVE;
    uv.x += sin(uv.y * 10.0 + t * 3.0) * 0.014 * FX_WAVE;
  }
  if (FX_RIPPLE > 0.001) {
    vec2 p = (uv - ctr) * vec2(uAspect, 1.0);
    float r = length(p);
    vec2 dir = p / (r + 0.0001);
    uv += dir * sin(r * 45.0 - t * 7.0) * 0.012 * FX_RIPPLE * (1.0 - smoothstep(0.0, 0.9, r)) / vec2(uAspect, 1.0);
  }
  if (FX_FISHEYE > 0.001) {
    vec2 p = (uv - ctr) * vec2(uAspect, 1.0);
    float r2 = dot(p, p);
    p *= 1.0 - FX_FISHEYE * 0.9 * (0.25 - min(r2, 0.25));
    p = p / (1.0 + FX_FISHEYE * 0.55 * r2);
    uv = p / vec2(uAspect, 1.0) + ctr;
  }
  if (FX_BULGE > 0.001) {
    vec2 p = (uv - ctr) * vec2(uAspect, 1.0);
    float r = length(p);
    float k = 1.0 - smoothstep(0.0, 0.55, r);
    p *= 1.0 - 0.6 * FX_BULGE * k * k;
    uv = p / vec2(uAspect, 1.0) + ctr;
  }
  if (FX_MIRROR > 0.001) {
    uv.x = 0.5 - abs(uv.x - 0.5);
  }
  if (FX_KALEIDOSCOPE > 0.001) {
    vec2 p = (uv - ctr) * vec2(uAspect, 1.0);
    float ang = atan(p.y, p.x) + t * 0.15;
    float r = length(p);
    float seg = 1.0471976;
    ang = abs(mod(ang, seg) - seg * 0.5);
    vec2 q = vec2(cos(ang), sin(ang)) * r;
    uv = mix(uv, q / vec2(uAspect, 1.0) + ctr, min(FX_KALEIDOSCOPE * 1.5, 1.0));
  }
  if (FX_PIXELATE > 0.001) {
    float cells = mix(220.0, 26.0, FX_PIXELATE);
    vec2 grid = vec2(cells * uAspect, cells);
    uv = (floor(uv * grid) + 0.5) / grid;
  }
  uv = clamp(uv, 0.001, 0.999);

  // ---------------------------------------------------------------- sampling
  float split = FX_RGB_SPLIT * 0.014 + FX_GLITCH * 0.007 + FX_VHS * 0.0045;
  vec3 col;
  float tiltAmt = FX_TILT_SHIFT * smoothstep(0.1, 0.5, abs(uv.y - 0.5));
  float blurAmt = FX_BLUR * 0.02 + tiltAmt * 0.018;
  if (blurAmt > 0.0006) {
    col = samp(uv) * 0.2 + (ring(uv, blurAmt * 0.5) + ring(uv, blurAmt)) * 0.4;
  } else if (split > 0.0006) {
    col = vec3(samp(uv + vec2(split, 0.0)).r, samp(uv).g, samp(uv - vec2(split, 0.0)).b);
  } else {
    col = samp(uv);
  }
  if (FX_ZOOM_BLUR > 0.001) {
    vec3 acc = vec3(0.0);
    for (int i = 0; i < 10; i++) {
      float k = 1.0 - FX_ZOOM_BLUR * 0.12 * float(i) / 9.0;
      acc += samp(ctr + (uv - ctr) * k);
    }
    col = mix(col, acc * 0.1, min(FX_ZOOM_BLUR * 1.4, 1.0));
  }

  // ---------------------------------------------------------------- colour & overlays
  if (FX_SOFT_GLOW > 0.001) {
    vec3 soft = (ring(uv, 0.012) + ring(uv, 0.03)) * 0.5;
    vec3 bright = max(soft - vec3(0.5), vec3(0.0));
    col = mix(col, soft, FX_SOFT_GLOW * 0.35) + bright * FX_SOFT_GLOW * 1.3;
  }
  if (FX_INVERT > 0.001) {
    col = mix(col, vec3(1.0) - col, FX_INVERT);
  }
  if (FX_DIGITAL_NOISE > 0.001) {
    float f = floor(t * 24.0);
    float n = hash12(uv * vec2(uAspect * 400.0, 400.0) + f);
    float scan = 0.5 + 0.5 * sin(uv.y * 900.0);
    col = mix(col, vec3(n) * (0.6 + 0.4 * scan), FX_DIGITAL_NOISE * 0.45);
    float bar = step(0.96, hash12(vec2(floor(uv.y * 24.0), f)));
    col = mix(col, vec3(1.0), bar * FX_DIGITAL_NOISE * 0.35);
  }
  if (FX_VHS > 0.001) {
    float scan = 0.88 + 0.12 * sin(uv.y * 700.0);
    float noise = hash12(vec2(uv.x * 220.0 + floor(t * 30.0), uv.y * 900.0)) - 0.5;
    float lumv = dot(col, LUMA);
    col = mix(col, vec3(lumv), 0.25 * FX_VHS);
    col = mix(col, col * scan + noise * 0.14, FX_VHS);
    float bar = smoothstep(0.0, 0.03, abs(fract(uv.y - t * 0.18) - 0.5) - 0.46);
    col += (1.0 - bar) * 0.12 * FX_VHS;
  }
  if (FX_FILM_GRAIN > 0.001) {
    float g = hash12(uv * vec2(uAspect * 700.0, 700.0) + floor(t * 24.0) * 3.7) - 0.5;
    col += g * 0.4 * FX_FILM_GRAIN;
  }
  if (FX_OLD_FILM > 0.001) {
    float lumv = dot(col, LUMA);
    vec3 sepia = vec3(lumv * 1.1, lumv * 0.92, lumv * 0.7);
    col = mix(col, sepia, 0.85 * FX_OLD_FILM);
    float f = floor(t * 12.0);
    float flick = 1.0 + (hash11(f) - 0.5) * 0.18;
    col *= mix(1.0, flick, FX_OLD_FILM);
    float scratch = step(0.992, hash12(vec2(floor(uv.x * 260.0), f)));
    col = mix(col, vec3(0.95), scratch * 0.5 * FX_OLD_FILM);
    float d = length((uv - ctr) * vec2(uAspect * 0.6 + 0.4, 1.0)) * 1.2;
    col *= 1.0 - FX_OLD_FILM * 0.7 * smoothstep(0.35, 1.0, d);
    col += (hash12(uv * 600.0 + f) - 0.5) * 0.12 * FX_OLD_FILM;
  }
  if (FX_LIGHT_LEAK > 0.001) {
    vec2 c1 = vec2(0.92 + 0.08 * sin(t * 0.7), 0.12 + 0.1 * sin(t * 0.5));
    float l1 = 1.0 - smoothstep(0.0, 0.9, length((uv - c1) * vec2(uAspect * 0.7, 1.0)));
    vec2 c2 = vec2(0.05 + 0.06 * sin(t * 0.4 + 2.0), 0.85);
    float l2 = 1.0 - smoothstep(0.0, 0.7, length((uv - c2) * vec2(uAspect * 0.7, 1.0)));
    col += (vec3(1.0, 0.45, 0.12) * l1 * l1 + vec3(1.0, 0.2, 0.4) * l2 * l2 * 0.6) * FX_LIGHT_LEAK * (0.7 + 0.3 * sin(t * 2.0));
  }
  if (FX_DISCO > 0.001) {
    vec3 shifted = hueShift(col, t * 3.0);
    col = mix(col, shifted, FX_DISCO * 0.8);
    vec3 beam = 0.5 + 0.5 * sin(vec3(0.0, 2.1, 4.2) + t * 3.0 + uv.x * 5.0);
    float sweep = 0.5 + 0.5 * sin(uv.x * 7.0 - uv.y * 3.0 + t * 4.0);
    col += beam * sweep * sweep * 0.3 * FX_DISCO;
  }
  if (FX_STROBE > 0.001) {
    float f = step(0.8, fract(t * 7.0));
    col = mix(col, vec3(1.0), f * FX_STROBE * 0.85);
    col *= 1.0 - 0.25 * FX_STROBE * (1.0 - f);
  }
  if (FX_FLASH > 0.001) {
    float f = pow(1.0 - fract(t * 1.25), 5.0);
    col = mix(col, vec3(1.0), f * FX_FLASH * 0.9);
  }
  if (FX_NEON_EDGE > 0.001) {
    vec2 dx = vec2(uTexel.x * 1.5, 0.0);
    vec2 dy = vec2(0.0, uTexel.y * 1.5);
    float gx = lum(uv + dx) - lum(uv - dx);
    float gy = lum(uv + dy) - lum(uv - dy);
    float edge = clamp(length(vec2(gx, gy)) * 4.0, 0.0, 1.0);
    vec3 glow = 0.5 + 0.5 * sin(vec3(0.0, 2.1, 4.2) + t * 2.0 + uv.x * 4.0);
    col = mix(col, col * 0.3 + glow * edge * 1.6, FX_NEON_EDGE);
  }
  if (FX_HEARTBEAT > 0.001) {
    float ph = fract(t * 1.2);
    float beat = smoothstep(0.0, 0.08, ph) * (1.0 - smoothstep(0.08, 0.25, ph));
    float d = length((uv0 - ctr) * vec2(uAspect * 0.6 + 0.4, 1.0)) * 1.3;
    col.r += FX_HEARTBEAT * beat * 0.12 * smoothstep(0.3, 1.0, d);
    col *= 1.0 - FX_HEARTBEAT * 0.45 * smoothstep(0.45, 1.05, d);
  }
  if (FX_DARK_CORNERS > 0.001) {
    float d = length((uv0 - ctr) * vec2(uAspect * 0.6 + 0.4, 1.0)) * 1.3;
    col *= 1.0 - FX_DARK_CORNERS * 1.05 * smoothstep(0.25, 0.9, d);
  }
  if (FX_SNOW > 0.001) {
    float snow = 0.0;
    for (int i = 0; i < 3; i++) {
      float sc = 9.0 + float(i) * 8.0;
      vec2 p = uv0 * vec2(uAspect, 1.0) * sc;
      p.y += t * (0.8 + float(i) * 0.5);
      p.x += sin(t * 0.7 + float(i) * 2.0) * 0.6;
      vec2 cell = floor(p);
      vec2 f = fract(p) - 0.5;
      float h = hash12(cell + float(i) * 13.0);
      vec2 off = vec2(hash12(cell + 3.0), hash12(cell + 7.0)) - 0.5;
      float flake = (1.0 - smoothstep(0.0, 0.14 + 0.05 * float(i), length(f - off * 0.5))) * step(0.45, h);
      snow += flake;
    }
    col = mix(col, vec3(1.0), clamp(snow, 0.0, 1.0) * FX_SNOW * 0.9);
  }
  if (FX_RAIN > 0.001) {
    float rain = 0.0;
    for (int i = 0; i < 2; i++) {
      float sc = 38.0 + float(i) * 26.0;
      vec2 p = vec2(uv0.x * uAspect * sc + uv0.y * 6.0, uv0.y * (3.0 + float(i)) + t * (2.6 + float(i) * 0.8));
      float cellx = floor(p.x);
      float h = hash12(vec2(cellx, float(i)));
      float streak = (1.0 - smoothstep(0.0, 0.12, abs(fract(p.x) - 0.5))) * smoothstep(0.7, 0.95, fract(p.y + h));
      rain += streak * step(0.55, h);
    }
    col = mix(col * (1.0 - 0.18 * FX_RAIN) * vec3(0.92, 0.96, 1.05), vec3(0.8, 0.88, 1.0), clamp(rain, 0.0, 1.0) * FX_RAIN * 0.6);
  }
  if (FX_SPARKLE > 0.001) {
    float glow = 0.0;
    for (int i = 0; i < 2; i++) {
      float sc = 11.0 + float(i) * 7.0;
      vec2 p = uv0 * vec2(uAspect, 1.0) * sc;
      vec2 cell = floor(p);
      vec2 f = fract(p) - 0.5;
      float h = hash12(cell + float(i) * 5.0);
      vec2 off = (vec2(hash12(cell + 1.3), hash12(cell + 4.1)) - 0.5) * 0.6;
      vec2 q = f - off;
      float tw = 0.5 + 0.5 * sin(t * (2.5 + h * 3.0) + h * 40.0);
      float plus = max(1.0 - abs(q.x) * 14.0, 0.0) * max(1.0 - abs(q.y) * 3.5, 0.0)
                  + max(1.0 - abs(q.y) * 14.0, 0.0) * max(1.0 - abs(q.x) * 3.5, 0.0);
      float core = 1.0 - smoothstep(0.0, 0.1, length(q));
      glow += (plus * 0.7 + core) * tw * tw * step(0.72, h);
    }
    col += vec3(1.0, 0.95, 0.8) * glow * FX_SPARKLE;
  }

  gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
"""
}
