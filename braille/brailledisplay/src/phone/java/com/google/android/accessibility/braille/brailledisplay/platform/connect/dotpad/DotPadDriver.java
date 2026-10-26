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

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;
import com.google.android.accessibility.braille.brailledisplay.BrailleDisplayLog;
import com.google.android.accessibility.braille.brailledisplay.platform.connect.D2dConnection;
import com.google.android.accessibility.braille.brailledisplay.platform.connect.device.ConnectableDevice;
import com.google.android.accessibility.braille.brltty.BrailleDisplayProperties;
import com.google.android.accessibility.braille.brltty.BrailleInputEvent;
import com.google.android.accessibility.braille.brltty.BrailleKeyBinding;
import com.google.android.accessibility.braille.brltty.DeviceInfo;
import com.google.android.accessibility.braille.brltty.Encoder;
import com.google.android.accessibility.braille.brltty.SupportedDevicesHelper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Braille display driver for the Dot Pad (DotPad320X), speaking the Dot Pad protocol directly over
 * {@link DotPadBleTransport}.
 *
 * <p>Reports a multi-line display to TalkBack (20 logical braille cells x 8 logical lines on the
 * Dot Pad X), renders each page onto the graphics area with {@link DotPadPageRenderer}, and writes
 * changed physical lines ({@code lineId = 1..10}) one at a time, waiting for the display to finish
 * moving its pins before sending the next. Newer pages replace older ones that have not been sent
 * yet, so the display never falls behind TalkBack.
 *
 * <p>Connection state, writes and key decoding all run on the main thread. {@link Encoder} methods
 * may be called from TalkBack's display thread and hand their work to the main thread.
 */
public class DotPadDriver implements Encoder {
  private static final String TAG = "DotPadDriver";
  public static final String DRIVER_CODE = "dotpad";
  public static final String DEVICE_NAME_PREFIX = "DotPad";
  public static final int NUM_STATUS_CELLS = 0;
  /** Graphics area size used until the display reports its board information (Dot Pad X). */
  private static final int DEFAULT_GRAPHIC_COLUMNS = 30;
  private static final int DEFAULT_GRAPHIC_ROWS = 10;
  private static final int GRAPHIC_FIRST_LINE_ID = 1;
  private static final long BOARD_INFO_TIMEOUT_MS = 2_000L;
  /** Added to the display's reported refresh time to allow for transport latency. */
  private static final long LINE_TIMEOUT_MARGIN_MS = 1_000L;
  private static final long DEFAULT_LINE_TIMEOUT_MS = 3_000L;
  private static final long LINE_RETRY_DELAY_MS = 100L;
  private static final int MAX_LINE_RETRIES = 3;

  /** A key chord and the TalkBack command it triggers. */
  private static final class KeyMapping {
    final int command;
    final String[] keys;

    KeyMapping(int command, String... keys) {
      this.command = command;
      this.keys = keys;
    }
  }

  /** Key map; see keymap.md. Key names match {@link DotPadKeyDecoder}. */
  private static final KeyMapping[] KEY_MAP = {
    new KeyMapping(BrailleInputEvent.CMD_NAV_PAN_UP, DotPadKeyDecoder.KEY_PAN_LEFT),
    new KeyMapping(BrailleInputEvent.CMD_NAV_PAN_DOWN, DotPadKeyDecoder.KEY_PAN_RIGHT),
    new KeyMapping(BrailleInputEvent.CMD_NAV_ITEM_PREVIOUS, "F1"),
    new KeyMapping(BrailleInputEvent.CMD_NAV_ITEM_NEXT, "F2"),
    new KeyMapping(BrailleInputEvent.CMD_ACTIVATE_CURRENT, "F3"),
    new KeyMapping(BrailleInputEvent.CMD_GLOBAL_BACK, "F4"),
    new KeyMapping(BrailleInputEvent.CMD_GLOBAL_HOME, "F1", "F2"),
    new KeyMapping(BrailleInputEvent.CMD_GLOBAL_NOTIFICATIONS, "F3", "F4"),
    new KeyMapping(BrailleInputEvent.CMD_OPEN_TALKBACK_MENU, "F2", "F3"),
    new KeyMapping(
        BrailleInputEvent.CMD_OPEN_TALKBACK_MENU,
        DotPadKeyDecoder.KEY_PAN_LEFT,
        DotPadKeyDecoder.KEY_PAN_RIGHT),
    new KeyMapping(
        BrailleInputEvent.CMD_NAVIGATE_BY_READING_GRANULARITY_OR_ADJUST_READING_CONTROL_BACKWARD,
        "F1",
        "F3"),
    new KeyMapping(
        BrailleInputEvent.CMD_NAVIGATE_BY_READING_GRANULARITY_OR_ADJUST_READING_CONTROL_FORWARD,
        "F2",
        "F4"),
    new KeyMapping(BrailleInputEvent.CMD_GLOBAL_RECENTS, "F1", "F4"),
    new KeyMapping(BrailleInputEvent.CMD_NAV_TOP, DotPadKeyDecoder.KEY_PAN_LEFT, "F1"),
    new KeyMapping(BrailleInputEvent.CMD_NAV_BOTTOM, DotPadKeyDecoder.KEY_PAN_RIGHT, "F4"),
  };

