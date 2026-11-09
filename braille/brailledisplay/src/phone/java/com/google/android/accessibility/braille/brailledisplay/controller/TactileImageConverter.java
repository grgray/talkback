/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.braille.brailledisplay.controller;

/**
 * Converts a picture into raised and lowered pins for a tactile graphics area.
 *
 * <p>The picture is reduced to two colours with a threshold chosen from its own brightness
 * (Otsu's method). Whichever colour covers less of the picture is taken as the drawing and raised,
 * so black lines on white and white lines on black both come out as raised lines. The picture is
 * then scaled to fit the pin grid, keeping its shape and centring it; a pin is raised when enough
 * of the part of the picture it covers is drawing, so thin lines survive being scaled down.
 */
public final class TactileImageConverter {
  /** Share of a pin's part of the picture that must be drawing for the pin to be raised. */
  static final double MIN_INK_FRACTION = 0.15;

  /** Pixels less opaque than this count as background. */
  private static final int MIN_OPAQUE_ALPHA = 128;

  private TactileImageConverter() {}

  /**
   * Converts {@code argb} ({@code imageWidth} x {@code imageHeight} pixels, row-major) to a {@code
   * pinsWide} x {@code pinsHigh} grid of pins, row-major, {@code true} for raised.
   */
  public static boolean[] convert(
      int[] argb, int imageWidth, int imageHeight, int pinsWide, int pinsHigh) {
    boolean[] pins = new boolean[pinsWide * pinsHigh];
    if (imageWidth <= 0 || imageHeight <= 0 || argb.length < imageWidth * imageHeight) {
      return pins;
    }
    int[] luminance = luminance(argb, imageWidth * imageHeight);
    int threshold = otsuThreshold(luminance);
    boolean[] ink = new boolean[luminance.length];
    int darkCount = 0;
    for (int i = 0; i < luminance.length; i++) {
      ink[i] = luminance[i] <= threshold;
      if (ink[i]) {
        darkCount++;
      }
    }
    if (darkCount == 0 || darkCount == luminance.length) {
      // One colour only: nothing to draw.
      return pins;
    }
    if (darkCount * 2 > luminance.length) {
      // Mostly dark: the drawing is the light part.
      for (int i = 0; i < ink.length; i++) {
        ink[i] = !ink[i];
      }
    }

    double scale = Math.min(pinsWide / (double) imageWidth, pinsHigh / (double) imageHeight);
    int outWide = clamp((int) Math.round(imageWidth * scale), 1, pinsWide);
    int outHigh = clamp((int) Math.round(imageHeight * scale), 1, pinsHigh);
    int offsetX = (pinsWide - outWide) / 2;
    int offsetY = (pinsHigh - outHigh) / 2;
    for (int py = 0; py < outHigh; py++) {
      int y0 = py * imageHeight / outHigh;
      int y1 = Math.max(y0 + 1, (py + 1) * imageHeight / outHigh);
      for (int px = 0; px < outWide; px++) {
        int x0 = px * imageWidth / outWide;
        int x1 = Math.max(x0 + 1, (px + 1) * imageWidth / outWide);
        int inked = 0;
        for (int y = y0; y < y1; y++) {
          for (int x = x0; x < x1; x++) {
            if (ink[y * imageWidth + x]) {
              inked++;
            }
          }
        }
        int area = (y1 - y0) * (x1 - x0);
        pins[(offsetY + py) * pinsWide + offsetX + px] = inked >= MIN_INK_FRACTION * area;
      }
    }
    return pins;
  }

  /** Returns each pixel's brightness, 0 (black) to 255 (white); see-through pixels are white. */
  static int[] luminance(int[] argb, int count) {
    int[] result = new int[count];
    for (int i = 0; i < count; i++) {
      int pixel = argb[i];
      if ((pixel >>> 24) < MIN_OPAQUE_ALPHA) {
        result[i] = 255;
        continue;
      }
      int r = (pixel >> 16) & 0xFF;
      int g = (pixel >> 8) & 0xFF;
      int b = pixel & 0xFF;
      result[i] = (299 * r + 587 * g + 114 * b) / 1000;
    }
    return result;
  }

  /**
   * Returns the brightness that best splits {@code luminance} into two groups (Otsu's method).
   * Values at or below it are one group, values above it the other.
   */
  static int otsuThreshold(int[] luminance) {
    long[] histogram = new long[256];
    for (int value : luminance) {
      histogram[value]++;
    }
    long total = luminance.length;
    double sumAll = 0;
    for (int i = 0; i < 256; i++) {
      sumAll += i * (double) histogram[i];
    }
    double sumBelow = 0;
    long countBelow = 0;
    double bestVariance = -1;
    int best = 127;
    for (int t = 0; t < 256; t++) {
      countBelow += histogram[t];
      if (countBelow == 0) {
        continue;
      }
      long countAbove = total - countBelow;
      if (countAbove == 0) {
        break;
      }
      sumBelow += t * (double) histogram[t];
      double meanBelow = sumBelow / countBelow;
      double meanAbove = (sumAll - sumBelow) / countAbove;
      double difference = meanBelow - meanAbove;
      double variance = (double) countBelow * countAbove * difference * difference;
      if (variance > bestVariance) {
        bestVariance = variance;
        best = t;
      }
    }
    return best;
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
