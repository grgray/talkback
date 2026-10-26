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
import android.app.Application;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;
import com.dotincorp.sdk_v3.model.DataCodes;
import com.dotincorp.sdk_v3.model.DotDevice;
import com.dotincorp.sdk_v3.model.DotDeviceMessage;
import com.dotincorp.sdk_v3.model.DotPadKey;
import com.dotincorp.sdk_v3.model.KeyCodes;
import com.dotincorp.sdk_v3.process.DotPadProcess;
import com.dotincorp.sdk_v3.process.DotPadScanner;
import com.dotincorp.sdk_v3.process.DotPadSearchListener;
import com.dotincorp.sdk_v3.process.ErrorCodes;
import com.dotincorp.sdk_v3.protocal.DisplayMode;
import com.google.android.accessibility.braille.brailledisplay.BrailleDisplayLog;
import com.google.android.accessibility.braille.brailledisplay.platform.connect.D2dConnection;
import com.google.android.accessibility.braille.brailledisplay.platform.connect.device.ConnectableDevice;
import com.google.android.accessibility.braille.brltty.BrailleDisplayProperties;
import com.google.android.accessibility.braille.brltty.BrailleInputEvent;
import com.google.android.accessibility.braille.brltty.BrailleKeyBinding;
import com.google.android.accessibility.braille.brltty.DeviceInfo;
import com.google.android.accessibility.braille.brltty.Encoder;
import com.google.android.accessibility.braille.brltty.SupportedDevicesHelper;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Driver that manages BLE discovery, GATT connection, and braille display properties for the Dot
 * Pad (DotPad320X) via the Dot Pad Android SDK v3.0.1 ({@link DotPadScanner} and {@link
 * DotPadProcess}).
 *
 * <p>Phase 3: Connects through {@link DotPadScanner} / {@link DotPadProcess}, reports a 160-cell
 * multi-line braille display (20 logical braille cells per line x 8 logical lines) to TalkBack,
 * renders each logical 2x4 braille cell onto the Dot Pad's 60x40 physical pin grid (30x10 physical
 * 2x4 cells, {@code lineId = 1..10}) with a 1-dot horizontal gap between adjacent cells and a
 * 1-dot vertical gap between adjacent lines, and sends changed physical rows via {@link
 * DotPadProcess#displayLineData} using {@link DisplayMode#TextMode} while respecting write
 * acknowledgements.
 */
public class DotPadDriver implements Encoder {
  private static final String TAG = "DotPadDriver";
  public static final String DRIVER_CODE = "dotpad";
  public static final String DEVICE_NAME_PREFIX = "DotPad";
  /** Number of cells on the dedicated single-line braille bar ({@code lineId = 0}). */
  public static final int NUM_BRAILLE_LINE_CELLS = 20;
  /**
   * Number of logical braille cells per row on the graphics area (20 cells x 3 pin columns = 60
   * pin columns across 30 physical cells).
   */
  public static final int NUM_TEXT_CELLS = 20;
  /**
   * Number of logical braille rows on the graphics area (8 rows x 5 pin rows = 40 pin rows across
   * 10 physical lines).
   */
  public static final int NUM_TEXT_ROWS = 8;
  public static final int NUM_STATUS_CELLS = 0;
  private static final int PHYSICAL_GRAPHIC_COLUMNS = 30;
  private static final int PHYSICAL_GRAPHIC_ROWS = 10;
  private static final int PINS_PER_PHYSICAL_CELL_COL = 2;
  private static final int PINS_PER_PHYSICAL_CELL_ROW = 4;
  private static final int LOGICAL_CELL_WIDTH_PINS = 2;
  private static final int LOGICAL_CELL_HEIGHT_PINS = 4;
  private static final int LOGICAL_CELL_HORIZONTAL_GAP_PINS = 1;
  private static final int LOGICAL_CELL_VERTICAL_GAP_PINS = 1;
  private static final int LOGICAL_CELL_STRIDE_X =
      LOGICAL_CELL_WIDTH_PINS + LOGICAL_CELL_HORIZONTAL_GAP_PINS; // 3 pins
  private static final int LOGICAL_CELL_STRIDE_Y =
      LOGICAL_CELL_HEIGHT_PINS + LOGICAL_CELL_VERTICAL_GAP_PINS; // 5 pins
  private static final int GRAPHIC_FIRST_LINE_ID = 1;
  private static final int START_CELL_INDEX = 0;
  private static final long WRITE_ACK_POLL_INTERVAL_MS = 20L;
  private static final long WRITE_ACK_TIMEOUT_MS = 1000L;

  /**
   * Bitmasks for the 8 dots of a physical 2x4 braille/graphic cell in {@link DisplayMode#TextMode}:
   * left column (xOffset = 0) = dots 1, 2, 3, 7 (bits 0, 1, 2, 6); right column (xOffset = 1) =
   * dots 4, 5, 6, 8 (bits 3, 4, 5, 7).
   */
  private static final int[][] PHYSICAL_CELL_PIN_BIT = {
    // xOffset = 0 (left column of physical cell: dots 1, 2, 3, 7)
    {0x01, 0x02, 0x04, 0x40},
    // xOffset = 1 (right column of physical cell: dots 4, 5, 6, 8)
    {0x08, 0x10, 0x20, 0x80}
  };

  /** Listener for BLE scan events from {@link DotPadScanner}. */
  public interface ScanCallback {
    void onBleDeviceFound(BluetoothDevice device);
  }

  /** Listener for BLE connection lifecycle events from {@link DotPadProcess}. */
  public interface ConnectionCallback {
    void onConnected(DotDevice dotDevice);

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
  private final DotPadScanner dotPadScanner;
  private final Object writeLock = new Object();
  private final Queue<Integer> pendingCommands = new ConcurrentLinkedQueue<>();
  private final Runnable writeAckPollRunnable = this::pollWriteAckState;
  private final Runnable writeAckTimeoutRunnable = this::onWriteAckTimeout;
  @Nullable private static volatile Field dotPadSendModuleField;
  @Nullable private static volatile Field sendModuleReadyField;
  private static volatile boolean sendModuleReflectionFailed;
  @Nullable private volatile ScanCallback scanCallback;
  @Nullable private volatile ConnectionCallback connectionCallback;
  @Nullable private volatile D2dConnection.Callback trafficCallback;
  @Nullable private volatile DotDevice connectedDotDevice;
  /** Latest page of physical row hex strings requested by TalkBack. */
  @Nullable private String[] lastPageRowHex;
  /** Physical row hex strings most recently sent to the device for each row {@code 0..9}. */
  @Nullable private String[] sentPageRowHex;
  /** Page of physical row hex strings currently in flight to the device. */
  @Nullable private String[] inFlightPageRowHex;
  /** Coalesced page of physical row hex strings waiting for the in-flight write to complete. */
  @Nullable private String[] queuedPageRowHex;
  private boolean writeInFlight;
  private boolean boardInfoReady;

  private DotPadDriver(Context appContext) {
    this.appContext = appContext;
    Application application = (Application) appContext;
    this.dotPadScanner = new DotPadScanner(application, dotPadSearchListener);
    DotPadProcess.INSTANCE.setCallBack(dotDeviceMessageCallback);
  }

  /** Sets the active {@link D2dConnection.Callback} used to notify TalkBack when key events arrive. */
  public void setTrafficCallback(@Nullable D2dConnection.Callback callback) {
    this.trafficCallback = callback;
  }

  /** Starts scanning for BLE Dot Pad devices via {@link DotPadScanner}. */
  public void startBleScan(ScanCallback callback) {
    this.scanCallback = callback;
    try {
      BrailleDisplayLog.d(TAG, "Starting DotPad BLE scan");
      dotPadScanner.startBleScan();
    } catch (RuntimeException e) {
      BrailleDisplayLog.e(TAG, "Failed to start DotPad BLE scan", e);
    }
  }

  /** Stops scanning for BLE Dot Pad devices via {@link DotPadScanner}. */
  public void stopBleScan() {
    this.scanCallback = null;
    try {
      BrailleDisplayLog.d(TAG, "Stopping DotPad BLE scan");
      dotPadScanner.stopBleScan();
    } catch (RuntimeException e) {
      BrailleDisplayLog.e(TAG, "Failed to stop DotPad BLE scan", e);
    }
  }

  /** Connects to a Dot Pad {@link BluetoothDevice} via {@link DotPadProcess}. */
  public void connect(BluetoothDevice bluetoothDevice, ConnectionCallback callback) {
    this.connectionCallback = callback;
    synchronized (writeLock) {
      resetWriteStateLocked(/* keepLastPage= */ false);
      boardInfoReady = false;
    }
    pendingCommands.clear();
    try {
      BrailleDisplayLog.d(TAG, "Connecting to DotPad via DotPadProcess: " + bluetoothDevice);
      DotPadProcess.INSTANCE.connect(bluetoothDevice);
    } catch (RuntimeException e) {
      BrailleDisplayLog.e(TAG, "DotPadProcess.connect failed", e);
      mainHandler.post(() -> callback.onConnectFailure(e));
    }
  }

  /** Disconnects the active Dot Pad connection via {@link DotPadProcess}. */
  public void disconnect() {
    this.connectionCallback = null;
    DotDevice device = this.connectedDotDevice;
    this.connectedDotDevice = null;
    synchronized (writeLock) {
      resetWriteStateLocked(/* keepLastPage= */ false);
      boardInfoReady = false;
    }
    pendingCommands.clear();
    try {
      BrailleDisplayLog.d(TAG, "Disconnecting DotPad device");
      DotPadProcess.INSTANCE.disconnect(device);
    } catch (RuntimeException e) {
      BrailleDisplayLog.e(TAG, "DotPadProcess.disconnect failed", e);
    }
  }

  @Nullable
  public DotDevice getConnectedDotDevice() {
    return connectedDotDevice;
  }

  // ---- Encoder implementation ----

  @Override
  public Optional<BrailleDisplayProperties> start(
      String deviceName, int vendorId, int prodId, boolean useHid, String parameters) {
    DotDevice device = connectedDotDevice;
    int numTextCells = getLogicalColumns(device);
    int numTextRows = getLogicalRows(device);
    BrailleDisplayLog.d(
        TAG,
        "Starting DotPadDriver encoder for device: "
            + deviceName
            + " ("
            + numTextCells
            + "x"
            + numTextRows
            + " = "
            + (numTextCells * numTextRows)
            + " logical cells across "
            + getPhysicalGraphicColumns(device)
            + "x"
            + getPhysicalGraphicRows(device)
            + " physical cells)");
    DeviceInfo deviceInfo = SupportedDevicesHelper.getDeviceInfo(deviceName, useHid);
    BrailleKeyBinding[] keyBindings = createDefaultKeyBindings();
    Map<String, String> friendlyKeyNames = new HashMap<>();
    if (deviceInfo != null) {
      for (Map.Entry<String, Integer> entry : deviceInfo.friendlyKeyNames().entrySet()) {
        friendlyKeyNames.put(entry.getKey(), appContext.getString(entry.getValue()));
      }
    }
    return Optional.of(
        new BrailleDisplayProperties(
            DRIVER_CODE,
            numTextCells,
            numTextRows,
            NUM_STATUS_CELLS,
            keyBindings,
            friendlyKeyNames));
  }

  @Override
  public void stop() {
    BrailleDisplayLog.d(TAG, "Stopping DotPadDriver encoder");
    synchronized (writeLock) {
      resetWriteStateLocked(/* keepLastPage= */ false);
    }
    pendingCommands.clear();
  }

  @Override
  public void consumePacketFromDevice(byte[] packet) {
    // DotPadProcess handles raw BLE GATT packets internally.
  }

  @Override
  public void writeBrailleDots(byte[] brailleDotBytes) {
    if (brailleDotBytes == null) {
      return;
    }
    DotDevice device = connectedDotDevice;
    int physicalColumns = getPhysicalGraphicColumns(device);
    int physicalRows = getPhysicalGraphicRows(device);
    int logicalColumns = getLogicalColumns(device);
    int logicalRows = getLogicalRows(device);
    String[] pageRowHex =
        renderSpacedBraillePageToHexRows(
            brailleDotBytes, logicalColumns, logicalRows, physicalColumns, physicalRows);
    enqueueOrSendPage(pageRowHex);
  }

  private static int getPhysicalGraphicColumns(@Nullable DotDevice device) {
    if (device != null && device.getNumberCellColumns() > 0) {
      return device.getNumberCellColumns();
    }
    return PHYSICAL_GRAPHIC_COLUMNS;
  }

  private static int getPhysicalGraphicRows(@Nullable DotDevice device) {
    if (device != null && device.getNumberCellRows() > 0) {
      return device.getNumberCellRows();
    }
    return PHYSICAL_GRAPHIC_ROWS;
  }

  private static int getLogicalColumns(@Nullable DotDevice device) {
    int totalPinCols = getPhysicalGraphicColumns(device) * PINS_PER_PHYSICAL_CELL_COL;
    return Math.max(1, totalPinCols / LOGICAL_CELL_STRIDE_X);
  }

  private static int getLogicalRows(@Nullable DotDevice device) {
    int totalPinRows = getPhysicalGraphicRows(device) * PINS_PER_PHYSICAL_CELL_ROW;
    return Math.max(1, totalPinRows / LOGICAL_CELL_STRIDE_Y);
  }

  /**
   * Renders a page of logical braille cells ({@code logicalColumns} x {@code logicalRows}) onto the
   * Dot Pad's physical graphics cell grid ({@code physicalColumns} x {@code physicalRows}) with a
   * 1-dot horizontal gap between adjacent logical cells and a 1-dot vertical gap between adjacent
   * logical lines.
   *
   * <p>On the 30x10 physical cell grid (60x40 pins), each logical cell occupies 2x4 pins at pin
   * origin {@code (logicalCol * 3, logicalRow * 5)}:
   *
   * <ul>
   *   <li>Even logical columns ({@code logicalCol = 0, 2, ...}) align with physical cell {@code
   *       (logicalCol / 2) * 3} (left column = physical left column, right column = physical right
   *       column), followed by 1 blank pin in the left column of physical cell {@code +1}.
   *   <li>Odd logical columns ({@code logicalCol = 1, 3, ...}) straddle physical cells: their left
   *       column (dots 1, 2, 3, 7) is rendered on the right column (dots 4, 5, 6, 8) of physical
   *       cell {@code (logicalCol / 2) * 3 + 1}, and their right column (dots 4, 5, 6, 8) is
   *       rendered on the left column (dots 1, 2, 3, 7) of physical cell {@code (logicalCol / 2) *
   *       3 + 2}, followed by 1 blank pin in the right column of physical cell {@code +2}.
   *   <li>Vertically, logical row {@code logicalRow} starts at pin row {@code logicalRow * 5},
   *       leaving 1 blank pin row between consecutive logical braille lines, so 8 logical lines
   *       span the 10 physical graphic lines (40 pin rows).
   * </ul>
   */
  public static String[] renderSpacedBraillePageToHexRows(
      byte[] logicalBrailleBytes,
      int logicalColumns,
      int logicalRows,
      int physicalColumns,
      int physicalRows) {
    int totalPinCols = physicalColumns * PINS_PER_PHYSICAL_CELL_COL;
    int totalPinRows = physicalRows * PINS_PER_PHYSICAL_CELL_ROW;
    byte[][] physicalCells = new byte[physicalRows][physicalColumns];

    for (int lRow = 0; lRow < logicalRows; lRow++) {
      int pinYBase = lRow * LOGICAL_CELL_STRIDE_Y;
      for (int lCol = 0; lCol < logicalColumns; lCol++) {
        int logicalIndex = lRow * logicalColumns + lCol;
        if (logicalIndex >= logicalBrailleBytes.length) {
          break;
        }
        int cellBits = logicalBrailleBytes[logicalIndex] & 0xFF;
        if (cellBits == 0) {
          continue;
        }
        int pinXBase = lCol * LOGICAL_CELL_STRIDE_X;
        for (int dx = 0; dx < LOGICAL_CELL_WIDTH_PINS; dx++) {
          int pinX = pinXBase + dx;
          if (pinX >= totalPinCols) {
            continue;
          }
          int physCol = pinX / PINS_PER_PHYSICAL_CELL_COL;
          int physXOffset = pinX % PINS_PER_PHYSICAL_CELL_COL;
          for (int dy = 0; dy < LOGICAL_CELL_HEIGHT_PINS; dy++) {
            int sourceBit = PHYSICAL_CELL_PIN_BIT[dx][dy];
            if ((cellBits & sourceBit) == 0) {
              continue;
            }
            int pinY = pinYBase + dy;
            if (pinY >= totalPinRows) {
              continue;
            }
            int physRow = pinY / PINS_PER_PHYSICAL_CELL_ROW;
            int physYOffset = pinY % PINS_PER_PHYSICAL_CELL_ROW;
            physicalCells[physRow][physCol] |=
                (byte) PHYSICAL_CELL_PIN_BIT[physXOffset][physYOffset];
          }
        }
      }
    }

    String[] hexRows = new String[physicalRows];
    for (int pRow = 0; pRow < physicalRows; pRow++) {
      hexRows[pRow] = toHexLine(physicalCells[pRow], physicalColumns);
    }
    return hexRows;
  }

  private void enqueueOrSendPage(String[] pageRowHex) {
    String[] pageToSendNow = null;
    String[] prevSentSnapshot = null;
    DotDevice deviceToSend = null;
    synchronized (writeLock) {
      lastPageRowHex = pageRowHex;
      DotDevice device = connectedDotDevice;
      if (device == null || !boardInfoReady) {
        queuedPageRowHex = pageRowHex;
        BrailleDisplayLog.v(TAG, "Queueing multi-line page until DotPad BoardInfo is ready");
        return;
      }
      if (writeInFlight) {
        if (!Arrays.equals(pageRowHex, inFlightPageRowHex)) {
          queuedPageRowHex = pageRowHex;
          BrailleDisplayLog.v(TAG, "Write in flight; coalescing latest multi-line page");
        } else {
          queuedPageRowHex = null;
        }
        return;
      }
      if (Arrays.equals(pageRowHex, sentPageRowHex)) {
        return;
      }
      writeInFlight = true;
      inFlightPageRowHex = pageRowHex;
      queuedPageRowHex = null;
      prevSentSnapshot = (sentPageRowHex != null) ? sentPageRowHex.clone() : null;
      sentPageRowHex = pageRowHex.clone();
      pageToSendNow = pageRowHex;
      deviceToSend = device;
      scheduleWriteAckTimeoutLocked();
    }
    sendPageToDevice(pageToSendNow, prevSentSnapshot, deviceToSend);
  }

  private void onWriteAckOrComplete(String reason) {
    String[] nextPage = null;
    String[] prevSentSnapshot = null;
    DotDevice device = null;
    synchronized (writeLock) {
      cancelWriteAckTimeoutLocked();
      writeInFlight = false;
      inFlightPageRowHex = null;
      if (queuedPageRowHex != null && connectedDotDevice != null && boardInfoReady) {
        if (!Arrays.equals(queuedPageRowHex, sentPageRowHex)) {
          nextPage = queuedPageRowHex;
          inFlightPageRowHex = nextPage;
          prevSentSnapshot = (sentPageRowHex != null) ? sentPageRowHex.clone() : null;
          sentPageRowHex = nextPage.clone();
          writeInFlight = true;
          device = connectedDotDevice;
          scheduleWriteAckTimeoutLocked();
        }
        queuedPageRowHex = null;
      }
    }
    if (nextPage != null && device != null) {
      BrailleDisplayLog.v(TAG, "Flushing coalesced multi-line page after " + reason);
      sendPageToDevice(nextPage, prevSentSnapshot, device);
    }
  }

  private void onWriteAckTimeout() {
    BrailleDisplayLog.v(TAG, "Write ACK timeout elapsed; unblocking next queued page");
    onWriteAckOrComplete("ACK timeout");
  }

  private void pollWriteAckState() {
    DotDevice device = connectedDotDevice;
    if (device == null || isSdkSendModuleReady(device)) {
      onWriteAckOrComplete("SDK ResponseDisplayLineComplete");
    } else {
      synchronized (writeLock) {
        if (writeInFlight) {
          mainHandler.postDelayed(writeAckPollRunnable, WRITE_ACK_POLL_INTERVAL_MS);
        }
      }
    }
  }

  /**
   * Checks whether {@link DotDevice}'s internal send module ({@code dotPadSendModule.c}) has
   * received {@code ResponseDisplayLineComplete} ({@code 0202}) and is ready for the next write.
   * Because {@link DotDevice#receivedMessageCallBack} consumes {@code ResponseDisplayLineAck} and
   * {@code ResponseDisplayLineComplete} internally without forwarding them to {@code
   * outerCallBack}, inspecting {@code dotPadSendModule.c} allows {@link DotPadDriver} to coalesce
   * updates while a write is in flight and flush immediately when the hardware confirms completion.
   */
  private static boolean isSdkSendModuleReady(DotDevice device) {
    if (sendModuleReflectionFailed) {
      return true;
    }
    try {
      Field moduleField = dotPadSendModuleField;
      if (moduleField == null) {
        moduleField = DotDevice.class.getDeclaredField("dotPadSendModule");
        moduleField.setAccessible(true);
        dotPadSendModuleField = moduleField;
      }
      Object sendModule = moduleField.get(device);
      if (sendModule == null) {
        return true;
      }
      Field readyField = sendModuleReadyField;
      if (readyField == null) {
        readyField = sendModule.getClass().getDeclaredField("c");
        readyField.setAccessible(true);
        sendModuleReadyField = readyField;
      }
      return readyField.getBoolean(sendModule);
    } catch (ReflectiveOperationException | SecurityException e) {
      sendModuleReflectionFailed = true;
      return true;
    }
  }

  private void scheduleWriteAckTimeoutLocked() {
    mainHandler.removeCallbacks(writeAckPollRunnable);
    mainHandler.removeCallbacks(writeAckTimeoutRunnable);
    mainHandler.postDelayed(writeAckPollRunnable, WRITE_ACK_POLL_INTERVAL_MS);
    mainHandler.postDelayed(writeAckTimeoutRunnable, WRITE_ACK_TIMEOUT_MS);
  }

  private void cancelWriteAckTimeoutLocked() {
    mainHandler.removeCallbacks(writeAckPollRunnable);
    mainHandler.removeCallbacks(writeAckTimeoutRunnable);
  }

  private void resetWriteStateLocked(boolean keepLastPage) {
    cancelWriteAckTimeoutLocked();
    writeInFlight = false;
    inFlightPageRowHex = null;
    sentPageRowHex = null;
    if (!keepLastPage) {
      lastPageRowHex = null;
      queuedPageRowHex = null;
    } else {
      queuedPageRowHex = lastPageRowHex;
    }
  }

  private void sendPageToDevice(
      String[] pageRowHex, @Nullable String[] prevSentRowHex, @Nullable DotDevice device) {
    if (device == null) {
      onWriteAckOrComplete("device null");
      return;
    }
    // Dispatch on mainHandler so all dirty rows (1..10) are marked in dotPadSendModule before
    // dotPadSendModule's posted mainLooper runnable bundles and transmits them.
    mainHandler.post(
        () -> {
          boolean anyRowSent = false;
          try {
            for (int row = 0; row < pageRowHex.length; row++) {
              String rowHex = pageRowHex[row];
              String prevHex =
                  (prevSentRowHex != null && row < prevSentRowHex.length)
                      ? prevSentRowHex[row]
                      : null;
              if (Objects.equals(rowHex, prevHex)) {
                continue;
              }
              int lineId = GRAPHIC_FIRST_LINE_ID + row;
              BrailleDisplayLog.v(TAG, "displayLineData (lineId=" + lineId + "): " + rowHex);
              DotPadProcess.INSTANCE.displayLineData(
                  lineId, START_CELL_INDEX, rowHex, DisplayMode.TextMode, device);
              anyRowSent = true;
            }
          } catch (RuntimeException e) {
            BrailleDisplayLog.e(TAG, "Failed to send braille page via displayLineData", e);
            onWriteAckOrComplete("displayLineData exception");
            return;
          }
          if (!anyRowSent) {
            onWriteAckOrComplete("no changed rows");
          }
        });
  }

  /**
   * Formats {@code brailleDotBytes} as an uppercase hex string of exactly {@code numCells * 2}
   * characters (2 hex characters per braille cell), padding with {@code "00"} if fewer bytes are
   * provided or truncating to {@code numCells} bytes if longer.
   */
  public static String toHexLine(byte[] brailleDotBytes, int numCells) {
    StringBuilder sb = new StringBuilder(numCells * 2);
    for (int i = 0; i < numCells; i++) {
      int cellByte = (i < brailleDotBytes.length) ? (brailleDotBytes[i] & 0xFF) : 0;
      sb.append(String.format(Locale.US, "%02X", cellByte));
    }
    return sb.toString();
  }

  @Override
  public int readCommand() {
    Integer cmd = pendingCommands.poll();
    return cmd != null ? cmd : BrailleInputEvent.CMD_NONE;
  }

  private void enqueueCommand(int command) {
    if (command == BrailleInputEvent.CMD_NONE) {
      return;
    }
    pendingCommands.offer(command & 0xFFFF);
    D2dConnection.Callback cb = trafficCallback;
    if (cb != null) {
      mainHandler.post(cb::onRead);
    }
  }

  /**
   * Maps a Dot Pad {@link KeyCodes} event to a TalkBack {@link BrailleInputEvent} command constant.
   */
  public static int mapKeyCodeToCommand(KeyCodes keyCode) {
    if (keyCode == null) {
      return BrailleInputEvent.CMD_NONE;
    }
    return switch (keyCode) {
      case PanningLeft -> BrailleInputEvent.CMD_NAV_PAN_UP;
      case PanningRight -> BrailleInputEvent.CMD_NAV_PAN_DOWN;
      case KeyFunction1 -> BrailleInputEvent.CMD_NAV_ITEM_PREVIOUS;
      case KeyFunction2 -> BrailleInputEvent.CMD_NAV_ITEM_NEXT;
      case KeyFunction3 -> BrailleInputEvent.CMD_ACTIVATE_CURRENT;
      case KeyFunction4 -> BrailleInputEvent.CMD_GLOBAL_BACK;
      case KeyFunction12 -> BrailleInputEvent.CMD_GLOBAL_HOME;
      case KeyFunction34 -> BrailleInputEvent.CMD_GLOBAL_NOTIFICATIONS;
      case KeyFunction23, PanningAll -> BrailleInputEvent.CMD_OPEN_TALKBACK_MENU;
      case KeyFunction13 ->
          BrailleInputEvent.CMD_NAVIGATE_BY_READING_GRANULARITY_OR_ADJUST_READING_CONTROL_BACKWARD;
      case KeyFunction24 ->
          BrailleInputEvent.CMD_NAVIGATE_BY_READING_GRANULARITY_OR_ADJUST_READING_CONTROL_FORWARD;
      case KeyFunction14 -> BrailleInputEvent.CMD_GLOBAL_RECENTS;
      case LPF1 -> BrailleInputEvent.CMD_NAV_TOP;
      case RPF4 -> BrailleInputEvent.CMD_NAV_BOTTOM;
      default -> BrailleInputEvent.CMD_NONE;
    };
  }

  @Override
  public Predicate<String> getDeviceNameFilter() {
    return DotPadDriver::isDotPadDeviceName;
  }

  @Override
  public BiPredicate<Integer, Integer> getDeviceVendorProdIdFilter() {
    return (vendorId, prodId) -> false;
  }

  private static BrailleKeyBinding[] createDefaultKeyBindings() {
    return new BrailleKeyBinding[] {
      new BrailleKeyBinding(
          BrailleInputEvent.CMD_NAV_PAN_UP,
          new String[] {"PanLeft"},
          /* longPress= */ false,
          /* unifiedKeyBinding= */ false),
      new BrailleKeyBinding(
          BrailleInputEvent.CMD_NAV_PAN_DOWN,
          new String[] {"PanRight"},
          /* longPress= */ false,
          /* unifiedKeyBinding= */ false),
      new BrailleKeyBinding(
          BrailleInputEvent.CMD_NAV_ITEM_PREVIOUS,
          new String[] {"F1"},
          /* longPress= */ false,
          /* unifiedKeyBinding= */ false),
      new BrailleKeyBinding(
          BrailleInputEvent.CMD_NAV_ITEM_NEXT,
          new String[] {"F2"},
          /* longPress= */ false,
          /* unifiedKeyBinding= */ false),
      new BrailleKeyBinding(
          BrailleInputEvent.CMD_ACTIVATE_CURRENT,
          new String[] {"F3"},
          /* longPress= */ false,
          /* unifiedKeyBinding= */ false),
      new BrailleKeyBinding(
          BrailleInputEvent.CMD_GLOBAL_BACK,
          new String[] {"F4"},
          /* longPress= */ false,
          /* unifiedKeyBinding= */ false),
      new BrailleKeyBinding(
          BrailleInputEvent.CMD_GLOBAL_HOME,
          new String[] {"F1", "F2"},
          /* longPress= */ false,
          /* unifiedKeyBinding= */ false),
      new BrailleKeyBinding(
          BrailleInputEvent.CMD_GLOBAL_NOTIFICATIONS,
          new String[] {"F3", "F4"},
          /* longPress= */ false,
          /* unifiedKeyBinding= */ false),
      new BrailleKeyBinding(
          BrailleInputEvent.CMD_OPEN_TALKBACK_MENU,
          new String[] {"F2", "F3"},
          /* longPress= */ false,
          /* unifiedKeyBinding= */ false),
      new BrailleKeyBinding(
          BrailleInputEvent.CMD_OPEN_TALKBACK_MENU,
          new String[] {"PanLeft", "PanRight"},
          /* longPress= */ false,
          /* unifiedKeyBinding= */ false),
    };
  }

  private final DotPadSearchListener dotPadSearchListener =
      new DotPadSearchListener() {
        @Override
        public void onBLEScan(BluetoothDevice device) {
          BrailleDisplayLog.d(TAG, "DotPadScanner onBLEScan: " + device);
          ScanCallback cb = scanCallback;
          if (cb != null) {
            mainHandler.post(() -> cb.onBleDeviceFound(device));
          }
        }

        @Override
        public void onUSBScan(List<? extends UsbDevice> devices) {
          // USB support is out of scope for Phase 2/3.
        }

        @Override
        public void isBLEScanning(boolean isScanning) {
          BrailleDisplayLog.v(TAG, "DotPadScanner isBLEScanning: " + isScanning);
        }

        @Override
        public void onError(String msg) {
          BrailleDisplayLog.w(TAG, "DotPadScanner onError: " + msg);
        }
      };

  private final DotDeviceMessage dotDeviceMessageCallback =
      new DotDeviceMessage() {
        @Override
        public void receivedMessageCallBackWithDevice(
            DotDevice device, DataCodes dataCode, String msg) {
          BrailleDisplayLog.d(
              TAG, "receivedMessageCallBackWithDevice: code=" + dataCode + ", msg=" + msg);
          if (dataCode == DataCodes.Connected) {
            connectedDotDevice = device;
            DotPadProcess.INSTANCE.setAutoRefresh(true, device);
            String[] pendingToFlush = null;
            synchronized (writeLock) {
              boardInfoReady = true;
              resetWriteStateLocked(/* keepLastPage= */ true);
              if (queuedPageRowHex != null) {
                pendingToFlush = queuedPageRowHex;
                inFlightPageRowHex = pendingToFlush;
                sentPageRowHex = pendingToFlush.clone();
                queuedPageRowHex = null;
                writeInFlight = true;
                scheduleWriteAckTimeoutLocked();
              }
            }
            ConnectionCallback cb = connectionCallback;
            if (cb != null) {
              mainHandler.post(() -> cb.onConnected(device));
            }
            if (pendingToFlush != null) {
              BrailleDisplayLog.d(
                  TAG, "Connected/BoardInfo ready; flushing initial multi-line page to DotPad");
              sendPageToDevice(pendingToFlush, /* prevSentRowHex= */ null, device);
            }
          } else if (dataCode == DataCodes.BoardInfo) {
            connectedDotDevice = device;
            synchronized (writeLock) {
              boardInfoReady = true;
            }
            onWriteAckOrComplete("BoardInfo");
          } else if (dataCode == DataCodes.ResponseDisplayLineAck
              || dataCode == DataCodes.ResponseDisplayLineComplete
              || dataCode == DataCodes.ResponseDisplayLineNonAck) {
            onWriteAckOrComplete(dataCode.name() + "(" + msg + ")");
          } else if (dataCode == DataCodes.CommandSendFail
              || dataCode == DataCodes.CommandError) {
            BrailleDisplayLog.w(TAG, "DotPad write error: " + dataCode + " (" + msg + ")");
            onWriteAckOrComplete(dataCode.name());
          } else if (dataCode == DataCodes.Reconnecting) {
            synchronized (writeLock) {
              boardInfoReady = false;
              resetWriteStateLocked(/* keepLastPage= */ true);
            }
            int attempt = 1;
            try {
              attempt = Integer.parseInt(msg.trim());
            } catch (NumberFormatException ignored) {
              // Keep default attempt = 1.
            }
            final int reconnectAttempt = attempt;
            ConnectionCallback cb = connectionCallback;
            if (cb != null) {
              mainHandler.post(() -> cb.onReconnecting(reconnectAttempt));
            }
          } else if (dataCode == DataCodes.Disconnected) {
            connectedDotDevice = null;
            synchronized (writeLock) {
              boardInfoReady = false;
              resetWriteStateLocked(/* keepLastPage= */ false);
            }
            pendingCommands.clear();
            ConnectionCallback cb = connectionCallback;
            if (cb != null) {
              mainHandler.post(cb::onDisconnected);
            }
          } else if (dataCode == DataCodes.ConnectedFail) {
            connectedDotDevice = null;
            synchronized (writeLock) {
              boardInfoReady = false;
              resetWriteStateLocked(/* keepLastPage= */ false);
            }
            pendingCommands.clear();
            ConnectionCallback cb = connectionCallback;
            if (cb != null) {
              mainHandler.post(
                  () ->
                      cb.onConnectFailure(
                          new IllegalStateException("DotPad connection failed: " + msg)));
            }
          }
        }

        @Override
        public void onKeyDown(DotDevice device, DotPadKey dotPadKey, String dotKeyBinary) {
          BrailleDisplayLog.v(
              TAG, "onKeyDown: key=" + dotPadKey + ", binary=" + dotKeyBinary);
        }

        @Override
        public void onKeyUp(DotDevice device, DotPadKey dotPadKey, String dotKeyBinary) {
          BrailleDisplayLog.v(
              TAG, "onKeyUp: key=" + dotPadKey + ", binary=" + dotKeyBinary);
        }

        @Override
        public void receivedKeyCallBack(DotDevice device, KeyCodes keyCode, String msg) {
          int cmd = mapKeyCodeToCommand(keyCode);
          BrailleDisplayLog.d(
              TAG,
              "receivedKeyCallBack: keyCode=" + keyCode + ", msg=" + msg + " -> cmd=" + cmd);
          enqueueCommand(cmd);
        }

        @Override
        public void receivedErrorCallback(ErrorCodes errorCode, String msg) {
          BrailleDisplayLog.w(
              TAG, "DotPadProcess error: code=" + errorCode + ", msg=" + msg);
          if (errorCode == ErrorCodes.ConnectFailed) {
            ConnectionCallback cb = connectionCallback;
            if (cb != null) {
              mainHandler.post(
                  () ->
                      cb.onConnectFailure(
                          new IllegalStateException("DotPadProcess ConnectFailed: " + msg)));
            }
          }
        }

        @Override
        public void sendMessageCallback(String msg) {}

        @Override
        public void receivedMessageCallBack(DataCodes dataCode, String msg) {}
      };
}