  /** Listener for BLE scan results. */
  public interface ScanCallback {
    void onBleDeviceFound(BluetoothDevice device);
  }

  /** Listener for connection lifecycle events. */
  public interface ConnectionCallback {
    /** The display is connected and its board information is known. */
    void onConnected();

    default void onReconnecting(int attempt) {}

    void onDisconnected();

    void onConnectFailure(Exception exception);
  }

  @SuppressLint("StaticFieldLeak")
  private static DotPadDriver instance;

  /** Returns the singleton {@link DotPadDriver} instance. */
  public static synchronized DotPadDriver getInstance(Context context) {
    if (instance == null) {
      instance = new DotPadDriver(context.getApplicationContext());
    }
    return instance;
  }

  /** Returns true if the given device name belongs to a Dot Pad braille display. */
  public static boolean isDotPadDeviceName(@Nullable String deviceName) {
    return deviceName != null && deviceName.startsWith(DEVICE_NAME_PREFIX);
  }

  /** Returns true if the given {@link ConnectableDevice} is a Dot Pad braille display. */
  public static boolean isDotPadDevice(@Nullable ConnectableDevice device) {
    return device != null && isDotPadDeviceName(device.name());
  }

  private final Context appContext;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final DotPadBleTransport transport;
  private final DotPadPacketParser parser = new DotPadPacketParser();
  private final DotPadKeyDecoder keyDecoder = new DotPadKeyDecoder();
  private final Queue<Integer> pendingCommands = new ConcurrentLinkedQueue<>();
  private final Runnable boardInfoTimeoutRunnable = this::onBoardInfoTimeout;
  private final Runnable lineTimeoutRunnable = this::onLineTimeout;
  private final Runnable pumpRunnable = this::pumpLines;

  @Nullable private volatile D2dConnection.Callback trafficCallback;
  @Nullable private ConnectionCallback connectionCallback;
  /** Board information of the connected display; {@code null} until it is received. */
  @Nullable private volatile DotPadBoardInfo boardInfo;
  /** True once the link is up and board information is known (or timed out). */
  private boolean displayReady;
  private boolean connectedNotified;

  // Write state (main thread only).
  /** Latest braille cells from TalkBack, kept so a page can be re-rendered after reconnecting. */
  @Nullable private byte[] latestBrailleCells;
  /** Physical cells TalkBack wants on each graphics line. */
  @Nullable private byte[][] desiredLines;
  /** Physical cells the display is known to show on each line; a {@code null} line is unknown. */
  @Nullable private byte[][] displayedLines;
  private int inFlightLine = -1;
  @Nullable private byte[] inFlightCells;
  private int lineRetries;

  private DotPadDriver(Context appContext) {
    this.appContext = appContext;
    this.transport = new DotPadBleTransport(appContext, mainHandler);
  }

  /** Sets the active {@link D2dConnection.Callback} used to notify TalkBack when key events arrive. */
  public void setTrafficCallback(@Nullable D2dConnection.Callback callback) {
    this.trafficCallback = callback;
  }

  /** Starts scanning for BLE Dot Pad devices. Main thread only. */
  public void startBleScan(ScanCallback callback) {
    transport.startScan(DotPadDriver::isDotPadDeviceName, callback::onBleDeviceFound);
  }

  /** Stops scanning for BLE Dot Pad devices. Main thread only. */
  public void stopBleScan() {
    transport.stopScan();
  }

