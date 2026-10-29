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

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.braille.brailledisplay.controller.BdController.BehaviorFocus;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.traversal.TraversalStrategy;
import com.google.android.accessibility.utils.traversal.TraversalStrategyUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds the content for a multi-line braille display so that reading uses the whole display.
 *
 * <p>The content is the focused item followed by the items after it, in the order the user would
 * reach them with "next item". Each item starts on a new line. Panning then moves through this text
 * a page at a time, across item boundaries, and the caller moves focus to the item on the top line.
 */
class MultiLineReader {
  /** Most items to put in one content. */
  @VisibleForTesting static final int MAX_ITEMS = 40;

  /** Pages of text to load, counting from the first item, so a pan forward is always full. */
  @VisibleForTesting static final int PAGES_AHEAD = 3;

  /**
   * Starts each item on a new line. The space follows {@link NodeBrailler}, which puts one after
   * every newline.
   */
  private static final String ITEM_SEPARATOR = "\n ";

  /** Most nodes to look at when searching for items, including ones with no text. */
  private static final int MAX_SEARCH_STEPS = 200;

  /** How far up the tree to look when checking whether one item is inside another. */
  private static final int MAX_ANCESTOR_DEPTH = 10;

  /** Marks the text that comes from one item. */
  private static final class ItemSpan {
    final AccessibilityNodeInfoCompat node;

    ItemSpan(AccessibilityNodeInfoCompat node) {
      this.node = node;
    }
  }

  /** An item in the content, and where its text starts. */
  static final class Item {
    final AccessibilityNodeInfoCompat node;
    final int start;

    Item(AccessibilityNodeInfoCompat node, int start) {
      this.node = node;
      this.start = start;
    }
  }

  private final NodeBrailler nodeBrailler;
  private final BehaviorFocus behaviorFocus;

  MultiLineReader(NodeBrailler nodeBrailler, BehaviorFocus behaviorFocus) {
    this.nodeBrailler = nodeBrailler;
    this.behaviorFocus = behaviorFocus;
  }

  /**
   * Returns the content starting with {@code first}, followed by the items after it until about
   * {@link #PAGES_AHEAD} pages of a {@code width} by {@code rows} display are filled.
   */
  CellsContent brailleFrom(
      AccessibilityNodeInfoCompat first, @Nullable AccessibilityEvent event, int width, int rows) {
    SpannableStringBuilder text = new SpannableStringBuilder();
    CharSequence firstText = nodeBrailler.brailleNodeAndEvent(first, event).getText();
    appendItem(text, first, firstText);
    int lines = estimateLines(firstText.length(), width);
    List<CharSequence> texts = new ArrayList<>();
    List<AccessibilityNodeInfoCompat> nodes =
        collect(
            first, TraversalStrategy.SEARCH_FOCUS_FORWARD, width, rows * PAGES_AHEAD - lines, texts);
    for (int i = 0; i < nodes.size(); i++) {
      appendItem(text, nodes.get(i), texts.get(i));
    }
    return new CellsContent(text).setSplitParagraphs(true);
  }

  /**
   * Returns the content of the items before {@code first}, enough to fill a page of a {@code width}
   * by {@code rows} display, set to show its last page. Returns {@code null} if there are no items
   * before {@code first}.
   */
  @Nullable
  CellsContent brailleBefore(AccessibilityNodeInfoCompat first, int width, int rows) {
    List<CharSequence> texts = new ArrayList<>();
    // One line more than a page, as the estimate can be a little over.
    List<AccessibilityNodeInfoCompat> nodes =
        collect(first, TraversalStrategy.SEARCH_FOCUS_BACKWARD, width, rows + 1, texts);
    if (nodes.isEmpty()) {
      return null;
    }
    Collections.reverse(nodes);
    Collections.reverse(texts);
    SpannableStringBuilder text = new SpannableStringBuilder();
    for (int i = 0; i < nodes.size(); i++) {
      appendItem(text, nodes.get(i), texts.get(i));
    }
    return new CellsContent(text)
        .setSplitParagraphs(true)
        .setPanStrategy(CellsContent.PAN_LAST_PAGE);
  }

  /** Returns the next item after {@code node} that has text, or {@code null}. */
  @Nullable
  AccessibilityNodeInfoCompat findNext(AccessibilityNodeInfoCompat node) {
    List<CharSequence> texts = new ArrayList<>();
    List<AccessibilityNodeInfoCompat> nodes =
        collect(node, TraversalStrategy.SEARCH_FOCUS_FORWARD, /* width= */ 1, /* lines= */ 1, texts);
    return nodes.isEmpty() ? null : nodes.get(0);
  }

  /** Returns the item whose text contains {@code position}, or the next one after it. */
  @Nullable
  static Item findItemAt(CharSequence text, int position) {
    List<Item> items = getItems(text);
    if (items.isEmpty()) {
      return null;
    }
    int[] starts = new int[items.size()];
    for (int i = 0; i < starts.length; i++) {
      starts[i] = items.get(i).start;
    }
    return items.get(findItemIndex(starts, position));
  }

  /** Returns the first item in {@code text}, or {@code null}. */
  @Nullable
  static Item findFirstItem(CharSequence text) {
    List<Item> items = getItems(text);
    return items.isEmpty() ? null : items.get(0);
  }

  /** Returns the last item in {@code text}, or {@code null}. */
  @Nullable
  static Item findLastItem(CharSequence text) {
    List<Item> items = getItems(text);
    return items.isEmpty() ? null : items.get(items.size() - 1);
  }

