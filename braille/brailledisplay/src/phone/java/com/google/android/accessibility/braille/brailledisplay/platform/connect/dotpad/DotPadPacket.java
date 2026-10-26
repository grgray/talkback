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

import java.util.Arrays;
import java.util.Locale;

/**
 * A single Dot Pad protocol packet.
 *
 * <p>Wire format: {@code AA 55 | length (2, big-endian) | destination | command (2, big-endian) |
 * seq | data... | checksum}. {@code length} counts the bytes from {@code destination} through
 * {@code checksum}. The checksum is {@code 0xA5} XORed with every byte from {@code destination}
 * through the last data byte.
 */
public final class DotPadPacket {
  public static final int SYNC_1 = 0xAA;
  public static final int SYNC_2 = 0x55;
  /** Bytes before {@code destination}: two sync bytes and two length bytes. */
  public static final int PREFIX_LENGTH = 4;
  /** Bytes counted by {@code length} that are not data: destination, command (2), seq, checksum. */
  public static final int OVERHEAD_LENGTH = 5;
  private static final int CHECKSUM_SEED = 0xA5;

  // Commands.
  public static final int REQ_FIRMWARE_VERSION = 0x0000;
  public static final int RSP_FIRMWARE_VERSION = 0x0001;
  public static final int REQ_DEVICE_NAME = 0x0100;
  public static final int RSP_DEVICE_NAME = 0x0101;
  public static final int REQ_BOARD_INFORMATION = 0x0110;
  public static final int RSP_BOARD_INFORMATION = 0x0111;
  public static final int REQ_DISPLAY_LINE = 0x0200;
  public static final int RSP_DISPLAY_LINE = 0x0201;
  public static final int NTF_DISPLAY_LINE = 0x0202;
  public static final int REQ_DISPLAY_PARTIAL = 0x0220;
  public static final int RSP_DISPLAY_PARTIAL = 0x0221;
  public static final int NTF_DISPLAY_PARTIAL = 0x0222;
  public static final int NTF_KEYS_SCROLL = 0x0302;
  public static final int NTF_KEYS_PERKINS = 0x0312;
  public static final int NTF_KEYS_ROUTING = 0x0322;
  public static final int NTF_KEYS_FUNCTION = 0x0332;
  public static final int NTF_ERROR = 0x9902;

  /** {@code seq} value for text mode writes; {@code 0} selects graphic (raw pin) mode. */
  public static final int SEQ_TEXT_MODE = 0x80;
  public static final int SEQ_GRAPHIC_MODE = 0x00;

  // Display line response codes (first data byte of RSP_DISPLAY_LINE).
  public static final int DISPLAY_ACK = 0;
  public static final int DISPLAY_NACK = 1;
  public static final int DISPLAY_WAIT = 2;
  public static final int DISPLAY_CHECKSUM = 3;

  private final int destination;
  private final int command;
  private final int seq;
  private final byte[] data;

  public DotPadPacket(int destination, int command, int seq, byte[] data) {
    this.destination = destination & 0xFF;
    this.command = command & 0xFFFF;
    this.seq = seq & 0xFF;
    this.data = data.clone();
  }

  /** Creates a request with no data. */
  public static DotPadPacket request(int command) {
    return new DotPadPacket(/* destination= */ 0, command, /* seq= */ 0, new byte[0]);
  }

  /** Creates a graphic-mode display line request writing {@code cells} from {@code startCell}. */
  public static DotPadPacket displayLine(int lineId, int startCell, byte[] cells) {
    byte[] data = new byte[cells.length + 1];
    data[0] = (byte) startCell;
    System.arraycopy(cells, 0, data, 1, cells.length);
    return new DotPadPacket(lineId, REQ_DISPLAY_LINE, SEQ_GRAPHIC_MODE, data);
  }

  /**
   * Creates a partial display request that writes a rectangle of graphic-mode cells in one packet:
   * cells {@code startCell} (inclusive) to {@code endCell} (exclusive) of each of {@code
   * rows.length} consecutive lines starting at {@code firstLineId}. The display acknowledges with
   * {@link #RSP_DISPLAY_PARTIAL} and reports completion with {@link #NTF_DISPLAY_PARTIAL}.
   */
  public static DotPadPacket displayPartial(
      int firstLineId, int startCell, int endCell, byte[][] rows) {
    int width = endCell - startCell;
    byte[] data = new byte[2 + width * rows.length];
    data[0] = (byte) startCell;
    data[1] = (byte) endCell;
    for (int i = 0; i < rows.length; i++) {
      System.arraycopy(rows[i], startCell, data, 2 + i * width, width);
    }
    return new DotPadPacket(firstLineId, REQ_DISPLAY_PARTIAL, SEQ_GRAPHIC_MODE, data);
  }

  public int getDestination() {
    return destination;
  }

  public int getCommand() {
    return command;
  }

  public int getSeq() {
    return seq;
  }

  public byte[] getData() {
    return data.clone();
  }

  /** Returns the data byte at {@code index} as an unsigned value, or {@code -1} if absent. */
  public int getDataByte(int index) {
    return index < data.length ? data[index] & 0xFF : -1;
  }

  public int getDataLength() {
    return data.length;
  }

  /** Serializes this packet into its wire format. */
  public byte[] toBytes() {
    int length = data.length + OVERHEAD_LENGTH;
    byte[] bytes = new byte[PREFIX_LENGTH + length];
    bytes[0] = (byte) SYNC_1;
    bytes[1] = (byte) SYNC_2;
    bytes[2] = (byte) (length >> 8);
    bytes[3] = (byte) length;
    bytes[4] = (byte) destination;
    bytes[5] = (byte) (command >> 8);
    bytes[6] = (byte) command;
    bytes[7] = (byte) seq;
    System.arraycopy(data, 0, bytes, 8, data.length);
    bytes[bytes.length - 1] =
        (byte) checksum(bytes, PREFIX_LENGTH, bytes.length - 1 - PREFIX_LENGTH);
    return bytes;
  }

  /** Computes the checksum over {@code count} bytes of {@code bytes} starting at {@code from}. */
  public static int checksum(byte[] bytes, int from, int count) {
    int sum = CHECKSUM_SEED;
    for (int i = from; i < from + count; i++) {
      sum ^= bytes[i] & 0xFF;
    }
    return sum & 0xFF;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof DotPadPacket)) {
      return false;
    }
    DotPadPacket other = (DotPadPacket) o;
    return destination == other.destination
        && command == other.command
        && seq == other.seq
        && Arrays.equals(data, other.data);
  }

  @Override
  public int hashCode() {
    return ((destination * 31 + command) * 31 + seq) * 31 + Arrays.hashCode(data);
  }

  @Override
  public String toString() {
    StringBuilder sb =
        new StringBuilder(
            String.format(
                Locale.US, "DotPadPacket{dest=%d, cmd=%04X, seq=%02X, data=", destination, command,
                seq));
    for (byte b : data) {
      sb.append(String.format(Locale.US, "%02X", b & 0xFF));
    }
    return sb.append('}').toString();
  }
}