  /** Connects to a Dot Pad {@link BluetoothDevice}. Main thread only. */
  public void connect(BluetoothDevice bluetoothDevice, ConnectionCallback callback) {
    BrailleDisplayLog.d(TAG, "Connecting to Dot Pad " + bluetoothDevice.getAddress());
    connectionCallback = callback;
    connectedNotified = false;
    resetLinkState(/* keepPage= */ false);
    transport.connect(bluetoothDevice, transportCallback);
  }

  /** Disconnects the active Dot Pad connection. Main thread only. */
  public void disconnect() {
    BrailleDisplayLog.d(TAG, "Disconnecting Dot Pad");
    connectionCallback = null;
    connectedNotified = false;
    transport.disconnect();
    resetLinkState(/* keepPage= */ false);
  }

  /** Board information of the connected display, or {@code null} if not known. */
  @Nullable
  public DotPadBoardInfo getBoardInfo() {
    return boardInfo;
  }

  private void resetLinkState(boolean keepPage) {
    mainHandler.removeCallbacks(boardInfoTimeoutRunnable);
    mainHandler.removeCallbacks(lineTimeoutRunnable);
    mainHandler.removeCallbacks(pumpRunnable);
    displayReady = false;
    parser.reset();
    keyDecoder.reset();
    pendingCommands.clear();
    inFlightLine = -1;
    inFlightCells = null;
    lineRetries = 0;
    displayedLines = null;
    if (!keepPage) {
      boardInfo = null;
      latestBrailleCells = null;
      desiredLines = null;
    }
  }

  // ---- Encoder implementation ----

  @Override
  public Optional<BrailleDisplayProperties> start(
      String deviceName, int vendorId, int prodId, boolean useHid, String parameters) {
    int logicalColumns = DotPadPageRenderer.logicalColumns(graphicColumns());
    int logicalRows = DotPadPageRenderer.logicalRows(graphicRows());
    BrailleDisplayLog.d(
        TAG,
        "Starting encoder for "
            + deviceName
            + ": "
            + logicalColumns
            + "x"
            + logicalRows
            + " logical cells on "
            + graphicColumns()
            + "x"
            + graphicRows()
            + " physical cells");
    DeviceInfo deviceInfo = SupportedDevicesHelper.getDeviceInfo(deviceName, useHid);
    Map<String, String> friendlyKeyNames = new HashMap<>();
    if (deviceInfo != null) {
      for (Map.Entry<String, Integer> entry : deviceInfo.friendlyKeyNames().entrySet()) {
        friendlyKeyNames.put(entry.getKey(), appContext.getString(entry.getValue()));
      }
    }
    return Optional.of(
        new BrailleDisplayProperties(
            DRIVER_CODE,
            logicalColumns,
            logicalRows,
            NUM_STATUS_CELLS,
            createKeyBindings(),
            friendlyKeyNames));
  }

  @Override
  public void stop() {
    BrailleDisplayLog.d(TAG, "Stopping encoder");
    pendingCommands.clear();
  }

  @Override
  public void consumePacketFromDevice(byte[] packet) {
    // Incoming bytes arrive through DotPadBleTransport, not D2dConnection.
  }

  @Override
  public void writeBrailleDots(byte[] brailleDotBytes) {
    if (brailleDotBytes == null) {
      return;
    }
    byte[] cells = brailleDotBytes.clone();
    mainHandler.post(() -> setPage(cells));
  }

  @Override
  public int readCommand() {
    Integer cmd = pendingCommands.poll();
    return cmd != null ? cmd : BrailleInputEvent.CMD_NONE;
  }

  @Override
  public Predicate<String> getDeviceNameFilter() {
    return DotPadDriver::isDotPadDeviceName;
  }

  @Override
  public BiPredicate<Integer, Integer> getDeviceVendorProdIdFilter() {
    return (vendorId, prodId) -> false;
  }

  private int graphicColumns() {
    DotPadBoardInfo info = boardInfo;
    return (info != null && info.graphic.isPresent())
        ? info.graphic.columns
        : DEFAULT_GRAPHIC_COLUMNS;
  }

  private int graphicRows() {
    DotPadBoardInfo info = boardInfo;
    return (info != null && info.graphic.isPresent()) ? info.graphic.rows : DEFAULT_GRAPHIC_ROWS;
  }

  // ---- Page writing (main thread) ----

