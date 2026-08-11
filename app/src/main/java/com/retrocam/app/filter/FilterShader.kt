package com.retrocam.app.filter

/**
 * The single AGSL program every look runs through. Adding a filter means adding a preset entry —
 * not writing a new shader.
 *
 * Stages, in order:
 *  1. sample distortion  — VHS tracking, sync-bar displacement
 *  2. sampling           — radial chromatic aberration / directional RGB split / anaglyph
 *  3. optics             — defocus blur, then unsharp for sharpness & clarity
 *  4. tone               — exposure, brightness, contrast, highlights, shadows, fade
 *  5. colour             — temperature, tint, saturation, then the optional LUT
 *  6. bleed              — chroma smear, the analogue "colour running" look
 *  7. light              — halation, glow, light leak, film burn, flash hotspot
 *  8. video artefacts    — scanlines, sync-bar glow, digital noise
 *  9. film artefacts     — grain, dust, scratches
 * 10. vignette
 *
 * Every expensive stage sits behind a `if (amount > 0.0)` guard. The branches are uniform-valued,
 * so all pixels take the same path and the GPU stays coherent — a filter that doesn't use dust
 * pays nothing for it.
 *
 * Everything spatial is expressed relative to the frame rather than in pixels, so the preview and
 * the full-resolution capture produce the same look — that WYSIWYG property is the whole point of
 * sharing one shader between the two paths.
 */
object FilterShader {

