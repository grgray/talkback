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
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.GestureDescription.StrokeDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.ViewConfiguration;
import android.text.TextUtils;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;
import androidx.core.view.accessibility.AccessibilityWindowInfoCompat;
import com.google.android.accessibility.braille.brailledisplay.BrailleDisplayLog;
import com.google.android.accessibility.braille.brailledisplay.R;
import com.google.android.accessibility.braille.brailledisplay.platform.TactileGraphicsDisplay;
import com.google.android.accessibility.braille.common.BrailleCommonTalkBackSpeaker;
import com.google.android.accessibility.braille.common.TalkBackSpeaker.AnnounceType;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.FeatureSupport;
import com.google.android.accessibility.utils.screencapture.ScreenshotCapture;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Draws pictures on a braille display that can show tactile graphics.
 *
 * <p>The picture is captured from the screen where the item is shown, converted to pins with
 * {@link TactileImageConverter}, and drawn in place of the braille. Authors mark the pictures to
 * draw automatically with {@code aria-roledescription="tactile graphic"} or alt text starting
 * "Tactile:"; any focused item can also be drawn on request.
 *
 * <p>A picture partly scrolled out of view is scrolled into view first, so the whole picture is
 * drawn. TalkBack asks the app to show it on screen, and if the app doesn't move it (browsers treat
 * a partly visible element as already on screen) it drags the page by just enough, through a touch
 * exploration passthrough region. If it still isn't all on screen, the part on screen is drawn;
 * the user is told only when the picture is too tall to fit, so no scroll could show all of it.
 *
 * <p>While a picture is drawn, its alt text is shown on the display's separate braille line, if it
 * has one, and the panning keys move through it a line at a time, breaking between words.
 */
class TactileGraphicsDrawer {
  private static final String TAG = "TactileGraphicsDrawer";

  /** How often the picture's position is checked while the app scrolls it into view. */
  private static final long SCROLL_POLL_MS = 100;
  /** Shortest wait for a scroll to start, in case the app is slow to begin moving the picture. */
  private static final long SCROLL_MIN_WAIT_MS = 400;
  /** Longest wait for a scroll to finish before capturing whatever is on screen. */
  private static final long SCROLL_MAX_WAIT_MS = 1500;
  /** Checks with the picture in the same place that count as the scroll having stopped. */
  private static final int SCROLL_STABLE_POLLS = 3;
  /** Scroll attempts before drawing whatever is on screen: show on screen, then up to 2 drags. */
  private static final int MAX_SCROLL_ATTEMPTS = 3;
  /** How long the drag that scrolls a picture into view moves for. */
  private static final long DRAG_MOVE_MS = 500;
  /** How long the drag holds still before lifting, so the app doesn't fling the page. */
  private static final long DRAG_HOLD_MS = 200;
  /**
   * Wait for the passthrough region to take effect before dragging. With 150 ms the first drag was
   * sometimes still caught by touch exploration.
   */
  private static final long PASSTHROUGH_SET_DELAY_MS = 400;
  /** Wait for the drag's touch events to pass before touch exploration covers the page again. */
  private static final long PASSTHROUGH_CLEAR_DELAY_MS = 300;
  /** Gap left between a picture scrolled into view and the edge of the area it scrolls in. */
  private static final int VIEWPORT_MARGIN_DIVISOR = 20;
  /** Deepest ancestor looked at when finding the area a picture scrolls in. */
  private static final int MAX_ANCESTOR_DEPTH = 64;
  /** Class name Chrome and WebView give the node holding a web page's visible area. */
  private static final String WEB_VIEW_CLASS_NAME = "android.webkit.WebView";

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
  /** Translates text into braille cells, or returns {@code null} if it can't. */
  private final Function<CharSequence, byte[]> translator;
  private final Handler handler = new Handler(Looper.getMainLooper());
  /** Item drawn, or being captured for drawing; {@code null} if none. */
  @Nullable private AccessibilityNodeInfoCompat drawnNode;
  /** Increased on every request and clear, so a late capture for an old request is dropped. */
  private int requestId;
  /** Whether a passthrough region is set for a drag, and must be cleared. */
  private boolean passthroughSet;
  /** Alt text of the drawn picture in braille, or {@code null} if none is on the braille line. */
  @Nullable private byte[] altTextCells;
  /** Start and end (exclusive) of each braille line's worth of {@link #altTextCells}. */
  private int[][] altTextLines = new int[0][];
  /** Index in {@link #altTextLines} of the part shown on the braille line. */
  private int altTextLine;

