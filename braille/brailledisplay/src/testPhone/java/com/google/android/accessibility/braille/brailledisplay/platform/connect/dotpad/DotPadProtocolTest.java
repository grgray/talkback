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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.android.accessibility.braille.brltty.BrailleInputEvent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for the Dot Pad protocol classes. */
@RunWith(JUnit4.class)
public final class DotPadProtocolTest {

  private static byte[] bytes(int... values) {
    byte[] result = new byte[values.length];
    for (int i = 0; i < values.length; i++) {
      result[i] = (byte) values[i];
    }
    return result;
  }

  // ---- DotPadPacket ----

  @Test
  public void request_encodesHeaderLengthAndChecksum() {
    byte[] encoded = DotPadPacket.request(DotPadPacket.REQ_BOARD_INFORMATION).toBytes();
    // Checksum: 0xA5 ^ 0x00 ^ 0x01 ^ 0x10 ^ 0x00 = 0xB4.
    assertArrayEquals(bytes(0xAA, 0x55, 0x00, 0x05, 0x00, 0x01, 0x10, 0x00, 0xB4), encoded);
  }

  @Test
  public void displayLine_prependsStartCellAndUsesGraphicMode() {
    DotPadPacket packet = DotPadPacket.displayLine(3, 0, bytes(0x11, 0x22));
    byte[] encoded = packet.toBytes();
    assertEquals(3, packet.getDestination());
    assertEquals(DotPadPacket.SEQ_GRAPHIC_MODE, packet.getSeq());
    assertArrayEquals(bytes(0x00, 0x11, 0x22), packet.getData());
    int checksum = 0xA5 ^ 0x03 ^ 0x02 ^ 0x00 ^ 0x00 ^ 0x00 ^ 0x11 ^ 0x22;
    assertArrayEquals(
        bytes(0xAA, 0x55, 0x00, 0x08, 0x03, 0x02, 0x00, 0x00, 0x00, 0x11, 0x22, checksum),
        encoded);
  }

  @Test
  public void displayPartial_packsRectangleRowByRow() {
    byte[][] rows = {bytes(0, 1, 2, 3, 4), bytes(10, 11, 12, 13, 14)};
    DotPadPacket packet = DotPadPacket.displayPartial(4, 1, 4, rows);
    assertEquals(4, packet.getDestination());
    assertEquals(DotPadPacket.REQ_DISPLAY_PARTIAL, packet.getCommand());
    assertEquals(DotPadPacket.SEQ_GRAPHIC_MODE, packet.getSeq());
    // [startCell, endCell (exclusive)] then cells 1..3 of each row.
    assertArrayEquals(bytes(1, 4, 1, 2, 3, 11, 12, 13), packet.getData());
    byte[] encoded = packet.toBytes();
    assertEquals(0x02, encoded[5]);
    assertEquals(0x20, encoded[6]);
    assertEquals(8 + 5, ((encoded[2] & 0xFF) << 8) | (encoded[3] & 0xFF));
  }

  // ---- DotPadPacketParser ----

  @Test
  public void parser_roundTripsPacket() {
    DotPadPacket packet = new DotPadPacket(5, DotPadPacket.NTF_DISPLAY_LINE, 0, bytes(0x01));
    List<DotPadPacket> parsed = new DotPadPacketParser().feed(packet.toBytes());
    assertEquals(Arrays.asList(packet), parsed);
  }

  @Test
  public void parser_reassemblesPacketSplitAcrossChunks() {
    byte[] encoded = DotPadPacket.displayLine(1, 0, new byte[30]).toBytes();
    DotPadPacketParser parser = new DotPadPacketParser();
    // Split inside the sync bytes, the header and the data.
    assertTrue(parser.feed(Arrays.copyOfRange(encoded, 0, 1)).isEmpty());
    assertTrue(parser.feed(Arrays.copyOfRange(encoded, 1, 6)).isEmpty());
    assertTrue(parser.feed(Arrays.copyOfRange(encoded, 6, 20)).isEmpty());
    List<DotPadPacket> parsed = parser.feed(Arrays.copyOfRange(encoded, 20, encoded.length));
    assertEquals(1, parsed.size());
    assertEquals(31, parsed.get(0).getDataLength());
  }

  @Test
  public void parser_handlesSeveralPacketsAndLeadingGarbage() {
    byte[] a = DotPadPacket.request(DotPadPacket.REQ_DEVICE_NAME).toBytes();
    byte[] b = new DotPadPacket(2, DotPadPacket.RSP_DISPLAY_LINE, 0, bytes(0)).toBytes();
    byte[] stream = new byte[3 + a.length + b.length];
    stream[0] = 0x12;
    stream[1] = (byte) 0xAA; // A lone sync byte that is not followed by 0x55.
    stream[2] = 0x00;
    System.arraycopy(a, 0, stream, 3, a.length);
    System.arraycopy(b, 0, stream, 3 + a.length, b.length);
    List<DotPadPacket> parsed = new DotPadPacketParser().feed(stream);
    assertEquals(2, parsed.size());
    assertEquals(DotPadPacket.REQ_DEVICE_NAME, parsed.get(0).getCommand());
    assertEquals(DotPadPacket.RSP_DISPLAY_LINE, parsed.get(1).getCommand());
  }

