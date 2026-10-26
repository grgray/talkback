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

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reassembles {@link DotPadPacket}s from a byte stream.
 *
 * <p>Transports deliver bytes in arbitrary chunks (BLE notifications are bounded by the MTU, USB
 * bulk reads by the endpoint size), so a packet may be split across several chunks and one chunk
 * may hold several packets. Bytes before a sync sequence and packets with a bad checksum are
 * dropped. Not thread-safe.
 */
public final class DotPadPacketParser {
  /** Largest length field we accept; anything larger is treated as stream corruption. */
  private static final int MAX_PACKET_LENGTH = 1024;

  private byte[] buffer = new byte[0];
  private int droppedPacketCount;

  /** Appends {@code chunk} to the stream and returns every complete, valid packet now available. */
  public List<DotPadPacket> feed(byte[] chunk) {
    ByteArrayOutputStream joined = new ByteArrayOutputStream(buffer.length + chunk.length);
    joined.write(buffer, 0, buffer.length);
    joined.write(chunk, 0, chunk.length);
    byte[] bytes = joined.toByteArray();

    List<DotPadPacket> packets = new ArrayList<>();
    int pos = 0;
    while (true) {
      int start = findSync(bytes, pos);
      if (start < 0) {
        // Keep a trailing 0xAA: it may be the first half of a sync split across chunks.
        pos = (bytes.length > 0 && (bytes[bytes.length - 1] & 0xFF) == DotPadPacket.SYNC_1)
            ? bytes.length - 1
            : bytes.length;
        break;
      }
      if (bytes.length - start < DotPadPacket.PREFIX_LENGTH) {
        pos = start;
        break;
      }
      int length = ((bytes[start + 2] & 0xFF) << 8) | (bytes[start + 3] & 0xFF);
      if (length < DotPadPacket.OVERHEAD_LENGTH || length > MAX_PACKET_LENGTH) {
        droppedPacketCount++;
        pos = start + 1;
        continue;
      }
      int end = start + DotPadPacket.PREFIX_LENGTH + length;
      if (end > bytes.length) {
        pos = start;
        break;
      }
      int body = start + DotPadPacket.PREFIX_LENGTH;
      int expected = DotPadPacket.checksum(bytes, body, length - 1);
      if ((bytes[end - 1] & 0xFF) != expected) {
        droppedPacketCount++;
        pos = start + 1;
        continue;
      }
      int command = ((bytes[body + 1] & 0xFF) << 8) | (bytes[body + 2] & 0xFF);
      packets.add(
          new DotPadPacket(
              bytes[body] & 0xFF,
              command,
              bytes[body + 3] & 0xFF,
              Arrays.copyOfRange(bytes, body + 4, end - 1)));
      pos = end;
    }
    buffer = Arrays.copyOfRange(bytes, pos, bytes.length);
    return packets;
  }

  /** Discards any partially received packet. */
  public void reset() {
    buffer = new byte[0];
  }

  /** Number of packets discarded because of a bad length or checksum. */
  public int getDroppedPacketCount() {
    return droppedPacketCount;
  }

  private static int findSync(byte[] bytes, int from) {
    for (int i = from; i + 1 < bytes.length; i++) {
      if ((bytes[i] & 0xFF) == DotPadPacket.SYNC_1
          && (bytes[i + 1] & 0xFF) == DotPadPacket.SYNC_2) {
        return i;
      }
    }
    return -1;
  }
}