    const val SOURCE = """
        uniform shader inputShader;
        uniform shader lutShader;
        uniform float2 resolution;
        uniform float seed;

        uniform float lutIntensity;

        // Tone and colour, all signed deltas around neutral so 0 == untouched.
        uniform float exposure;
        uniform float brightness;
        uniform float contrast;
        uniform float saturation;
        uniform float temperature;
        uniform float tint;
        uniform float highlights;
        uniform float shadows;
        uniform float fade;

        // Optics
        uniform float blurAmount;
        uniform float motionBlur;
        uniform float motionAngle;
        uniform float sharpness;
        uniform float clarity;
        uniform float chromaAmount;
        uniform float rgbShift;
        uniform float anaglyph;
        uniform float colorBleed;

        // Light
        uniform float halationAmount;
        uniform float halationRadius;
        uniform float halationThreshold;
        uniform float glowAmount;
        uniform float glowThreshold;
        uniform float leakAmount;
        uniform float3 leakColor;
        uniform float2 leakOrigin;
        uniform float leakRadius;
        uniform float burnAmount;
        uniform float flashAmount;
        uniform float flashHardness;

        // Video artefacts
        uniform float scanlineAmount;
        uniform float scanlineCount;
        uniform float syncBarAmount;
        uniform float syncBarWidth;
        uniform float syncBarShift;
        uniform float trackingAmount;
        uniform float noiseAmount;

        // Film artefacts
        uniform float grainAmount;
        uniform float grainSize;
        uniform float dustAmount;
        uniform float scratchAmount;
        uniform float vignetteAmount;

        const float LUT_GRID = 8.0;
        const float LUT_MAX = 63.0;
        const float TWO_PI = 6.2831853;
        const float3 LUMA = float3(0.2126, 0.7152, 0.0722);

        float hash(float2 p) {
            p = fract(p * float2(123.34, 456.21));
            p += dot(p, p + 45.32);
            return fract(p.x * p.y);
        }

        float3 sampleInput(float2 c) {
            return float3(inputShader.eval(c).rgb);
        }

        // Nine taps on a ring plus centre. Shared by defocus, glow and clarity so the tap budget
        // is paid once rather than per effect.
        float3 blurAt(float2 coord, float radius) {
            float d = radius * 0.7071;
            float3 sum = sampleInput(coord) * 2.0;
            sum += sampleInput(coord + float2( radius, 0.0));
            sum += sampleInput(coord + float2(-radius, 0.0));
            sum += sampleInput(coord + float2(0.0,  radius));
            sum += sampleInput(coord + float2(0.0, -radius));
            sum += sampleInput(coord + float2( d,  d));
            sum += sampleInput(coord + float2( d, -d));
            sum += sampleInput(coord + float2(-d,  d));
            sum += sampleInput(coord + float2(-d, -d));
            return sum / 10.0;
        }

        // 8x8 grid of 64x64 cells: cell index is blue, x within a cell is red, y is green.
        float3 sampleLut(float3 rgb) {
            float3 c = clamp(rgb, float3(0.0), float3(1.0));
            float blue = c.b * LUT_MAX;
            float b0 = floor(blue);
            float b1 = min(b0 + 1.0, LUT_MAX);

            float2 inner = float2(0.5) + c.rg * LUT_MAX;

            float row0 = floor(b0 / LUT_GRID);
            float row1 = floor(b1 / LUT_GRID);
            float2 p0 = float2(b0 - row0 * LUT_GRID, row0) * 64.0 + inner;
            float2 p1 = float2(b1 - row1 * LUT_GRID, row1) * 64.0 + inner;

            float3 s0 = float3(lutShader.eval(p0).rgb);
            float3 s1 = float3(lutShader.eval(p1).rgb);
            return mix(s0, s1, blue - b0);
        }

        float3 brightPass(float3 c, float threshold) {
            float l = dot(c, LUMA);
            return c * (max(l - threshold, 0.0) / max(1.0 - threshold, 0.001));
        }

        // Sparse specks on a jittered grid: position, radius and opacity all derive from the cell,
        // so nothing lines up into a visible lattice and each seed gives a different scattering.
        float dustAt(float2 uv, float density, float aspect) {
            float2 grid = float2(uv.x * aspect, uv.y) * 34.0;
            float2 cell = floor(grid);
            float2 f = fract(grid);

            float present = hash(cell + seed * 3.7);
            if (present > density * 0.32) return 0.0;

            float2 centre = float2(hash(cell + 1.7 + seed), hash(cell + 3.1 + seed));
            float radius = mix(0.035, 0.16, hash(cell + 5.3 + seed));
            float speck = 1.0 - smoothstep(0.0, radius, distance(f, centre));
            return speck * mix(0.25, 1.0, hash(cell + 7.9 + seed));
        }

        // Near-vertical hairlines with random column, width, length and lean.
        float scratchAt(float2 uv, float density) {
            float columns = 26.0;
            float col = floor(uv.x * columns);
            if (hash(float2(col, seed * 2.3)) > density * 0.30) return 0.0;

            float lean = (hash(float2(col + 11.0, seed)) - 0.5) * 0.06;
            float centre = (col + hash(float2(col + 13.0, seed))) / columns + uv.y * lean;
            float width = mix(0.0007, 0.0026, hash(float2(col + 17.0, seed)));
            float line = 1.0 - smoothstep(0.0, width, abs(uv.x - centre));

            float start = hash(float2(col + 19.0, seed)) * 0.7;
            float end = start + mix(0.15, 0.8, hash(float2(col + 23.0, seed)));
            float within = step(start, uv.y) * step(uv.y, end);

            return line * within * mix(0.15, 0.7, hash(float2(col + 29.0, seed)));
        }

        half4 main(float2 fragCoord) {
            float2 uv = fragCoord / resolution;
            float2 centered = uv - float2(0.5);
            float shortSide = min(resolution.x, resolution.y);
            float aspect = resolution.x / resolution.y;

            // --- 1. sample-coordinate distortion -----------------------------------------
            float2 sampleCoord = fragCoord;
            float syncBand = 0.0;

            if (syncBarAmount > 0.0) {
                float barPos = fract(seed * 0.13);
                float d = abs(fract(uv.y - barPos + 0.5) - 0.5);
                syncBand = 1.0 - smoothstep(0.0, max(syncBarWidth, 0.001), d);
                sampleCoord.x += syncBand * syncBarShift * resolution.x;
            }
            if (trackingAmount > 0.0) {
                // Per-scanline jitter plus a slow wave: tape weave, not a sine ripple.
                float row = floor(uv.y * 240.0);
                float jitter = (hash(float2(row, seed * 5.1)) - 0.5) * 0.6;
                float wave = sin(uv.y * 9.0 + seed * 2.0) * 0.4;
                sampleCoord.x += (jitter + wave) * trackingAmount * resolution.x * 0.01;
            }

            // --- 2. sampling: colour-channel separation ----------------------------------
            float3 color;
            if (chromaAmount > 0.0 || rgbShift > 0.0 || anaglyph > 0.0) {
                // Radial for lens aberration, horizontal for tape/anaglyph misregistration.
                float2 radial = centered * (chromaAmount * shortSide * 0.01);
                float2 lateral = float2((rgbShift + anaglyph) * shortSide * 0.01, 0.0);
                color = float3(
                    sampleInput(sampleCoord + radial + lateral).r,
                    sampleInput(sampleCoord).g,
                    sampleInput(sampleCoord - radial - lateral).b
                );
            } else {
                color = sampleInput(sampleCoord);
            }

            // --- 3. optics: defocus, then unsharp for sharpness and clarity ---------------
            if (motionBlur > 0.0) {
                // Smear along one axis. A symmetric blur here would read as defocus, not movement.
                float2 dir = float2(cos(motionAngle), sin(motionAngle)) * motionBlur * shortSide * 0.01;
                float3 m = color;
                m += sampleInput(sampleCoord + dir * 0.33);
                m += sampleInput(sampleCoord - dir * 0.33);
                m += sampleInput(sampleCoord + dir * 0.66);
                m += sampleInput(sampleCoord - dir * 0.66);
                m += sampleInput(sampleCoord + dir);
                m += sampleInput(sampleCoord - dir);
                color = m / 7.0;
            }
            if (blurAmount > 0.0) {
                color = mix(color, blurAt(sampleCoord, blurAmount * shortSide * 0.01), 0.85);
            }
            if (sharpness > 0.0) {
                float3 soft = blurAt(sampleCoord, shortSide * 0.0018);
                color += (color - soft) * sharpness * 2.0;
            }
            if (clarity != 0.0) {
                // Local contrast: unsharp at a much wider radius than sharpness uses.
                float3 wide = blurAt(sampleCoord, shortSide * 0.02);
                color += (color - wide) * clarity;
            }

            // --- 4. tone ------------------------------------------------------------------
            if (exposure != 0.0) color *= pow(2.0, exposure);
            if (brightness != 0.0) color += brightness;
            if (contrast != 0.0) color = (color - float3(0.5)) * (1.0 + contrast) + float3(0.5);

            if (highlights != 0.0 || shadows != 0.0) {
                float l = dot(clamp(color, float3(0.0), float3(1.0)), LUMA);
                color += highlights * smoothstep(0.5, 1.0, l);
                color += shadows * (1.0 - smoothstep(0.0, 0.5, l));
            }
            if (fade > 0.0) {
                // Lift the black point the way aged prints do, without touching the highlights.
                color = mix(color, color * (1.0 - fade * 0.45) + float3(fade * 0.22), 1.0);
            }

            // --- 5. colour ----------------------------------------------------------------
            if (temperature != 0.0) {
                color.r += temperature * 0.18;
                color.b -= temperature * 0.18;
            }
            if (tint != 0.0) {
                color.g += tint * 0.14;
                color.r -= tint * 0.05;
                color.b -= tint * 0.05;
            }
            if (saturation != 0.0) {
                float l = dot(color, LUMA);
                color = mix(float3(l), color, 1.0 + saturation);
            }
            if (lutIntensity > 0.0) {
                color = mix(color, sampleLut(color), lutIntensity);
            }

            // --- 6. colour bleed ----------------------------------------------------------
            if (colorBleed > 0.0) {
                // Smear chroma sideways while keeping luma sharp — how composite video looks.
                float3 smeared = blurAt(sampleCoord, colorBleed * shortSide * 0.012);
                float l = dot(color, LUMA);
                float3 smearedChroma = smeared - float3(dot(smeared, LUMA));
                color = float3(l) + mix(color - float3(l), smearedChroma, colorBleed);
            }

            // --- 7. light -----------------------------------------------------------------
            if (halationAmount > 0.0) {
                float3 bloom = brightPass(
                    blurAt(fragCoord, halationRadius * shortSide * 0.01), halationThreshold
                );
                color += bloom * halationAmount * float3(1.0, 0.42, 0.22);
            }
            if (glowAmount > 0.0) {
                float3 bloom = brightPass(blurAt(fragCoord, shortSide * 0.025), glowThreshold);
                color += bloom * glowAmount;
            }
            if (leakAmount > 0.0) {
                float2 toLeak = (uv - leakOrigin) * float2(aspect, 1.0);
                float fall = 1.0 - smoothstep(0.0, max(leakRadius, 0.001), length(toLeak));
                color += fall * fall * leakAmount * leakColor;
            }
            if (burnAmount > 0.0) {
                // A hot blown-out corner, harder-edged and whiter than a leak.
                float2 origin = float2(1.0, 0.0);
                float fall = 1.0 - smoothstep(0.0, 0.75, length((uv - origin) * float2(aspect, 1.0)));
                float burn = pow(fall, 2.2) * burnAmount;
                color += burn * float3(1.0, 0.72, 0.42) * 1.6;
            }
            if (flashAmount > 0.0) {
                float hot = clamp(1.0 - length(centered * float2(aspect, 1.0)) * 2.0, 0.0, 1.0);
                color += pow(hot, max(flashHardness, 0.001)) * flashAmount;
            }

            // --- 8. video artefacts -------------------------------------------------------
            if (scanlineAmount > 0.0) {
                float line = 0.5 + 0.5 * cos(uv.y * scanlineCount * TWO_PI);
                color *= 1.0 - scanlineAmount * (1.0 - line);
            }
            if (syncBarAmount > 0.0) {
                color += syncBand * syncBarAmount * float3(0.85, 0.90, 1.0);
            }
            if (noiseAmount > 0.0) {
                // Coloured speckle, unlike grain's monochrome density variation.
                float3 n = float3(
                    hash(fragCoord + seed * 11.0),
                    hash(fragCoord + seed * 13.0),
                    hash(fragCoord + seed * 17.0)
                ) - float3(0.5);
                color += n * noiseAmount;
            }

            // --- 9. film artefacts --------------------------------------------------------
            if (grainAmount > 0.0) {
                float scale = 1300.0 / max(grainSize, 0.05);
                float2 grainCoord = float2(uv.x * aspect, uv.y) * scale
                    + float2(seed * 71.0, seed * 113.0);
                float g = hash(grainCoord) - 0.5;

                // Real film grain lives in the density curve: strong in the toe and mid, quiet in
                // the shoulder. That also keeps it off skin and bright sky, which is where obvious
                // grain reads as digital noise rather than film.
                float l = dot(clamp(color, float3(0.0), float3(1.0)), LUMA);
                float weight = mix(1.0, 0.45, smoothstep(0.32, 0.85, l));
                color += g * grainAmount * weight;
            }
            if (dustAmount > 0.0) {
                color += dustAt(uv, dustAmount, aspect) * 0.85;
            }
            if (scratchAmount > 0.0) {
                color += scratchAt(uv, scratchAmount) * 0.7;
            }

            // --- 10. vignette --------------------------------------------------------------
            if (vignetteAmount > 0.0) {
                float2 v = centered * float2(aspect, 1.0);
                color *= clamp(1.0 - vignetteAmount * dot(v, v) * 2.0, 0.0, 1.0);
            }

            return half4(half3(clamp(color, float3(0.0), float3(1.0))), 1.0);
        }
    """
}
