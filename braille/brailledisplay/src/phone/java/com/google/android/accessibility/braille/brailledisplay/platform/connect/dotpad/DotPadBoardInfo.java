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

import java.util.Locale;

/**
 * Board information reported by a Dot Pad in its {@link DotPadPacket#RSP_BOARD_INFORMATION}
 * response: supported features, cell format and the size of the text (braille line) and graphic
 * display areas.
 */
public final class DotPadBoardInfo {
  public static final int FEATURE_GRAPHIC_DISPLAY = 0x80;
  public static final int FEATURE_TEXT_DISPLAY = 0x40;
  public static final int FEATURE_PERKINS_KEYS = 0x20;
  public static final int FEATURE_ROUTING_KEYS = 0x10;
  public static final int FEATURE_NAVIGATION_KEYS = 0x08;
  public static final int FEATURE_PANNING_KEYS = 0x04;
  public static final int FEATURE_FUNCTION_KEYS = 0x02;

  private static final int DOTS_PER_CELL_8 = 1;
  private static final int REFRESH_TIME_UNIT_MS = 100;

  /** One display area (text or graphic). */
  public static final class Area {
    public final int rows;
    public final int columns;
    public final boolean dividedLine;
    /** Worst-case time to refresh the whole area, in milliseconds; {@code 0} if not reported. */
    public final int refreshTimeMs;

    Area(int rows, int columns, boolean dividedLine, int refreshTimeMs) {
      this.rows = rows;
      this.columns = columns;
      this.dividedLine = dividedLine;
      this.refreshTimeMs = refreshTimeMs;
    }

    public boolean isPresent() {
      return rows > 0 && columns > 0;
    }

    @Override
    public String toString() {
      return String.format(
          Locale.US, "%dx%d%s refresh=%dms", columns, rows, dividedLine ? " divided" : "",
          refreshTimeMs);
    }
  }

  public final int features;
  public final boolean eightDotCells;
  /** Distance between adjacent pins in 0.1 mm units. */
  public final int pinSpacing;
  public final int functionKeyCount;
  public final Area text;
  public final Area graphic;

  private DotPadBoardInfo(
      int features,
      boolean eightDotCells,
      int pinSpacing,
      int functionKeyCount,
      Area text,
      Area graphic) {
    this.features = features;
    this.eightDotCells = eightDotCells;
    this.pinSpacing = pinSpacing;
    this.functionKeyCount = functionKeyCount;
    this.text = text;
    this.graphic = graphic;
  }

  /** Parses the data of a {@link DotPadPacket#RSP_BOARD_INFORMATION} packet. Missing fields are 0. */
  public static DotPadBoardInfo parse(DotPadPacket packet) {
    int features = Math.max(0, packet.getDataByte(0));
    int dotsPerCell = packet.getDataByte(1);
    int functionKeyCount = Math.max(0, packet.getDataByte(3));
    if ((features & FEATURE_FUNCTION_KEYS) != 0 && functionKeyCount == 0) {
      // Older firmware reports function keys without a count; Dot Pads have four.
      functionKeyCount = 4;
    }
    return new DotPadBoardInfo(
        features,
        dotsPerCell < 0 || dotsPerCell == DOTS_PER_CELL_8,
        Math.max(0, packet.getDataByte(2)),
        functionKeyCount,
        parseArea(packet, 4),
        parseArea(packet, 8));
  }

  private static Area parseArea(DotPadPacket packet, int offset) {
    return new Area(
        Math.max(0, packet.getDataByte(offset)),
        Math.max(0, packet.getDataByte(offset + 1)),
        packet.getDataByte(offset + 2) == 1,
        Math.max(0, packet.getDataByte(offset + 3)) * REFRESH_TIME_UNIT_MS);
  }

  public boolean hasFeature(int feature) {
    return (features & feature) != 0;
  }

  @Override
  public String toString() {
    return String.format(
        Locale.US,
        "DotPadBoardInfo{features=%02X, %d-dot, pinSpacing=%d, fnKeys=%d, text=%s, graphic=%s}",
        features, eightDotCells ? 8 : 6, pinSpacing, functionKeyCount, text, graphic);
  }
}
