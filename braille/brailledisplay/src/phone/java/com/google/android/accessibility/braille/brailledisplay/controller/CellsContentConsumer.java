/*
 * Copyright (C) 2023 Google Inc.
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

import android.text.style.ClickableSpan;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import java.util.Optional;

/** Consumer of {@link CellsContent}. */
public interface CellsContentConsumer {

  /** Reason for update {@link CellsContent}. */
  enum Reason {
    START_UP,
    NAVIGATE_TO_NEW_NODE,
    WINDOW_CHANGED,
    SCREEN_OFF,
    /** Multi-line reading panned past the start of the first item on the display. */
    PAN_TO_PREVIOUS_ITEMS,
  }

  /** Sets common content. */
  void setContent(CellsContent content, Reason reason);

  /** Sets timed content with specific duration. */
  void setTimedContent(TimedMessager.Type type, CellsContent content, int durationInMilliseconds);

  /** Returns accessibility node at the given position. */
  AccessibilityNodeInfoCompat getAccessibilityNode(int routingKeyIndex);

  /** Returns the index in the whole content. */
  int getTextIndexInWhole(int routingKeyIndex);

  /** Gets the {@link android.text.style.ClickableSpan} in the specific index. */
  Optional<ClickableSpan[]> getClickableSpans(int routingKeyIndex);

  /** Dismisses current timed message. */
  void dismissTimedMessage();

  /** Displays the latest caption. */
  boolean showLatestCaption();

  /** Returns whether timed message is displaying. */
  boolean isTimedMessageDisplaying();

  /** Returns whether caption is displaying. */
  boolean isCaptionShowing();

  /**
   * Pans the display back one page. Returns {@code false} if the common content is already at its
   * start.
   */
  boolean panUp();

  /**
   * Pans the display forward one page. Returns {@code false} if the common content is already at
   * its end.
   */
  boolean panDown();

  /**
   * Returns the text position, in the common content, of the first cell on the display, or {@code
   * NO_CURSOR} if a timed message is showing or there is no content.
   */
  int getDisplayStartTextPosition();
}