  /**
   * Returns the index of the item that {@code position} falls in, given each item's start in
   * ascending order. A position in the separator before an item belongs to that item.
   */
  @VisibleForTesting
  static int findItemIndex(int[] starts, int position) {
    for (int i = starts.length - 1; i > 0; i--) {
      // Text before an item's start, after the previous item's end, is the separator; it is
      // skipped by line wrapping, so count it as part of the item that follows.
      if (position >= starts[i] - ITEM_SEPARATOR.length()) {
        return i;
      }
    }
    return 0;
  }

  /** Estimates how many lines of a {@code width}-cell display {@code length} characters fill. */
  @VisibleForTesting
  static int estimateLines(int length, int width) {
    if (width <= 0) {
      return 1;
    }
    return Math.max(1, (length + width - 1) / width);
  }

  /**
   * Collects the items from {@code start} in {@code direction} until about {@code lines} lines of a
   * {@code width}-cell display are filled, skipping items with no text. Their text is added to
   * {@code texts}.
   */
  private List<AccessibilityNodeInfoCompat> collect(
      AccessibilityNodeInfoCompat start,
      @TraversalStrategy.SearchDirection int direction,
      int width,
      int lines,
      List<CharSequence> texts) {
    List<AccessibilityNodeInfoCompat> nodes = new ArrayList<>();
    if (lines <= 0) {
      return nodes;
    }
    AccessibilityNodeInfoCompat root = AccessibilityNodeInfoUtils.getRoot(start);
    if (root == null) {
      return nodes;
    }
    // Include the content of web views. TalkBack's own navigation asks the browser for the next
    // element instead, so its traversal order leaves web content out by default.
    TraversalStrategy traversal =
        TraversalStrategyUtils.getTraversalStrategy(
            root,
            behaviorFocus.createFocusFinder(),
            direction,
            /* includeChildrenOfNodesWithWebActions= */ true,
            /* makeFabFirst= */ false);
    CharSequence startText = nodeBrailler.brailleNode(start).getText();
    AccessibilityNodeInfoCompat node = start;
    int filled = 0;
    for (int steps = 0;
        steps < MAX_SEARCH_STEPS && filled < lines && nodes.size() < MAX_ITEMS;
        steps++) {
      node =
          TraversalStrategyUtils.searchFocus(
              traversal,
              node,
              direction,
              AccessibilityNodeInfoUtils.FILTER_SHOULD_FOCUS_EXCEPT_WEB_VIEW);
      if (node == null) {
        break;
      }
      CharSequence text = nodeBrailler.brailleNode(node).getText();
      if (!hasWords(text)) {
        // Nothing to read, such as an unlabelled image or link: it would only show a role mark.
        continue;
      }
      int last = nodes.size() - 1;
      AccessibilityNodeInfoCompat previous = last >= 0 ? nodes.get(last) : start;
      CharSequence previousText = last >= 0 ? texts.get(last) : startText;
      if (direction == TraversalStrategy.SEARCH_FOCUS_FORWARD) {
        // Web pages often expose an element and the text inside it as separate items, such as a
        // link and its label. Skip the inner one if its words are already shown.
        if (isAncestor(previous, node) && contains(previousText, text)) {
          continue;
        }
      } else if (last >= 0 && isAncestor(node, previous) && contains(text, previousText)) {
        // Going backwards the inner item comes first: replace it with the element around it.
        nodes.remove(last);
        texts.remove(last);
        filled -= estimateLines(previousText.length(), width);
      } else if (last < 0 && isAncestor(node, start)) {
        // The element around the item we started from.
        continue;
      }
      nodes.add(node);
      texts.add(text);
      filled += estimateLines(text.length(), width);
    }
    return nodes;
  }

  /** Returns whether {@code text} has a letter or digit, not just spaces and symbols. */
  @VisibleForTesting
  static boolean hasWords(@Nullable CharSequence text) {
    if (text == null) {
      return false;
    }
    for (int i = 0; i < text.length(); i++) {
      if (Character.isLetterOrDigit(text.charAt(i))) {
        return true;
      }
    }
    return false;
  }

  /** Returns whether {@code outer} has all of {@code inner}'s text, ignoring spaces at the ends. */
  @VisibleForTesting
  static boolean contains(CharSequence outer, CharSequence inner) {
    return outer.toString().contains(inner.toString().trim());
  }

  private static boolean isAncestor(
      AccessibilityNodeInfoCompat ancestor, AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat current = node.getParent();
    for (int depth = 0; current != null && depth < MAX_ANCESTOR_DEPTH; depth++) {
      if (current.equals(ancestor)) {
        return true;
      }
      current = current.getParent();
    }
    return false;
  }

  private static void appendItem(
      SpannableStringBuilder text, AccessibilityNodeInfoCompat node, CharSequence itemText) {
    if (text.length() > 0) {
      text.append(ITEM_SEPARATOR);
    }
    int start = text.length();
    text.append(itemText);
    text.setSpan(new ItemSpan(node), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
  }

  private static List<Item> getItems(CharSequence text) {
    List<Item> items = new ArrayList<>();
    if (!(text instanceof Spanned spanned)) {
      return items;
    }
    ItemSpan[] spans = spanned.getSpans(0, spanned.length(), ItemSpan.class);
    for (ItemSpan span : spans) {
      items.add(new Item(span.node, spanned.getSpanStart(span)));
    }
    Collections.sort(items, (a, b) -> Integer.compare(a.start, b.start));
    return items;
  }
}