  private void setPage(byte[] brailleCells) {
    latestBrailleCells = brailleCells;
    int physicalColumns = graphicColumns();
    int physicalRows = graphicRows();
    desiredLines =
        DotPadPageRenderer.render(
            brailleCells,
            DotPadPageRenderer.logicalColumns(physicalColumns),
            DotPadPageRenderer.logicalRows(physicalRows),
            physicalColumns,
            physicalRows);
    pumpLines();
  }

  /** Sends the first line that differs from what the display shows, if nothing is in flight. */
  private void pumpLines() {
    mainHandler.removeCallbacks(pumpRunnable);
    byte[][] desired = desiredLines;
    if (!displayReady || inFlightLine >= 0 || desired == null) {
      return;
    }
    if (displayedLines == null || displayedLines.length != desired.length) {
      displayedLines = new byte[desired.length][];
    }
    for (int line = 0; line < desired.length; line++) {
      if (Arrays.equals(desired[line], displayedLines[line])) {
        continue;
      }
      inFlightLine = line;
      inFlightCells = desired[line];
      DotPadPacket packet =
          DotPadPacket.displayLine(GRAPHIC_FIRST_LINE_ID + line, /* startCell= */ 0, inFlightCells);
      BrailleDisplayLog.v(TAG, "Writing " + packet);
      mainHandler.postDelayed(lineTimeoutRunnable, lineTimeoutMs());
      if (!transport.write(packet.toBytes())) {
        BrailleDisplayLog.w(TAG, "Transport not ready; dropping line write");
        finishLine(/* displayed= */ false);
      }
      return;
    }
  }

  private long lineTimeoutMs() {
    DotPadBoardInfo info = boardInfo;
    if (info == null || info.graphic.refreshTimeMs == 0) {
      return DEFAULT_LINE_TIMEOUT_MS;
    }
    return info.graphic.refreshTimeMs + LINE_TIMEOUT_MARGIN_MS;
  }

  /**
   * Ends the in-flight line write. If {@code displayed}, the display now shows it; otherwise it is
   * retried a few times before being assumed shown, so a misbehaving line cannot stall the page.
   */
  private void finishLine(boolean displayed) {
    mainHandler.removeCallbacks(lineTimeoutRunnable);
    if (inFlightLine < 0) {
      return;
    }
    if (displayed || ++lineRetries > MAX_LINE_RETRIES) {
      if (displayedLines != null && inFlightLine < displayedLines.length) {
        displayedLines[inFlightLine] = inFlightCells;
      }
      lineRetries = 0;
      inFlightLine = -1;
      inFlightCells = null;
      pumpLines();
    } else {
      inFlightLine = -1;
      inFlightCells = null;
      mainHandler.postDelayed(pumpRunnable, LINE_RETRY_DELAY_MS);
    }
  }

  private void onLineTimeout() {
    BrailleDisplayLog.w(TAG, "No completion for line " + inFlightLine + "; moving on");
    finishLine(/* displayed= */ false);
  }

  // ---- Incoming packets (main thread) ----

  private void onPacket(DotPadPacket packet) {
    int command = packet.getCommand();
    if (DotPadKeyDecoder.isKeyPacket(command)) {
      Set<String> chord = keyDecoder.onKeyPacket(packet);
      if (chord != null) {
        int cmd = mapChordToCommand(chord);
        BrailleDisplayLog.d(TAG, "Key chord " + chord + " -> command " + cmd);
        enqueueCommand(cmd);
      }
      return;
    }
    switch (command) {
      case DotPadPacket.RSP_BOARD_INFORMATION:
        onBoardInfo(DotPadBoardInfo.parse(packet));
        break;
      case DotPadPacket.RSP_DEVICE_NAME:
      case DotPadPacket.RSP_FIRMWARE_VERSION:
        BrailleDisplayLog.d(
            TAG,
            (command == DotPadPacket.RSP_DEVICE_NAME ? "Device name: " : "Firmware: ")
                + new String(packet.getData(), StandardCharsets.US_ASCII).trim());
        break;
      case DotPadPacket.RSP_DISPLAY_LINE:
        int code = packet.getDataByte(0);
        if (code != DotPadPacket.DISPLAY_ACK && isInFlight(packet)) {
          BrailleDisplayLog.w(TAG, "Line " + packet.getDestination() + " rejected: " + code);
          finishLine(/* displayed= */ false);
        }
        break;
      case DotPadPacket.NTF_DISPLAY_LINE:
        if (isInFlight(packet)) {
          finishLine(/* displayed= */ true);
        }
        break;
      case DotPadPacket.NTF_ERROR:
        BrailleDisplayLog.w(TAG, "Display reported error " + packet.getDataByte(0));
        break;
      default:
        BrailleDisplayLog.v(TAG, "Unhandled " + packet);
    }
  }

