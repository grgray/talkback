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

import androidx.annotation.Nullable;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns Dot Pad key notifications into chords.
 *
 * <p>Each key notification carries the full pressed state of one key group as a bitmask, where key
 * {@code k} is bit {@code 7 - (k % 8)} of byte {@code k / 8}. A gesture starts when any key goes
 * down and ends when every group reports no keys pressed; the chord is every key that was down at
 * any point during the gesture. This lets two keys be pressed and released slightly apart without
 * a fixed timing window. Not thread-safe.
 */
public final class DotPadKeyDecoder {
  public static final String KEY_PAN_LEFT = "PanLeft";
  public static final String KEY_PAN_RIGHT = "PanRight";
  private static final int PERKINS_PAN_LEFT = 13;
  private static final int PERKINS_PAN_RIGHT = 14;

  private final Set<Integer> groupsHeld = new HashSet<>();
  private final Set<String> chord = new TreeSet<>();

  /** Returns true if {@code command} is one of the key notification commands. */
  public static boolean isKeyPacket(int command) {
    return command == DotPadPacket.NTF_KEYS_SCROLL
        || command == DotPadPacket.NTF_KEYS_PERKINS
        || command == DotPadPacket.NTF_KEYS_ROUTING
        || command == DotPadPacket.NTF_KEYS_FUNCTION;
  }

  /**
   * Processes a key notification. Returns the completed chord (key names, e.g. {@code F1}, {@code
   * PanLeft}) when the last key of a gesture is released, otherwise {@code null}.
   */
  @Nullable
  public Set<String> onKeyPacket(DotPadPacket packet) {
    int group = packet.getCommand();
    Set<String> pressed = decodePressedKeys(group, packet.getData());
    if (pressed.isEmpty()) {
      groupsHeld.remove(group);
    } else {
      groupsHeld.add(group);
      chord.addAll(pressed);
    }
    if (!groupsHeld.isEmpty() || chord.isEmpty()) {
      return null;
    }
    Set<String> completed = Collections.unmodifiableSet(new TreeSet<>(chord));
    chord.clear();
    return completed;
  }

  /** Forgets any gesture in progress, e.g. after a disconnect. */
  public void reset() {
    groupsHeld.clear();
    chord.clear();
  }

  /** Returns the names of the keys whose bits are set in {@code data} for key group {@code group}. */
  public static Set<String> decodePressedKeys(int group, byte[] data) {
    Set<String> keys = new TreeSet<>();
    for (int byteIndex = 0; byteIndex < data.length; byteIndex++) {
      int bits = data[byteIndex] & 0xFF;
      for (int bit = 0; bit < 8; bit++) {
        if ((bits & (0x80 >> bit)) != 0) {
          keys.add(keyName(group, byteIndex * 8 + bit));
        }
      }
    }
    return keys;
  }

  private static String keyName(int group, int index) {
    switch (group) {
      case DotPadPacket.NTF_KEYS_FUNCTION:
        return "F" + (index + 1);
      case DotPadPacket.NTF_KEYS_PERKINS:
        if (index == PERKINS_PAN_LEFT) {
          return KEY_PAN_LEFT;
        } else if (index == PERKINS_PAN_RIGHT) {
          return KEY_PAN_RIGHT;
        }
        return "Perkins" + index;
      case DotPadPacket.NTF_KEYS_ROUTING:
        return "Routing" + index;
      default:
        return "Scroll" + index;
    }
  }
}
