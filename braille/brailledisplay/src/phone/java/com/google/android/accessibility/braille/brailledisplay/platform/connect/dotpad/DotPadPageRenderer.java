/*
 * Copyright 2026 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.braille.brailledisplay.platform.connect.dotpad;

/**
 * Renders a page of logical braille cells onto the Dot Pad graphics area.
 *
 * <p>The graphics area is a grid of physical 2x4-pin cells. Each logical braille cell also takes
 * 2x4 pins, but logical cells are spaced with a 1-pin horizontal gap between cells and a 1-pin
 * vertical gap between lines so they read as braille rather than as a solid pin field. On the Dot
 * Pad X (30x10 physical cells = 60x40 pins) that gives 20 logical cells x 8 logical lines.
 *
 * <p>Input cells use TalkBack's braille dot order (bit {@code n} = dot {@code n + 1}). Output cells
 * use the Dot Pad graphic-mode pin order, where bit {@code y + 4 * x} is the pin at column {@code
 * x}, row {@code y} of the physical cell.
 */
public final class DotPadPageRenderer {
  public static final int PINS_PER_CELL_X = 2;
  public static final int PINS_PER_CELL_Y = 4;
  private static final int HORIZONTAL_GAP_PINS = 1;
  private static final int VERTICAL_GAP_PINS = 1;
  private static final int STRIDE_X = PINS_PER_CELL_X + HORIZONTAL_GAP_PINS;
  private static final int STRIDE_Y = PINS_PER_CELL_Y + VERTICAL_GAP_PINS;

  /** TalkBack braille dot bits indexed by [x][y] within a cell: dots 1-2-3-7 and 4-5-6-8. */
  private static final int[][] BRAILLE_DOT_BIT = {
    {0x01, 0x02, 0x04, 0x40},
    {0x08, 0x10, 0x20, 0x80},
  };

  private DotPadPageRenderer() {}

  /** Number of logical braille cells per line that fit on {@code physicalColumns} cells. */
  public static int logicalColumns(int physicalColumns) {
    return fit(physicalColumns * PINS_PER_CELL_X, PINS_PER_CELL_X, STRIDE_X);
  }

  /** Number of logical braille lines that fit on {@code physicalRows} cells. */
  public static int logicalRows(int physicalRows) {
    return fit(physicalRows * PINS_PER_CELL_Y, PINS_PER_CELL_Y, STRIDE_Y);
  }

  /** Number of cells of {@code cellPins} spaced by {@code stride} that fit in {@code totalPins}. */
  private static int fit(int totalPins, int cellPins, int stride) {
    if (totalPins < cellPins) {
      return 1;
    }
    return (totalPins - cellPins) / stride + 1;
  }

  /**
   * Renders {@code brailleCells} (row-major, {@code logicalColumns} per line) onto a {@code
   * physicalRows} x {@code physicalColumns} grid of graphic-mode cells. Missing input cells are
   * blank; cells that fall outside the grid are clipped.
   */
  public static byte[][] render(
      byte[] brailleCells,
      int logicalColumns,
      int logicalRows,
      int physicalColumns,
      int physicalRows) {
    byte[][] physical = new byte[physicalRows][physicalColumns];
    int totalPinsX = physicalColumns * PINS_PER_CELL_X;
    int totalPinsY = physicalRows * PINS_PER_CELL_Y;
    for (int row = 0; row < logicalRows; row++) {
      for (int col = 0; col < logicalColumns; col++) {
        int index = row * logicalColumns + col;
        if (index >= brailleCells.length) {
          return physical;
        }
        int dots = brailleCells[index] & 0xFF;
        if (dots == 0) {
          continue;
        }
        for (int x = 0; x < PINS_PER_CELL_X; x++) {
          int pinX = col * STRIDE_X + x;
          if (pinX >= totalPinsX) {
            continue;
          }
          for (int y = 0; y < PINS_PER_CELL_Y; y++) {
            int pinY = row * STRIDE_Y + y;
            if ((dots & BRAILLE_DOT_BIT[x][y]) == 0 || pinY >= totalPinsY) {
              continue;
            }
            int bit = (pinY % PINS_PER_CELL_Y) + PINS_PER_CELL_Y * (pinX % PINS_PER_CELL_X);
            physical[pinY / PINS_PER_CELL_Y][pinX / PINS_PER_CELL_X] |= (byte) (1 << bit);
          }
        }
      }
    }
    return physical;
  }
}