  private boolean isInFlight(DotPadPacket packet) {
    return inFlightLine >= 0 && packet.getDestination() == GRAPHIC_FIRST_LINE_ID + inFlightLine;
  }

  private void onBoardInfo(DotPadBoardInfo info) {
    BrailleDisplayLog.d(TAG, "Board information: " + info);
    boardInfo = info;
    if (!displayReady) {
      onDisplayReady();
    }
  }

  private void onBoardInfoTimeout() {
    BrailleDisplayLog.w(TAG, "No board information; assuming a Dot Pad X graphics area");
    onDisplayReady();
  }

  /** The link is up and the display size is settled: tell TalkBack and (re)send the page. */
  private void onDisplayReady() {
    mainHandler.removeCallbacks(boardInfoTimeoutRunnable);
    displayReady = true;
    ConnectionCallback cb = connectionCallback;
    if (!connectedNotified && cb != null) {
      connectedNotified = true;
      cb.onConnected();
    }
    if (latestBrailleCells != null) {
      // Re-render in case the board information changed the page size.
      setPage(latestBrailleCells);
    }
  }

  // ---- Keys ----

  /** Maps a key chord to a TalkBack command, or {@link BrailleInputEvent#CMD_NONE}. */
  public static int mapChordToCommand(Set<String> chord) {
    for (KeyMapping mapping : KEY_MAP) {
      if (chord.equals(new TreeSet<>(Arrays.asList(mapping.keys)))) {
        return mapping.command;
      }
    }
    return BrailleInputEvent.CMD_NONE;
  }

  private void enqueueCommand(int command) {
    if (command == BrailleInputEvent.CMD_NONE) {
      return;
    }
    pendingCommands.offer(command & 0xFFFF);
    D2dConnection.Callback cb = trafficCallback;
    if (cb != null) {
      cb.onRead();
    }
  }

  private static BrailleKeyBinding[] createKeyBindings() {
    BrailleKeyBinding[] bindings = new BrailleKeyBinding[KEY_MAP.length];
    for (int i = 0; i < KEY_MAP.length; i++) {
      bindings[i] =
          new BrailleKeyBinding(
              KEY_MAP[i].command,
              KEY_MAP[i].keys,
              /* longPress= */ false,
              /* unifiedKeyBinding= */ false);
    }
    return bindings;
  }

  // ---- Transport callbacks (main thread) ----

  private final DotPadBleTransport.Callback transportCallback =
      new DotPadBleTransport.Callback() {
        @Override
        public void onConnected() {
          BrailleDisplayLog.d(TAG, "Link up; requesting board information");
          resetLinkState(/* keepPage= */ true);
          mainHandler.postDelayed(boardInfoTimeoutRunnable, BOARD_INFO_TIMEOUT_MS);
          transport.write(DotPadPacket.request(DotPadPacket.REQ_BOARD_INFORMATION).toBytes());
          transport.write(DotPadPacket.request(DotPadPacket.REQ_DEVICE_NAME).toBytes());
          transport.write(DotPadPacket.request(DotPadPacket.REQ_FIRMWARE_VERSION).toBytes());
        }

        @Override
        public void onReconnecting(int attempt) {
          BrailleDisplayLog.d(TAG, "Link lost; reconnecting, attempt " + attempt);
          resetLinkState(/* keepPage= */ true);
          ConnectionCallback cb = connectionCallback;
          if (cb != null) {
            cb.onReconnecting(attempt);
          }
        }

        @Override
        public void onDisconnected() {
          ConnectionCallback cb = connectionCallback;
          connectionCallback = null;
          connectedNotified = false;
          resetLinkState(/* keepPage= */ false);
          if (cb != null) {
            cb.onDisconnected();
          }
        }

        @Override
        public void onConnectFailure(Exception exception) {
          ConnectionCallback cb = connectionCallback;
          connectionCallback = null;
          connectedNotified = false;
          resetLinkState(/* keepPage= */ false);
          if (cb != null) {
            cb.onConnectFailure(exception);
          }
        }

        @Override
        public void onDataReceived(byte[] data) {
          for (DotPadPacket packet : parser.feed(data)) {
            onPacket(packet);
          }
        }
      };
}
