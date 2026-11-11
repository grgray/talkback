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

package com.google.android.accessibility.braille.brailledisplay.platform;

import androidx.annotation.Nullable;

/**
 * A connected braille display that can also draw tactile graphics, such as the Dot Pad's graphics
 * area. A driver registers itself with {@link #setCurrent} while such a display is ready.
 *
 * <p>While a graphic is shown, braille content sent to the display is kept but not shown; {@link
 * #clearGraphic} shows the latest braille content again.
 */
public interface TactileGraphicsDisplay {

  /** Width of the graphics area, in pins. */
  int getGraphicWidth();

  /** Height of the graphics area, in pins. */
  int getGraphicHeight();

  /**
   * Draws {@code pins}, {@link #getGraphicWidth} x {@link #getGraphicHeight}, row-major, {@code
   * true} for raised, in place of the braille content.
   */
  void showGraphic(boolean[] pins);

  /** Stops drawing the graphic and shows the braille content again. */
  void clearGraphic();

  /** Returns whether a graphic is being drawn. */
  boolean isShowingGraphic();

  /**
   * Returns the number of cells on the display's separate braille line, which can show text while a
   * graphic is drawn, or 0 if it has none.
   */
  int getBrailleLineCells();

  /**
   * Shows {@code cells} (TalkBack's dot order: bits 0 to 7 are dots 1 to 8) on the separate braille
   * line, blank-padded or cut to {@link #getBrailleLineCells}; {@code null} blanks it.
   */
  void showBrailleLine(@Nullable byte[] cells);

  /** Sets the display that can draw graphics, or {@code null} when there is none. */
  static void setCurrent(@Nullable TactileGraphicsDisplay display) {
    Holder.current = display;
  }

  /** Returns the connected display that can draw graphics, or {@code null}. */
  @Nullable
  static TactileGraphicsDisplay getCurrent() {
    return Holder.current;
  }

  /** Holds the current display. */
  final class Holder {
    @Nullable private static volatile TactileGraphicsDisplay current;

    private Holder() {}
  }
}
