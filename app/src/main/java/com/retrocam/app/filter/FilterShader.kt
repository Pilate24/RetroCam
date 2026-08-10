package com.retrocam.app.filter

/**
 * The single AGSL program every look runs through. Adding a filter means adding a preset JSON
 * (see `assets/presets/`) and a LUT PNG — not writing a new shader.
 *
 * Stages, in order: sync-bar displacement -> chromatic aberration -> LUT colour grade ->
 * halation -> light leak -> flash hotspot -> scanlines -> sync-bar glow -> grain -> vignette.
 *
 * Geometric overlays that change the frame's shape — aspect crop, the Polaroid border, the date
 * stamp — are not here; they run after this shader in [OverlayCompositor], because they alter the
 * output's dimensions rather than its pixels.
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
        uniform float grainAmount;
        uniform float vignetteAmount;
        uniform float chromaAmount;
        uniform float halationAmount;
        uniform float halationRadius;
        uniform float halationThreshold;
        uniform float scanlineAmount;
        uniform float scanlineCount;
        uniform float syncBarAmount;
        uniform float syncBarWidth;
        uniform float syncBarShift;
        uniform float leakAmount;
        uniform float2 leakOrigin;
        uniform float leakRadius;
        uniform float flashAmount;
        uniform float flashHardness;

        const float LUT_GRID = 8.0;
        const float LUT_MAX = 63.0;
        const float GRAIN_SCALE = 900.0;
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

        float3 brightPass(float3 c) {
            float l = dot(c, LUMA);
            return c * (max(l - halationThreshold, 0.0) / max(1.0 - halationThreshold, 0.001));
        }

        half4 main(float2 fragCoord) {
            float2 uv = fragCoord / resolution;
            float2 centered = uv - float2(0.5);
            float shortSide = min(resolution.x, resolution.y);
            float aspect = resolution.x / resolution.y;

            // 0. Rolling sync bar: a band that drifts down the frame, dragging the picture sideways.
            //    Wrapped distance so the band re-enters from the top seamlessly.
            float syncBand = 0.0;
            float2 sampleCoord = fragCoord;
            if (syncBarAmount > 0.0) {
                float barPos = fract(seed * 0.13);
                float d = abs(fract(uv.y - barPos + 0.5) - 0.5);
                syncBand = 1.0 - smoothstep(0.0, max(syncBarWidth, 0.001), d);
                sampleCoord.x += syncBand * syncBarShift * resolution.x;
            }

            // 1. Chromatic aberration: red and blue drift outward from the centre.
            float3 color;
            if (chromaAmount > 0.0) {
                float2 offset = centered * (chromaAmount * shortSide * 0.01);
                color = float3(
                    sampleInput(sampleCoord + offset).r,
                    sampleInput(sampleCoord).g,
                    sampleInput(sampleCoord - offset).b
                );
            } else {
                color = sampleInput(sampleCoord);
            }

            // 2. Colour grade. lutIntensity is the per-look strength / future intensity slider.
            color = mix(color, sampleLut(color), lutIntensity);

            // 3. Halation: bleed warm light out of the highlights. Eight taps on a ring.
            if (halationAmount > 0.0) {
                float r = halationRadius * shortSide * 0.01;
                float d = r * 0.7071;
                float3 bloom =
                    brightPass(sampleInput(fragCoord + float2( r, 0.0))) +
                    brightPass(sampleInput(fragCoord + float2(-r, 0.0))) +
                    brightPass(sampleInput(fragCoord + float2(0.0,  r))) +
                    brightPass(sampleInput(fragCoord + float2(0.0, -r))) +
                    brightPass(sampleInput(fragCoord + float2( d,  d))) +
                    brightPass(sampleInput(fragCoord + float2( d, -d))) +
                    brightPass(sampleInput(fragCoord + float2(-d,  d))) +
                    brightPass(sampleInput(fragCoord + float2(-d, -d)));
                color += bloom * 0.125 * halationAmount * float3(1.0, 0.42, 0.22);
            }

            // 4. Light leak: warm bloom spilling in from wherever leakOrigin sits.
            if (leakAmount > 0.0) {
                float2 toLeak = (uv - leakOrigin) * float2(aspect, 1.0);
                float fall = 1.0 - smoothstep(0.0, max(leakRadius, 0.001), length(toLeak));
                color += fall * fall * leakAmount * float3(1.0, 0.52, 0.22);
            }

            // 5. Flash hotspot: the bright centre a cheap on-camera flash throws.
            if (flashAmount > 0.0) {
                float2 v = centered * float2(aspect, 1.0);
                float hot = clamp(1.0 - length(v) * 2.0, 0.0, 1.0);
                color += pow(hot, max(flashHardness, 0.001)) * flashAmount;
            }

            // 6. Scanlines, counted across the frame height so they survive any resolution.
            if (scanlineAmount > 0.0) {
                float line = 0.5 + 0.5 * cos(uv.y * scanlineCount * TWO_PI);
                color *= 1.0 - scanlineAmount * (1.0 - line);
            }

            // 7. The sync bar itself: a washed-out, slightly blue glow over the displaced band.
            if (syncBarAmount > 0.0) {
                color += syncBand * syncBarAmount * float3(0.85, 0.90, 1.0);
            }

            // 8. Grain, sized relative to the frame so preview and capture match.
            if (grainAmount > 0.0) {
                float2 grainCoord = float2(uv.x * aspect, uv.y) * GRAIN_SCALE + float2(seed * 71.0, seed * 113.0);
                color += (hash(grainCoord) - 0.5) * grainAmount;
            }

            // 9. Vignette, aspect-corrected so it stays round.
            float2 vig = centered * float2(aspect, 1.0);
            color *= clamp(1.0 - vignetteAmount * dot(vig, vig) * 2.0, 0.0, 1.0);

            return half4(half3(clamp(color, float3(0.0), float3(1.0))), 1.0);
        }
    """
}
