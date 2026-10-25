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
 * <p>Phase 2: Connects through {@link DotPadScanner} / {@link DotPadProcess}, reports a 20-cell
 * braille display to TalkBack, sends TalkBack's dot patterns directly to the 20-cell braille line
 * ({@code lineId = 0}) via {@link DotPadProcess#displayLineData} while respecting write
 * acknowledgements, and maps Dot Pad keys to TalkBack commands.
 */
public class DotPadDriver implements Encoder {
  private static final String TAG = "DotPadDriver";
  public static final String DRIVER_CODE = "dotpad";
  public static final String DEVICE_NAME_PREFIX = "DotPad";
  public static final int NUM_TEXT_CELLS = 20;
  public static final int NUM_STATUS_CELLS = 0;
  private static final int BRAILLE_LINE_ID = 0;
  private static final int START_CELL_INDEX = 0;
  private static final long WRITE_ACK_POLL_INTERVAL_MS = 20L;
  private static final long WRITE_ACK_TIMEOUT_MS = 1000L;

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
  @Nullable private String lastBrailleLineHex;
  @Nullable private String inFlightBrailleLineHex;
  @Nullable private String queuedBrailleLineHex;
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
      resetWriteStateLocked(/* keepLastLine= */ false);
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
      resetWriteStateLocked(/* keepLastLine= */ false);
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
    BrailleDisplayLog.d(
        TAG, "Starting DotPadDriver encoder for device: " + deviceName + " (20 cells)");
    DeviceInfo deviceInfo = SupportedDevicesHelper.getDeviceInfo(deviceName, useHid);
    BrailleKeyBinding[] keyBindings = createDefaultKeyBindings();
    Map<String, String> friendlyKeyNames = new HashMap<>();
    if (deviceInfo != null) {
      for (Map.Entry<String, Integer> entry : deviceInfo.friendlyKeyNames().entrySet()) {
        friendlyKeyNames.put(entry.getKey(), appContext.getString(entry.getValue()));
      }
    }
    int numTextCells = NUM_TEXT_CELLS;
    DotDevice dotDevice = connectedDotDevice;
    if (dotDevice != null && dotDevice.getNumberBrailleCellColumns() > 0) {
      numTextCells = dotDevice.getNumberBrailleCellColumns();
    }
    return Optional.of(
        new BrailleDisplayProperties(
            DRIVER_CODE, numTextCells, NUM_STATUS_CELLS, keyBindings, friendlyKeyNames));
  }

  @Override
  public void stop() {
    BrailleDisplayLog.d(TAG, "Stopping DotPadDriver encoder");
    synchronized (writeLock) {
      resetWriteStateLocked(/* keepLastLine= */ false);
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
    int numCells = NUM_TEXT_CELLS;
    DotDevice device = connectedDotDevice;
    if (device != null && device.getNumberBrailleCellColumns() > 0) {
      numCells = device.getNumberBrailleCellColumns();
    }
    String hexLine = toHexLine(brailleDotBytes, numCells);
    enqueueOrSendBrailleLine(hexLine);
  }

  private void enqueueOrSendBrailleLine(String hexLine) {
    String lineToSendNow = null;
    DotDevice deviceToSend = null;
    synchronized (writeLock) {
      lastBrailleLineHex = hexLine;
      DotDevice device = connectedDotDevice;
      if (device == null || !boardInfoReady) {
        queuedBrailleLineHex = hexLine;
        BrailleDisplayLog.v(
            TAG, "Queueing braille line until DotPad BoardInfo is ready: " + hexLine);
        return;
      }
      if (writeInFlight) {
        if (!Objects.equals(hexLine, inFlightBrailleLineHex)) {
          queuedBrailleLineHex = hexLine;
          BrailleDisplayLog.v(
              TAG, "Write in flight; coalescing latest braille line for next ACK: " + hexLine);
        } else {
          queuedBrailleLineHex = null;
        }
        return;
      }
      if (Objects.equals(hexLine, inFlightBrailleLineHex)) {
        return;
      }
      writeInFlight = true;
      inFlightBrailleLineHex = hexLine;
      queuedBrailleLineHex = null;
      lineToSendNow = hexLine;
      deviceToSend = device;
      scheduleWriteAckTimeoutLocked();
    }
    sendLineToDevice(lineToSendNow, deviceToSend);
  }

  private void onWriteAckOrComplete(String reason) {
    String nextLine = null;
    DotDevice device = null;
    synchronized (writeLock) {
      cancelWriteAckTimeoutLocked();
      writeInFlight = false;
      if (queuedBrailleLineHex != null && connectedDotDevice != null && boardInfoReady) {
        if (!Objects.equals(queuedBrailleLineHex, inFlightBrailleLineHex)) {
          nextLine = queuedBrailleLineHex;
          inFlightBrailleLineHex = nextLine;
          writeInFlight = true;
          device = connectedDotDevice;
          scheduleWriteAckTimeoutLocked();
        }
        queuedBrailleLineHex = null;
      }
    }
    if (nextLine != null && device != null) {
      BrailleDisplayLog.v(TAG, "Flushing coalesced braille line after " + reason + ": " + nextLine);
      sendLineToDevice(nextLine, device);
    }
  }

  private void onWriteAckTimeout() {
    BrailleDisplayLog.v(TAG, "Write ACK timeout elapsed; unblocking next queued braille line");
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

  private void resetWriteStateLocked(boolean keepLastLine) {
    cancelWriteAckTimeoutLocked();
    writeInFlight = false;
    inFlightBrailleLineHex = null;
    if (!keepLastLine) {
      lastBrailleLineHex = null;
      queuedBrailleLineHex = null;
    } else {
      queuedBrailleLineHex = lastBrailleLineHex;
    }
  }

  private void sendLineToDevice(String hexLine, @Nullable DotDevice device) {
    try {
      BrailleDisplayLog.v(TAG, "displayLineData (lineId=0): " + hexLine);
      DotPadProcess.INSTANCE.displayLineData(
          BRAILLE_LINE_ID, START_CELL_INDEX, hexLine, DisplayMode.TextMode, device);
    } catch (RuntimeException e) {
      BrailleDisplayLog.e(TAG, "Failed to send braille dots via displayLineData", e);
      onWriteAckOrComplete("displayLineData exception");
    }
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
          // USB support is out of scope for Phase 2 POC.
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
            String pendingToFlush = null;
            synchronized (writeLock) {
              boardInfoReady = true;
              resetWriteStateLocked(/* keepLastLine= */ true);
              if (queuedBrailleLineHex != null) {
                pendingToFlush = queuedBrailleLineHex;
                inFlightBrailleLineHex = pendingToFlush;
                queuedBrailleLineHex = null;
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
                  TAG, "Connected/BoardInfo ready; flushing initial braille line to DotPad");
              sendLineToDevice(pendingToFlush, device);
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
              resetWriteStateLocked(/* keepLastLine= */ true);
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
              resetWriteStateLocked(/* keepLastLine= */ false);
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
              resetWriteStateLocked(/* keepLastLine= */ false);
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
