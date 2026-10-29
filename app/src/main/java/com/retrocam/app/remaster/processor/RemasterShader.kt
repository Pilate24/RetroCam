package com.retrocam.app.remaster.processor

/**
 * Android Graphics Shading Language (AGSL) program for hardware-accelerated AI Photo Remaster.
 *
 * Runs entirely on the device GPU on Android 13+ (API 33+).
 * Computes exposure, tone mapping, dynamic range compression, AWB color balance,
 * smart vibrance, edge-preserving bilateral denoising, unsharp clarity,
 * detail enhancement, and portrait skin refinement in a single unified GPU pass.
 */
object RemasterShader {

    const val SOURCE = """
        uniform shader inputShader;
        uniform float2 resolution;

        // Tone & Light
        uniform float brightness;
        uniform float exposure;
        uniform float contrast;
        uniform float highlights;
        uniform float shadows;

        // Color & Balance
        uniform float saturation;
        uniform float vibrance;
        uniform float temperature;
        uniform float tint;

        // Detail & Optics
        uniform float sharpness;
        uniform float clarity;
        uniform float noiseReduction;
        uniform float detailRecovery;
        uniform float vignette;

        // Modes & Flags
        uniform float isMonochrome;
        uniform float removeArtifacts;

        // Portrait Features
        uniform float portraitActive;
        uniform float portraitFaceCount;
        // Up to 4 face centers (x, y normalized) and radius (normalized)
        uniform float4 face0; // x, y, radius, confidence
        uniform float4 face1;
        uniform float portraitSkinSmoothing;
        uniform float portraitFaceIllum;
        uniform float portraitBgBlur;

        float3 srgbToLinear(float3 c) {
            return pow(max(c, float3(0.0)), float3(2.2));
        }

        float3 linearToSrgb(float3 c) {
            return pow(max(c, float3(0.0)), float3(1.0 / 2.2));
        }

        float rgbToLuma(float3 c) {
            return dot(c, float3(0.2126, 0.7152, 0.0722));
        }

        // Detects human skin tones in normalized RGB
        float detectSkin(float3 rgb) {
            float r = rgb.r;
            float g = rgb.g;
            float b = rgb.b;
            // Generalized skin tone threshold in sRGB space
            bool isSkin = (r > g) && (g > b) && ((r - g) > 0.04) && ((r - b) > 0.06);
            return isSkin ? 1.0 : 0.0;
        }

        half4 main(float2 fragCoord) {
            float2 uv = fragCoord / resolution;
            float2 pixelStep = float2(1.0) / resolution;

            // Center sample
            float4 baseSample = inputShader.eval(fragCoord);
            float3 c = baseSample.rgb;

            // 1. Bilateral Edge-Preserving Noise Reduction
            if (noiseReduction > 0.01) {
                float centerLuma = rgbToLuma(c);
                float3 blurSum = c;
                float weightSum = 1.0;
                float sigmaColor = max(0.03, (1.05 - noiseReduction) * 0.18);
                float stepDist = 1.2 + noiseReduction * 1.0;

                float2 offsets[8];
                offsets[0] = float2(-stepDist, 0.0) * pixelStep;
                offsets[1] = float2(stepDist, 0.0) * pixelStep;
                offsets[2] = float2(0.0, -stepDist) * pixelStep;
                offsets[3] = float2(0.0, stepDist) * pixelStep;
                offsets[4] = float2(-stepDist, -stepDist) * pixelStep * 0.7;
                offsets[5] = float2(stepDist, -stepDist) * pixelStep * 0.7;
                offsets[6] = float2(-stepDist, stepDist) * pixelStep * 0.7;
                offsets[7] = float2(stepDist, stepDist) * pixelStep * 0.7;

                for (int i = 0; i < 8; i++) {
                    float3 s = inputShader.eval(fragCoord + offsets[i] * resolution).rgb;
                    float diff = abs(rgbToLuma(s) - centerLuma);
                    float w = exp(- (diff * diff) / (2.0 * sigmaColor * sigmaColor));
                    blurSum += s * w;
                    weightSum += w;
                }
                float3 denoised = blurSum / weightSum;
                c = mix(c, denoised, min(1.0, noiseReduction * 1.2));
            }

            // 2. Exposure & Brightness in Linear Domain
            float3 lin = srgbToLinear(c);
            if (abs(exposure) > 0.001) {
                lin *= exp2(exposure * 1.4);
            }
            if (abs(brightness) > 0.001) {
                lin += float3(brightness * 0.28);
            }
            c = linearToSrgb(lin);

            // 3. Dynamic Range: Shadows Lift & Highlights Recovery
            float luma = rgbToLuma(c);
            if (abs(shadows) > 0.001) {
                // Shadow lift with smooth toe roll-off
                float shadowMask = clamp(1.0 - luma, 0.0, 1.0);
                shadowMask = shadowMask * shadowMask;
                c += float3(shadows * shadowMask * 0.42);
            }
            if (abs(highlights) > 0.001) {
                // Highlight compression / recovery
                float hlMask = clamp(luma, 0.0, 1.0);
                hlMask = hlMask * hlMask;
                c += float3(highlights * hlMask * 0.40);
            }

            // 4. Contrast (S-Curve about mid-gray 0.5)
            if (abs(contrast) > 0.001) {
                float factor = 1.0 + contrast * 1.1;
                c = (c - 0.5) * factor + 0.5;
            }

            // 5. White Balance (Temperature & Tint)
            if (abs(temperature) > 0.001 || abs(tint) > 0.001) {
                // Temperature: + warm (red up, blue down), - cool (blue up, red down)
                c.r += temperature * 0.16;
                c.b -= temperature * 0.16;

                // Tint: + magenta (green down, red/blue up), - green (green up)
                c.g -= tint * 0.14;
                c.r += tint * 0.07;
                c.b += tint * 0.07;
            }

            // 6. Saturation & Smart Vibrance
            float currentLuma = rgbToLuma(c);
            if (abs(saturation) > 0.001) {
                c = mix(float3(currentLuma), c, 1.0 + saturation);
            }
            if (abs(vibrance) > 0.001) {
                float maxCol = max(c.r, max(c.g, c.b));
                float minCol = min(c.r, min(c.g, c.b));
                float currentSat = maxCol - minCol;
                // Vibrance boosts desaturated colors more than already saturated ones
                float vibFactor = vibrance * (1.0 - currentSat) * 1.4;
                c = mix(float3(currentLuma), c, 1.0 + vibFactor);
            }

            // 7. Clarity (Micro-contrast) & Sharpness & Detail Recovery
            float totalDetail = sharpness + clarity * 0.8 + detailRecovery * 0.9;
            if (totalDetail > 0.01) {
                float2 d = pixelStep * resolution * 1.0;
                float3 cUp = inputShader.eval(fragCoord + float2(0.0, -d.y)).rgb;
                float3 cDown = inputShader.eval(fragCoord + float2(0.0, d.y)).rgb;
                float3 cLeft = inputShader.eval(fragCoord + float2(-d.x, 0.0)).rgb;
                float3 cRight = inputShader.eval(fragCoord + float2(d.x, 0.0)).rgb;

                float3 localAvg = (cUp + cDown + cLeft + cRight) * 0.25;
                float3 highPass = c - localAvg;

                // Clarity enhances midtone micro-contrast
                if (abs(clarity) > 0.001) {
                    float midtoneMask = 1.0 - abs(currentLuma - 0.5) * 2.0;
                    c += highPass * (clarity * midtoneMask * 1.5);
                }

                // Sharpness & Detail Recovery with anti-halo clamping
                float sharpAmount = sharpness * 1.2 + detailRecovery * 0.7;
                float3 sharpened = c + highPass * sharpAmount;
                // Clamp ringing halos
                float3 minNeighbor = min(c, min(min(cUp, cDown), min(cLeft, cRight)));
                float3 maxNeighbor = max(c, max(max(cUp, cDown), max(cLeft, cRight)));
                c = clamp(sharpened, minNeighbor - 0.05, maxNeighbor + 0.05);
            }

            // 8. Portrait Enhancement (Skin Smoothing, Face Illumination, BG Blur)
            if (portraitActive > 0.5 && portraitFaceCount > 0.0) {
                float isSkin = detectSkin(c);
                float distToFace = 10.0;

                // Check distance to Face 0
                if (face0.z > 0.01) {
                    float d = length(uv - face0.xy) / face0.z;
                    distToFace = min(distToFace, d);
                }
                // Check distance to Face 1
                if (face1.z > 0.01) {
                    float d = length(uv - face1.xy) / face1.z;
                    distToFace = min(distToFace, d);
                }

                // Face Illumination (subtle exposure lift on face center)
                if (portraitFaceIllum > 0.01 && distToFace < 1.4) {
                    float illumFactor = (1.0 - smoothstep(0.0, 1.4, distToFace)) * portraitFaceIllum * 0.22;
                    c += float3(illumFactor);
                }

                // Skin Smoothing: smooth only skin pixels near faces
                if (portraitSkinSmoothing > 0.01 && isSkin > 0.5 && distToFace < 2.0) {
                    float2 sStep = pixelStep * resolution * 2.0;
                    float3 s1 = inputShader.eval(fragCoord + float2(sStep.x, 0.0)).rgb;
                    float3 s2 = inputShader.eval(fragCoord - float2(sStep.x, 0.0)).rgb;
                    float3 s3 = inputShader.eval(fragCoord + float2(0.0, sStep.y)).rgb;
                    float3 s4 = inputShader.eval(fragCoord - float2(0.0, sStep.y)).rgb;
                    float3 smoothed = (c + s1 + s2 + s3 + s4) * 0.2;
                    c = mix(c, smoothed, portraitSkinSmoothing * 0.65);
                }

                // Background Depth Separation (subtle blur outside portrait region)
                if (portraitBgBlur > 0.01 && distToFace > 1.8) {
                    float bgFactor = smoothstep(1.8, 3.2, distToFace) * portraitBgBlur * 0.5;
                    float2 bStep = pixelStep * resolution * 3.0;
                    float3 b1 = inputShader.eval(fragCoord + float2(bStep.x, 0.0)).rgb;
                    float3 b2 = inputShader.eval(fragCoord - float2(bStep.x, 0.0)).rgb;
                    float3 b3 = inputShader.eval(fragCoord + float2(0.0, bStep.y)).rgb;
                    float3 b4 = inputShader.eval(fragCoord - float2(0.0, bStep.y)).rgb;
                    float3 bgBlurColor = (c + b1 + b2 + b3 + b4) * 0.2;
                    c = mix(c, bgBlurColor, bgFactor);
                }
            }

            // 9. Black & White Pro Mode
            if (isMonochrome > 0.5) {
                float bwLuma = rgbToLuma(c);
                // Enhanced tonal depth for B&W
                bwLuma = pow(max(0.0, bwLuma), 1.05);
                c = float3(bwLuma);
            }

            // 10. Vignette
            if (abs(vignette) > 0.001) {
                float dist = length((uv - float2(0.5)) * float2(1.0, resolution.y / resolution.x));
                float vFactor = smoothstep(0.35, 0.95, dist);
                if (vignette < 0.0) {
                    // Dark vignette
                    c *= 1.0 + vignette * vFactor * 0.9;
                } else {
                    // Light vignette
                    c = mix(c, float3(1.0), vignette * vFactor * 0.6);
                }
            }

            c = clamp(c, 0.0, 1.0);
            return half4(c, baseSample.a);
        }
    """
}
