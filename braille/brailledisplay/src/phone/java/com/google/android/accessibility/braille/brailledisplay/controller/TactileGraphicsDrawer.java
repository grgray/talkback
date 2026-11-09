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

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.text.TextUtils;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.braille.brailledisplay.BrailleDisplayLog;
import com.google.android.accessibility.braille.brailledisplay.platform.TactileGraphicsDisplay;
import com.google.android.accessibility.utils.FeatureSupport;
import com.google.android.accessibility.utils.screencapture.ScreenshotCapture;
import java.util.function.Supplier;

/**
 * Draws pictures on a braille display that can show tactile graphics.
 *
 * <p>The picture is captured from the screen where the item is shown, converted to pins with
 * {@link TactileImageConverter}, and drawn in place of the braille. Authors mark the pictures to
 * draw automatically with {@code aria-roledescription="tactile graphic"} or alt text starting
 * "Tactile:"; any focused item can also be drawn on request.
 */
class TactileGraphicsDrawer {
  private static final String TAG = "TactileGraphicsDrawer";

  /** Role description that marks a picture for drawing, set with {@code aria-roledescription}. */
  static final String MARKED_ROLE_DESCRIPTION = "tactile graphic";

  /** Word at the start of alt text that marks a picture for drawing. */
  static final String ALT_TEXT_MARKER = "Tactile:";

  /** Called when a drawing request fails. */
  interface FailureListener {
    void onDrawFailed();
  }

  private final Supplier<AccessibilityService> serviceSupplier;
  private final FailureListener failureListener;
  /** Item drawn, or being captured for drawing; {@code null} if none. */
  @Nullable private AccessibilityNodeInfoCompat drawnNode;
  /** Increased on every request and clear, so a late capture for an old request is dropped. */
  private int requestId;

  TactileGraphicsDrawer(
      Supplier<AccessibilityService> serviceSupplier, FailureListener failureListener) {
    this.serviceSupplier = serviceSupplier;
    this.failureListener = failureListener;
  }

  /** Returns whether {@code node} is marked by its author to be drawn. */
  static boolean isMarked(AccessibilityNodeInfoCompat node) {
    CharSequence roleDescription = node.getRoleDescription();
    if (roleDescription != null
        && MARKED_ROLE_DESCRIPTION.equalsIgnoreCase(roleDescription.toString().trim())) {
      return true;
    }
    CharSequence alt = node.getContentDescription();
    if (TextUtils.isEmpty(alt)) {
      alt = node.getText();
    }
    return startsWithMarker(alt);
  }

  /** Returns whether {@code text} starts with {@link #ALT_TEXT_MARKER}, ignoring case. */
  @VisibleForTesting
  static boolean startsWithMarker(@Nullable CharSequence text) {
    return markerEnd(text) >= 0;
  }

  /**
   * Returns the index just after {@link #ALT_TEXT_MARKER} and the spaces after it, if {@code text}
   * starts with it (after any spaces); otherwise -1.
   */
  @VisibleForTesting
  static int markerEnd(@Nullable CharSequence text) {
    if (text == null) {
      return -1;
    }
    String string = text.toString();
    int start = 0;
    while (start < string.length() && Character.isWhitespace(string.charAt(start))) {
      start++;
    }
    if (!string.regionMatches(
        /* ignoreCase= */ true, start, ALT_TEXT_MARKER, 0, ALT_TEXT_MARKER.length())) {
      return -1;
    }
    int end = start + ALT_TEXT_MARKER.length();
    while (end < string.length() && Character.isWhitespace(string.charAt(end))) {
      end++;
    }
    return end;
  }

  /** Returns whether a display that can draw graphics is connected. */
  boolean canDraw() {
    return TactileGraphicsDisplay.getCurrent() != null
        && FeatureSupport.canTakeScreenShotByAccessibilityService();
  }

  /** Returns whether {@code node} is drawn, or being captured for drawing. */
  boolean isDrawn(@Nullable AccessibilityNodeInfoCompat node) {
    return node != null && node.equals(drawnNode);
  }

  /** Returns whether anything is drawn, or being captured for drawing. */
  boolean isDrawing() {
    TactileGraphicsDisplay display = TactileGraphicsDisplay.getCurrent();
    return drawnNode != null || (display != null && display.isShowingGraphic());
  }

  /**
   * Captures {@code node} from the screen and draws it. Returns {@code false} if it can't be
   * started; a failure after that is reported to the {@link FailureListener}.
   */
  boolean draw(AccessibilityNodeInfoCompat node) {
    AccessibilityService service = serviceSupplier.get();
    if (!canDraw() || service == null) {
      return false;
    }
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    if (bounds.isEmpty()) {
      return false;
    }
    int id = ++requestId;
    drawnNode = node;
    ScreenshotCapture.takeScreenshot(
        service,
        (bitmap, isFormatSupported) -> onCaptured(id, bounds, bitmap),
        service.getMainExecutor());
    return true;
  }

  /** Stops drawing, showing the braille again. */
  void clear() {
    requestId++;
    drawnNode = null;
    TactileGraphicsDisplay display = TactileGraphicsDisplay.getCurrent();
    if (display != null && display.isShowingGraphic()) {
      display.clearGraphic();
    }
  }

  private void onCaptured(int id, Rect bounds, @Nullable Bitmap screenshot) {
    if (id != requestId) {
      // Cleared, or another request was made, while capturing.
      if (screenshot != null) {
        screenshot.recycle();
      }
      return;
    }
    TactileGraphicsDisplay display = TactileGraphicsDisplay.getCurrent();
    Rect area = new Rect(bounds);
    if (screenshot == null
        || display == null
        || !area.intersect(0, 0, screenshot.getWidth(), screenshot.getHeight())) {
      BrailleDisplayLog.w(TAG, "Couldn't capture the item to draw");
      if (screenshot != null) {
        screenshot.recycle();
      }
      drawnNode = null;
      failureListener.onDrawFailed();
      return;
    }
    int width = area.width();
    int height = area.height();
    int[] pixels = new int[width * height];
    screenshot.getPixels(pixels, 0, width, area.left, area.top, width, height);
    screenshot.recycle();
    boolean[] pins =
        TactileImageConverter.convert(
            pixels, width, height, display.getGraphicWidth(), display.getGraphicHeight());
    if (!hasRaisedPin(pins)) {
      // A blank or one-colour picture, or a protected window that captures as black.
      BrailleDisplayLog.w(TAG, "Nothing to draw in the captured item");
      drawnNode = null;
      failureListener.onDrawFailed();
      return;
    }
    display.showGraphic(pins);
  }

  private static boolean hasRaisedPin(boolean[] pins) {
    for (boolean pin : pins) {
      if (pin) {
        return true;
      }
    }
    return false;
  }
}