  @Test
  public void parser_dropsPacketWithBadChecksumAndRecovers() {
    byte[] bad = DotPadPacket.request(DotPadPacket.REQ_DEVICE_NAME).toBytes();
    bad[bad.length - 1] ^= 0x01;
    byte[] good = DotPadPacket.request(DotPadPacket.REQ_FIRMWARE_VERSION).toBytes();
    DotPadPacketParser parser = new DotPadPacketParser();
    assertTrue(parser.feed(bad).isEmpty());
    List<DotPadPacket> parsed = parser.feed(good);
    assertEquals(1, parsed.size());
    assertEquals(DotPadPacket.REQ_FIRMWARE_VERSION, parsed.get(0).getCommand());
    assertEquals(1, parser.getDroppedPacketCount());
  }

  // ---- DotPadBoardInfo ----

  @Test
  public void boardInfo_parsesDotPadX() {
    DotPadPacket packet =
        new DotPadPacket(
            0,
            DotPadPacket.RSP_BOARD_INFORMATION,
            0,
            bytes(0xC6, 0x01, 0x1A, 0x04, 1, 20, 0, 5, 10, 30, 0, 30));
    DotPadBoardInfo info = DotPadBoardInfo.parse(packet);
    assertTrue(info.hasFeature(DotPadBoardInfo.FEATURE_GRAPHIC_DISPLAY));
    assertTrue(info.hasFeature(DotPadBoardInfo.FEATURE_TEXT_DISPLAY));
    assertTrue(info.hasFeature(DotPadBoardInfo.FEATURE_FUNCTION_KEYS));
    assertFalse(info.hasFeature(DotPadBoardInfo.FEATURE_PERKINS_KEYS));
    assertTrue(info.eightDotCells);
    assertEquals(4, info.functionKeyCount);
    assertEquals(1, info.text.rows);
    assertEquals(20, info.text.columns);
    assertEquals(500, info.text.refreshTimeMs);
    assertEquals(10, info.graphic.rows);
    assertEquals(30, info.graphic.columns);
    assertEquals(3000, info.graphic.refreshTimeMs);
  }

  @Test
  public void boardInfo_truncatedDataLeavesAreasEmpty() {
    DotPadPacket packet =
        new DotPadPacket(0, DotPadPacket.RSP_BOARD_INFORMATION, 0, bytes(0x02, 0x01));
    DotPadBoardInfo info = DotPadBoardInfo.parse(packet);
    assertEquals(4, info.functionKeyCount); // Defaulted because function keys are present.
    assertFalse(info.graphic.isPresent());
    assertFalse(info.text.isPresent());
  }

  // ---- DotPadKeyDecoder ----

  @Test
  public void decodePressedKeys_usesMostSignificantBitFirst() {
    assertEquals(
        new TreeSet<>(Arrays.asList("F1")),
        DotPadKeyDecoder.decodePressedKeys(DotPadPacket.NTF_KEYS_FUNCTION, bytes(0x80)));
    assertEquals(
        new TreeSet<>(Arrays.asList("F2", "F4")),
        DotPadKeyDecoder.decodePressedKeys(DotPadPacket.NTF_KEYS_FUNCTION, bytes(0x50)));
    // Perkins keys 13 and 14 are bits 0x04 and 0x02 of the second byte.
    assertEquals(
        new TreeSet<>(Arrays.asList("PanLeft", "PanRight")),
        DotPadKeyDecoder.decodePressedKeys(DotPadPacket.NTF_KEYS_PERKINS, bytes(0x00, 0x06)));
  }

  private static DotPadPacket keys(int group, int... data) {
    return new DotPadPacket(0, group, 0, bytes(data));
  }

  @Test
  public void keyDecoder_singleKeyCompletesOnRelease() {
    DotPadKeyDecoder decoder = new DotPadKeyDecoder();
    assertNull(decoder.onKeyPacket(keys(DotPadPacket.NTF_KEYS_FUNCTION, 0x20)));
    assertEquals(
        new TreeSet<>(Arrays.asList("F3")),
        decoder.onKeyPacket(keys(DotPadPacket.NTF_KEYS_FUNCTION, 0x00)));
  }

  @Test
  public void keyDecoder_chordIncludesKeysReleasedEarly() {
    DotPadKeyDecoder decoder = new DotPadKeyDecoder();
    assertNull(decoder.onKeyPacket(keys(DotPadPacket.NTF_KEYS_FUNCTION, 0x80))); // F1
    assertNull(decoder.onKeyPacket(keys(DotPadPacket.NTF_KEYS_FUNCTION, 0xC0))); // F1+F2
    assertNull(decoder.onKeyPacket(keys(DotPadPacket.NTF_KEYS_FUNCTION, 0x40))); // F1 up
    assertEquals(
        new TreeSet<>(Arrays.asList("F1", "F2")),
        decoder.onKeyPacket(keys(DotPadPacket.NTF_KEYS_FUNCTION, 0x00)));
  }