  TactileGraphicsDrawer(
      Supplier<AccessibilityService> serviceSupplier,
      FailureListener failureListener,
      Function<CharSequence, byte[]> translator) {
    this.serviceSupplier = serviceSupplier;
    this.failureListener = failureListener;
    this.translator = translator;
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
   * Captures {@code node} from the screen and draws it, scrolling it into view first if part of it
   * is out of view. Returns {@code false} if it can't be started; a failure after that is reported
   * to the {@link FailureListener}.
   */
  boolean draw(AccessibilityNodeInfoCompat node) {
    AccessibilityService service = serviceSupplier.get();
    if (!canDraw() || service == null) {
      return false;
    }
    node.refresh();
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    if (bounds.isEmpty()) {
      return false;
    }
    int id = ++requestId;
    drawnNode = node;
    if (isFullyInView(node, bounds)) {
      capture(id, bounds, /* tooLarge= */ false);
    } else {
      scrollIntoView(id, node, bounds, /* attempt= */ 0);
    }
    return true;
  }

  /**
   * Makes scroll attempt {@code attempt} to bring {@code node} into view, and waits for it to
   * finish; draws the part on screen if no more can be tried.
   */
  private void scrollIntoView(int id, AccessibilityNodeInfoCompat node, Rect bounds, int attempt) {
    if (!canDraw()) {
      // The display went away, or was lent to another app: there's nothing to draw for.
      drawnNode = null;
      return;
    }
    if (attempt >= MAX_SCROLL_ATTEMPTS) {
      capture(id, bounds, isTooLarge(node, bounds));
      return;
    }
    // Browsers don't scroll a partly visible element for "show on screen", so go straight to
    // dragging in web content.
    if (attempt == 0
        && !isInWebContent(node)
        && node.performAction(AccessibilityActionCompat.ACTION_SHOW_ON_SCREEN.getId())) {
      waitForScroll(id, node, bounds, SystemClock.uptimeMillis(), /* stablePolls= */ 0, attempt);
      return;
    }
    if (!dragIntoView(id, node, bounds, attempt)) {
      // It can't be scrolled any further; draw the part on screen.
      capture(id, bounds, isTooLarge(node, bounds));
    }
  }

  /**
   * Drags the page by just enough to bring {@code node}, cut off at the top or bottom of the area
   * it scrolls in, all into view, then waits for the page to stop. Returns {@code false} if it
   * can't: the picture is cut off at both ends or at a side, or touch exploration passthrough isn't
   * available.
   */
  private boolean dragIntoView(int id, AccessibilityNodeInfoCompat node, Rect bounds, int attempt) {
    AccessibilityService service = serviceSupplier.get();
    Rect viewport = viewportOf(node);
    if (service == null
        || viewport == null
        || viewport.isEmpty()
        || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      return false;
    }
    boolean cutTop = bounds.top <= viewport.top;
    boolean cutBottom = bounds.bottom >= viewport.bottom;
    if (cutTop == cutBottom) {
      // Taller than the area it scrolls in, or cut off at a side.
      return false;
    }
    int margin = Math.max(viewport.height() / VIEWPORT_MARGIN_DIVISOR, 1);
    int slop = ViewConfiguration.get(service).getScaledTouchSlop();
    float startY;
    float distance;
    if (cutBottom) {
      // Move the page up until the picture's top is just below the top of the area.
      distance = -(bounds.top - (viewport.top + margin));
      if (distance >= 0) {
        return false;
      }
      distance -= slop;
      startY = viewport.bottom - margin;
    } else {
      // Move the page down until the picture's bottom is just above the bottom of the area.
      distance = (viewport.bottom - margin) - bounds.bottom;
      if (distance <= 0) {
        return false;
      }
      distance += slop;
      startY = viewport.top + margin;
    }
    float endY = Math.max(viewport.top + 1, Math.min(viewport.bottom - 1, startY + distance));
    float x = viewport.exactCenterX();

    Path move = new Path();
    move.moveTo(x, startY);
    move.lineTo(x, endY);
    StrokeDescription moveStroke =
        new StrokeDescription(move, /* startTime= */ 0, DRAG_MOVE_MS, /* willContinue= */ true);
    Path hold = new Path();
    hold.moveTo(x, endY);
    hold.lineTo(x, endY);
    StrokeDescription holdStroke =
        moveStroke.continueStroke(hold, /* startTime= */ 0, DRAG_HOLD_MS, /* willContinue= */ false);

    long dragStart = SystemClock.uptimeMillis();
    AccessibilityService.GestureResultCallback afterHold =
        new AccessibilityService.GestureResultCallback() {
          @Override
          public void onCompleted(GestureDescription gesture) {
            clearPassthroughLater(service);
            if (id == requestId) {
              waitForScroll(id, node, bounds, dragStart, /* stablePolls= */ 0, attempt);
            }
          }

          @Override
          public void onCancelled(GestureDescription gesture) {
            onDragCancelled(id, service, bounds);
          }
        };
    AccessibilityService.GestureResultCallback afterMove =
        new AccessibilityService.GestureResultCallback() {
          @Override
          public void onCompleted(GestureDescription gesture) {
            GestureDescription second =
                new GestureDescription.Builder().addStroke(holdStroke).build();
            if (!service.dispatchGesture(second, afterHold, /* handler= */ null)) {
              onDragCancelled(id, service, bounds);
            }
          }

          @Override
          public void onCancelled(GestureDescription gesture) {
            onDragCancelled(id, service, bounds);
          }
        };

    // While touch exploration is on, a drag only explores. A passthrough region over the page
    // sends touches there straight to the app, so the drag scrolls it.
    service.setTouchExplorationPassthroughRegion(Display.DEFAULT_DISPLAY, new Region(viewport));
    passthroughSet = true;
    GestureDescription first = new GestureDescription.Builder().addStroke(moveStroke).build();
    handler.postDelayed(
        () -> {
          if (id != requestId || !canDraw()) {
            clearPassthroughNow(service);
            if (id == requestId) {
              drawnNode = null;
            }
            return;
          }
          if (!service.dispatchGesture(first, afterMove, /* handler= */ null)) {
            onDragCancelled(id, service, bounds);
          }
        },
        PASSTHROUGH_SET_DELAY_MS);
    return true;
  }

  private void onDragCancelled(int id, AccessibilityService service, Rect bounds) {
    BrailleDisplayLog.w(TAG, "The drag to scroll the picture into view was cancelled");
    clearPassthroughLater(service);
    if (id == requestId) {
      capture(id, bounds, /* tooLarge= */ false);
    }
  }

  private void clearPassthroughLater(AccessibilityService service) {
    // A separate handler, so clear() can't cancel it.
    new Handler(Looper.getMainLooper())
        .postDelayed(() -> clearPassthroughNow(service), PASSTHROUGH_CLEAR_DELAY_MS);
  }

  private void clearPassthroughNow(AccessibilityService service) {
    if (passthroughSet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      service.setTouchExplorationPassthroughRegion(Display.DEFAULT_DISPLAY, new Region());
    }
    passthroughSet = false;
  }

  /** Returns whether {@code node} is in a web page. */
  private static boolean isInWebContent(AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat ancestor = node.getParent();
    for (int depth = 0; ancestor != null && depth < MAX_ANCESTOR_DEPTH; depth++) {
      CharSequence className = ancestor.getClassName();
      if (className != null && WEB_VIEW_CLASS_NAME.contentEquals(className)) {
        return true;
      }
      ancestor = ancestor.getParent();
    }
    return false;
  }

  /** Stops drawing, showing the braille again. */
  void clear() {
    requestId++;
    drawnNode = null;
    handler.removeCallbacksAndMessages(null);
    AccessibilityService service = serviceSupplier.get();
    if (passthroughSet && service != null) {
      clearPassthroughNow(service);
    }
    TactileGraphicsDisplay display = TactileGraphicsDisplay.getCurrent();
    if (display != null && display.isShowingGraphic()) {
      display.clearGraphic();
    }
    if (altTextCells != null && display != null) {
      display.showBrailleLine(null);
    }
    altTextCells = null;
    altTextLines = new int[0][];
    altTextLine = 0;
  }

  /**
   * Moves the braille line on to the next ({@code forward}) or previous part of the drawn picture's
   * alt text. Returns {@code false} if nothing is drawn or there is no more in that direction, so
   * the caller can leave the picture.
   */
  boolean panBrailleLine(boolean forward) {
    TactileGraphicsDisplay display = TactileGraphicsDisplay.getCurrent();
    if (altTextCells == null || display == null || !display.isShowingGraphic()) {
      return false;
    }
    int next = altTextLine + (forward ? 1 : -1);
    if (next < 0 || next >= altTextLines.length) {
      return false;
    }
    altTextLine = next;
    showAltTextLine(display);
    return true;
  }

  /** Shows the drawn picture's alt text on the display's braille line, from the start. */
  private void showAltText(
      TactileGraphicsDisplay display, @Nullable AccessibilityNodeInfoCompat node) {
    int width = display.getBrailleLineCells();
    CharSequence text = node == null ? null : altText(node);
    byte[] cells = (width <= 0 || TextUtils.isEmpty(text)) ? null : translator.apply(text);
    if (cells == null || cells.length == 0) {
      return;
    }
    altTextCells = cells;
    altTextLines = wrap(cells, width);
    altTextLine = 0;
    showAltTextLine(display);
  }

  private void showAltTextLine(TactileGraphicsDisplay display) {
    if (altTextCells == null || altTextLines.length == 0) {
      return;
    }
    int[] line = altTextLines[altTextLine];
    byte[] cells = new byte[line[1] - line[0]];
    System.arraycopy(altTextCells, line[0], cells, 0, cells.length);
    display.showBrailleLine(cells);
  }

  /** Returns {@code node}'s alt text without the marker word, or {@code null} if it has none. */
  @Nullable
  static CharSequence altText(AccessibilityNodeInfoCompat node) {
    CharSequence text = node.getContentDescription();
    if (TextUtils.isEmpty(text)) {
      text = node.getText();
    }
    if (TextUtils.isEmpty(text)) {
      return null;
    }
    int markerEnd = markerEnd(text);
    String result = (markerEnd >= 0 ? text.subSequence(markerEnd, text.length()) : text).toString();
    result = result.trim();
    return result.isEmpty() ? null : result;
  }

  /**
   * Splits braille {@code cells} into lines of at most {@code width} cells, breaking after the last
   * blank cell that fits where it can, and leaving out the blanks between lines. Returns each line's
   * start and end (exclusive).
   */
  @VisibleForTesting
  static int[][] wrap(byte[] cells, int width) {
    List<int[]> lines = new ArrayList<>();
    int start = skipBlanks(cells, 0);
    while (start < cells.length) {
      int end;
      if (cells.length - start <= width) {
        end = cells.length;
      } else {
        end = start + width;
        for (int i = start + width; i > start; i--) {
          if (cells[i] == 0) {
            end = i;
            break;
          }
        }
        // Leave out any blanks just before the break too.
        while (end > start + 1 && cells[end - 1] == 0) {
          end--;
        }
      }
      lines.add(new int[] {start, end});
      start = skipBlanks(cells, end);
    }
    return lines.toArray(new int[0][]);
  }

  private static int skipBlanks(byte[] cells, int from) {
    while (from < cells.length && cells[from] == 0) {
      from++;
    }
    return from;
  }

  /**
   * Checks the picture's position every {@link #SCROLL_POLL_MS} until it is in view and has
   * stopped moving, or the scroll seems to have finished without bringing it all into view, and
   * then captures it.
   */
  private void waitForScroll(
      int id,
      AccessibilityNodeInfoCompat node,
      Rect lastBounds,
      long start,
      int stablePolls,
      int attempt) {
    handler.postDelayed(
        () -> {
          if (id != requestId) {
            return;
          }
          Rect bounds = new Rect();
          if (!node.refresh() || isEmpty(node, bounds)) {
            BrailleDisplayLog.w(TAG, "The picture went away while scrolling");
            drawnNode = null;
            failureListener.onDrawFailed();
            return;
          }
          int stable = bounds.equals(lastBounds) ? stablePolls + 1 : 0;
          long elapsed = SystemClock.uptimeMillis() - start;
          boolean inView = isFullyInView(node, bounds);
          if (inView && stable >= 1) {
            capture(id, bounds, /* tooLarge= */ false);
          } else if ((elapsed >= SCROLL_MIN_WAIT_MS && stable >= SCROLL_STABLE_POLLS)
              || elapsed >= SCROLL_MAX_WAIT_MS) {
            // Stopped without bringing it all into view: try the next way of scrolling.
            scrollIntoView(id, node, bounds, attempt + 1);
          } else {
            waitForScroll(id, node, bounds, start, stable, attempt);
          }
        },
        SCROLL_POLL_MS);
  }

  /** Puts {@code node}'s bounds in {@code bounds}, and returns whether they are empty. */
  private static boolean isEmpty(AccessibilityNodeInfoCompat node, Rect bounds) {
    node.getBoundsInScreen(bounds);
    return bounds.isEmpty();
  }

  /**
   * Captures the picture at {@code bounds} and draws it. If {@code tooLarge}, the user is told that
   * only part of it could be drawn.
   */
  private void capture(int id, Rect bounds, boolean tooLarge) {
    AccessibilityService service = serviceSupplier.get();
    if (service == null) {
      drawnNode = null;
      failureListener.onDrawFailed();
      return;
    }
    ScreenshotCapture.takeScreenshot(
        service,
        (bitmap, isFormatSupported) -> onCaptured(id, bounds, bitmap, tooLarge),
        service.getMainExecutor());
  }

  /**
   * Returns whether all of {@code node}, shown at {@code bounds}, is in view.
   *
   * <p>Android reports only the part of an item that is on screen, so an item cut off by the edge
   * of the area it scrolls in reaches that edge. An item that touches an edge of its window, of a
   * scrolling ancestor or of a web page's visible area is taken to be cut off.
   */
  private static boolean isFullyInView(AccessibilityNodeInfoCompat node, Rect bounds) {
    Rect viewport = viewportOf(node);
    return viewport != null && (viewport.isEmpty() || isInside(bounds, viewport));
  }

  /**
   * Returns the area {@code node} can be seen in: its window, narrowed to each scrolling ancestor
   * and web page's visible area; empty if none is known, {@code null} if they don't overlap.
   */
  @Nullable
  private static Rect viewportOf(AccessibilityNodeInfoCompat node) {
    Rect viewport = new Rect();
    AccessibilityWindowInfoCompat window = AccessibilityNodeInfoUtils.getWindow(node);
    if (window != null) {
      window.getBoundsInScreen(viewport);
    }
    Rect ancestorBounds = new Rect();
    AccessibilityNodeInfoCompat ancestor = node.getParent();
    for (int depth = 0; ancestor != null && depth < MAX_ANCESTOR_DEPTH; depth++) {
      CharSequence className = ancestor.getClassName();
      if (ancestor.isScrollable()
          || (className != null && WEB_VIEW_CLASS_NAME.contentEquals(className))) {
        ancestor.getBoundsInScreen(ancestorBounds);
        if (viewport.isEmpty()) {
          viewport.set(ancestorBounds);
        } else if (!viewport.intersect(ancestorBounds)) {
          return null;
        }
      }
      ancestor = ancestor.getParent();
    }
    return viewport;
  }

  /**
   * Returns whether {@code node}, shown at {@code bounds}, is too tall to fit in the area it scrolls
   * in, so no scroll can show all of it. It is cut off at the top or bottom, and the part on screen
   * fills all but the margins a scroll leaves: a picture that could fit shows less than that while
   * it is cut off.
   */
  private static boolean isTooLarge(AccessibilityNodeInfoCompat node, Rect bounds) {
    Rect viewport = viewportOf(node);
    if (viewport == null || viewport.isEmpty()) {
      return false;
    }
    boolean cutOff = bounds.top <= viewport.top || bounds.bottom >= viewport.bottom;
    int margin = Math.max(viewport.height() / VIEWPORT_MARGIN_DIVISOR, 1);
    return cutOff && bounds.height() >= viewport.height() - 2 * margin;
  }

  /** Returns whether {@code bounds} lies inside {@code viewport} without touching its edges. */
  @VisibleForTesting
  static boolean isInside(Rect bounds, Rect viewport) {
    return bounds.left > viewport.left
        && bounds.top > viewport.top
        && bounds.right < viewport.right
        && bounds.bottom < viewport.bottom;
  }

  private void onCaptured(int id, Rect bounds, @Nullable Bitmap screenshot, boolean tooLarge) {
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
    showAltText(display, drawnNode);
    if (tooLarge) {
      BrailleDisplayLog.d(TAG, "Drew the part of the picture on screen");
      AccessibilityService service = serviceSupplier.get();
      if (service != null) {
        BrailleCommonTalkBackSpeaker.getInstance()
            .speak(
                service.getString(R.string.bd_tactile_graphic_cut_off), AnnounceType.ENQUEUE);
      }
    }
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