  @Test
  public void keyDecoder_chordSpansGroupsUntilAllReleased() {
    DotPadKeyDecoder decoder = new DotPadKeyDecoder();
    assertNull(decoder.onKeyPacket(keys(DotPadPacket.NTF_KEYS_PERKINS, 0x00, 0x04))); // PanLeft
    assertNull(decoder.onKeyPacket(keys(DotPadPacket.NTF_KEYS_FUNCTION, 0x80))); // F1
    assertNull(decoder.onKeyPacket(keys(DotPadPacket.NTF_KEYS_PERKINS, 0x00, 0x00)));
    Set<String> chord = decoder.onKeyPacket(keys(DotPadPacket.NTF_KEYS_FUNCTION, 0x00));
    assertEquals(new TreeSet<>(Arrays.asList("F1", "PanLeft")), chord);
    assertEquals(BrailleInputEvent.CMD_NAV_TOP, DotPadDriver.mapChordToCommand(chord));
  }

  @Test
  public void mapChordToCommand_matchesKeyMap() {
    assertEquals(
        BrailleInputEvent.CMD_NAV_PAN_UP,
        DotPadDriver.mapChordToCommand(new TreeSet<>(Arrays.asList("PanLeft"))));
    assertEquals(
        BrailleInputEvent.CMD_GLOBAL_HOME,
        DotPadDriver.mapChordToCommand(new TreeSet<>(Arrays.asList("F2", "F1"))));
    assertEquals(
        BrailleInputEvent.CMD_OPEN_TALKBACK_MENU,
        DotPadDriver.mapChordToCommand(new TreeSet<>(Arrays.asList("PanLeft", "PanRight"))));
    assertEquals(
        BrailleInputEvent.CMD_NONE,
        DotPadDriver.mapChordToCommand(new TreeSet<>(Arrays.asList("F1", "F2", "F3"))));
  }

  // ---- DotPadPageRenderer ----

  @Test
  public void renderer_dotPadXFits20By8() {
    assertEquals(20, DotPadPageRenderer.logicalColumns(30));
    assertEquals(8, DotPadPageRenderer.logicalRows(10));
  }

  @Test
  public void renderer_firstCellMapsBrailleDotsToPinBits() {
    // Dots 1, 4, 7, 8 -> pins (0,0), (1,0), (0,3), (1,3) -> bits 0, 4, 3, 7.
    byte[][] lines = DotPadPageRenderer.render(bytes(0x01 | 0x08 | 0x40 | 0x80), 20, 8, 30, 10);
    assertEquals(0x01 | 0x10 | 0x08 | 0x80, lines[0][0] & 0xFF);
    assertEquals(0, lines[0][1]);
    assertEquals(0, lines[1][0]);
  }

  @Test
  public void renderer_secondCellStraddlesPhysicalCellsAfterGap() {
    byte[] page = new byte[160];
    page[1] = 0x01 | 0x08; // Dots 1 and 4 of logical cell 1 -> pin columns 3 and 4.
    byte[][] lines = DotPadPageRenderer.render(page, 20, 8, 30, 10);
    assertEquals(0, lines[0][0]);
    assertEquals(0x10, lines[0][1] & 0xFF); // Pin column 3 = right column of cell 1.
    assertEquals(0x01, lines[0][2] & 0xFF); // Pin column 4 = left column of cell 2.
  }

  @Test
  public void renderer_secondLineStartsAfterOnePinGap() {
    byte[] page = new byte[160];
    page[20] = 0x01; // Dot 1 of logical line 1 -> pin row 5 = physical row 1, bit 1.
    byte[][] lines = DotPadPageRenderer.render(page, 20, 8, 30, 10);
    assertEquals(0, lines[0][0]);
    assertEquals(0x02, lines[1][0] & 0xFF);
  }

  @Test
  public void renderer_lastLineFitsOnLastPhysicalRow() {
    byte[] page = new byte[160];
    page[7 * 20] = (byte) 0x80; // Dot 8 of logical line 7 -> pin row 38 = row 9, y 2, x 1.
    byte[][] lines = DotPadPageRenderer.render(page, 20, 8, 30, 10);
    assertEquals(1 << (2 + 4), lines[9][0] & 0xFF);
  }

  // ---- DotPadDriver.changedRange ----

  @Test
  public void changedRange_unknownLineIsWholeLine() {
    assertArrayEquals(new int[] {0, 29}, DotPadDriver.changedRange(null, new byte[30]));
  }

  @Test
  public void changedRange_identicalLineIsNull() {
    assertNull(DotPadDriver.changedRange(bytes(1, 2, 3), bytes(1, 2, 3)));
  }

  @Test
  public void changedRange_spansFirstToLastDifference() {
    assertArrayEquals(
        new int[] {1, 3}, DotPadDriver.changedRange(bytes(0, 0, 0, 0, 0), bytes(0, 5, 0, 7, 0)));
    assertArrayEquals(
        new int[] {4, 4}, DotPadDriver.changedRange(bytes(0, 0, 0, 0, 0), bytes(0, 0, 0, 0, 9)));
  }
}
