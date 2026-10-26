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
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import androidx.annotation.Nullable;
import com.google.android.accessibility.braille.brailledisplay.BrailleDisplayLog;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Bluetooth Low Energy transport for the Dot Pad: scanning, GATT connection, notifications and a
 * chunked write queue.
 *
 * <p>The Dot Pad exposes a serial-style GATT service with one characteristic the host writes
 * packets to and one it receives packets on as notifications. Packets are larger than a default
 * BLE write, so outgoing bytes are split to the negotiated MTU and sent one chunk at a time.
 *
 * <p>All public methods must be called on the thread of the {@link Handler} passed in, and all
 * callbacks are delivered on it.
 */
@SuppressLint("MissingPermission") // TalkBack requests Bluetooth permissions before connecting.
public class DotPadBleTransport {
  private static final String TAG = "DotPadBleTransport";

  public static final UUID SERVICE_UUID = UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455");
  public static final UUID READ_CHARACTERISTIC_UUID =
      UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616");
  public static final UUID WRITE_CHARACTERISTIC_UUID =
      UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3");
  private static final UUID CLIENT_CONFIG_DESCRIPTOR_UUID =
      UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

  private static final int REQUESTED_MTU = 247;
  private static final int DEFAULT_MTU = 23;
  private static final int ATT_HEADER_LENGTH = 3;
  private static final long SETUP_TIMEOUT_MS = 15_000L;
  private static final long MTU_TIMEOUT_MS = 2_000L;
  private static final long WRITE_TIMEOUT_MS = 500L;
  private static final long WRITE_BUSY_RETRY_MS = 10L;
  private static final long RECONNECT_DELAY_MS = 2_000L;
  /** Attempts after an established link drops before giving up. */
  private static final int MAX_RECONNECT_ATTEMPTS = 5;
  /** Attempts for the first connection; Android often fails the first GATT connect (status 133). */
  private static final int MAX_INITIAL_CONNECT_ATTEMPTS = 3;

  /** Connection lifecycle and incoming data. */
  public interface Callback {
    /** The link is up and notifications are enabled; also called after each reconnection. */
    void onConnected();

    /** An established link dropped and reconnection attempt {@code attempt} is starting. */
    void onReconnecting(int attempt);

    /** An established link dropped and could not be re-established. */
    void onDisconnected();

    /** The first connection could not be established. */
    void onConnectFailure(Exception exception);

    void onDataReceived(byte[] data);
  }

  private final Context context;
  private final Handler handler;
  private final Queue<byte[]> writeQueue = new ArrayDeque<>();
  private final Set<String> reportedScanAddresses = new HashSet<>();

  @Nullable private ScanCallback activeScanCallback;
  @Nullable private Callback callback;
  @Nullable private BluetoothDevice device;
  @Nullable private BluetoothGatt gatt;
  @Nullable private BluetoothGattCharacteristic writeCharacteristic;
  /** Incremented on every connect and disconnect so callbacks from an old GATT are ignored. */
  private int generation;
  private int mtu = DEFAULT_MTU;
  private boolean ready;
  private boolean everReady;
  private int attempt;
  private boolean writeInFlight;

  private final Runnable setupTimeoutRunnable = () -> onLinkLost("setup timeout");
  private final Runnable mtuTimeoutRunnable = this::discoverServices;
  private final Runnable writeTimeoutRunnable = this::onChunkWritten;
  private final Runnable reconnectRunnable = this::openGatt;
  private final Runnable writeRetryRunnable = this::writeNextChunk;

  public DotPadBleTransport(Context context, Handler handler) {
    this.context = context;
    this.handler = handler;
  }

  // ---- Scanning ----

  /**
   * Starts a BLE scan and reports each device whose advertised name matches {@code nameFilter}
   * once per scan.
   */
  public void startScan(Predicate<String> nameFilter, Consumer<BluetoothDevice> onDeviceFound) {
    stopScan();
    BluetoothLeScanner scanner = getScanner();
    if (scanner == null) {
      BrailleDisplayLog.w(TAG, "BLE scanner unavailable; is Bluetooth on?");
      return;
    }
    reportedScanAddresses.clear();
    ScanCallback scanCallback =
        new ScanCallback() {
          @Override
          public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice found = result.getDevice();
            String name =
                result.getScanRecord() != null ? result.getScanRecord().getDeviceName() : null;
            if (name == null) {
              name = found.getName();
            }
            if (!nameFilter.test(name)) {
              return;
            }
            handler.post(
                () -> {
                  if (activeScanCallback == this
                      && reportedScanAddresses.add(found.getAddress())) {
                    BrailleDisplayLog.d(TAG, "Found Dot Pad: " + found.getAddress());
                    onDeviceFound.accept(found);
                  }
                });
          }

          @Override
          public void onScanFailed(int errorCode) {
            BrailleDisplayLog.w(TAG, "BLE scan failed: " + errorCode);
          }
        };
    ScanSettings settings =
        new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
    try {
      scanner.startScan(/* filters= */ null, settings, scanCallback);
      activeScanCallback = scanCallback;
      BrailleDisplayLog.d(TAG, "BLE scan started");
    } catch (RuntimeException e) {
      BrailleDisplayLog.e(TAG, "Failed to start BLE scan", e);
    }
  }

  public void stopScan() {
    ScanCallback scanCallback = activeScanCallback;
    activeScanCallback = null;
    if (scanCallback == null) {
      return;
    }
    BluetoothLeScanner scanner = getScanner();
    if (scanner == null) {
      return;
    }
    try {
      scanner.stopScan(scanCallback);
      BrailleDisplayLog.d(TAG, "BLE scan stopped");
    } catch (RuntimeException e) {
      BrailleDisplayLog.e(TAG, "Failed to stop BLE scan", e);
    }
  }

  @Nullable
  private BluetoothLeScanner getScanner() {
    BluetoothManager manager = context.getSystemService(BluetoothManager.class);
    BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
    return (adapter != null && adapter.isEnabled()) ? adapter.getBluetoothLeScanner() : null;
  }

  // ---- Connection ----

  /** Connects to {@code device}, replacing any existing connection. */
  public void connect(BluetoothDevice device, Callback callback) {
    closeGatt();
    this.device = device;
    this.callback = callback;
    everReady = false;
    attempt = 1;
    openGatt();
  }

  /** Closes the connection. No further callbacks are delivered. */
  public void disconnect() {
    callback = null;
    device = null;
    closeGatt();
  }

  public boolean isReady() {
    return ready;
  }

  private void openGatt() {
    BluetoothDevice target = device;
    if (target == null) {
      return;
    }
    closeGatt();
    int connectGeneration = generation;
    BrailleDisplayLog.d(TAG, "Connecting GATT to " + target.getAddress() + ", attempt " + attempt);
    handler.postDelayed(setupTimeoutRunnable, SETUP_TIMEOUT_MS);
    try {
      gatt =
          target.connectGatt(
              context,
              /* autoConnect= */ false,
              new GattCallback(connectGeneration),
              BluetoothDevice.TRANSPORT_LE);
    } catch (RuntimeException e) {
      BrailleDisplayLog.e(TAG, "connectGatt failed", e);
      gatt = null;
    }
    if (gatt == null) {
      onLinkLost("connectGatt returned null");
    }
  }

  /** Releases the current GATT, if any, and invalidates its pending callbacks. */
  private void closeGatt() {
    generation++;
    handler.removeCallbacks(setupTimeoutRunnable);
    handler.removeCallbacks(mtuTimeoutRunnable);
    handler.removeCallbacks(writeTimeoutRunnable);
    handler.removeCallbacks(writeRetryRunnable);
    handler.removeCallbacks(reconnectRunnable);
    ready = false;
    writeInFlight = false;
    writeQueue.clear();
    writeCharacteristic = null;
    mtu = DEFAULT_MTU;
    BluetoothGatt oldGatt = gatt;
    gatt = null;
    if (oldGatt != null) {
      try {
        oldGatt.disconnect();
        oldGatt.close();
      } catch (RuntimeException e) {
        BrailleDisplayLog.w(TAG, "Error closing GATT: " + e);
      }
    }
  }

  /** Handles the link dropping or failing to come up: reconnect, or report the outcome. */
  private void onLinkLost(String reason) {
    BrailleDisplayLog.d(TAG, "GATT link lost: " + reason);
    boolean wasReady = ready;
    closeGatt();
    Callback cb = callback;
    if (cb == null || device == null) {
      return;
    }
    if (wasReady) {
      attempt = 0;
    }
    int maxAttempts = everReady ? MAX_RECONNECT_ATTEMPTS : MAX_INITIAL_CONNECT_ATTEMPTS;
    if (attempt < maxAttempts) {
      attempt++;
      if (everReady) {
        cb.onReconnecting(attempt);
      }
      handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS);
      return;
    }
    callback = null;
    device = null;
    if (everReady) {
      cb.onDisconnected();
    } else {
      cb.onConnectFailure(new IllegalStateException("Dot Pad BLE connection failed: " + reason));
    }
  }

  private void discoverServices() {
    handler.removeCallbacks(mtuTimeoutRunnable);
    BluetoothGatt current = gatt;
    if (current != null && !current.discoverServices()) {
      onLinkLost("discoverServices failed");
    }
  }

  private void onServicesDiscovered(BluetoothGatt current) {
    BluetoothGattService service = current.getService(SERVICE_UUID);
    if (service == null) {
      onLinkLost("Dot Pad service not found");
      return;
    }
    BluetoothGattCharacteristic read = service.getCharacteristic(READ_CHARACTERISTIC_UUID);
    BluetoothGattCharacteristic write = service.getCharacteristic(WRITE_CHARACTERISTIC_UUID);
    if (read == null || write == null) {
      onLinkLost("Dot Pad characteristics not found");
      return;
    }
    writeCharacteristic = write;
    if (!current.setCharacteristicNotification(read, true)) {
      onLinkLost("setCharacteristicNotification failed");
      return;
    }
    BluetoothGattDescriptor config = read.getDescriptor(CLIENT_CONFIG_DESCRIPTOR_UUID);
    if (config == null) {
      // Some stacks deliver notifications without an explicit descriptor write.
      onReady();
      return;
    }
    byte[] value =
        (read.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
    if (!writeDescriptor(current, config, value)) {
      onLinkLost("Enabling notifications failed");
    }
  }

  @SuppressWarnings("deprecation") // Pre-Tiramisu descriptor write API.
  private static boolean writeDescriptor(
      BluetoothGatt current, BluetoothGattDescriptor descriptor, byte[] value) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      return current.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS;
    }
    descriptor.setValue(value);
    return current.writeDescriptor(descriptor);
  }

  private void onReady() {
    handler.removeCallbacks(setupTimeoutRunnable);
    ready = true;
    everReady = true;
    attempt = 0;
    BrailleDisplayLog.d(TAG, "Dot Pad BLE link ready, MTU " + mtu);
    Callback cb = callback;
    if (cb != null) {
      cb.onConnected();
    }
  }

  // ---- Writing ----

  /** Queues {@code bytes} for sending. Returns false if the link is not ready. */
  public boolean write(byte[] bytes) {
    if (!ready) {
      return false;
    }
    int chunkSize = Math.max(1, mtu - ATT_HEADER_LENGTH);
    for (int offset = 0; offset < bytes.length; offset += chunkSize) {
      writeQueue.add(Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + chunkSize)));
    }
    if (!writeInFlight) {
      writeNextChunk();
    }
    return true;
  }

  private void writeNextChunk() {
    BluetoothGatt current = gatt;
    BluetoothGattCharacteristic characteristic = writeCharacteristic;
    byte[] chunk = writeQueue.peek();
    if (current == null || characteristic == null || chunk == null) {
      writeInFlight = false;
      return;
    }
    writeInFlight = true;
    if (!writeCharacteristic(current, characteristic, chunk)) {
      // The stack is busy with a previous operation; try again shortly.
      handler.postDelayed(writeRetryRunnable, WRITE_BUSY_RETRY_MS);
      return;
    }
    writeQueue.poll();
    handler.postDelayed(writeTimeoutRunnable, WRITE_TIMEOUT_MS);
  }

  private void onChunkWritten() {
    handler.removeCallbacks(writeTimeoutRunnable);
    writeInFlight = false;
    if (!writeQueue.isEmpty()) {
      writeNextChunk();
    }
  }

  @SuppressWarnings("deprecation") // Pre-Tiramisu characteristic write API.
  private static boolean writeCharacteristic(
      BluetoothGatt current, BluetoothGattCharacteristic characteristic, byte[] chunk) {
    int writeType =
        (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
                != 0
            ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      return current.writeCharacteristic(characteristic, chunk, writeType)
          == BluetoothGatt.GATT_SUCCESS;
    }
    characteristic.setWriteType(writeType);
    characteristic.setValue(chunk);
    return current.writeCharacteristic(characteristic);
  }

  // ---- GATT callbacks (binder threads; everything is posted to the handler) ----

  private class GattCallback extends BluetoothGattCallback {
    private final int callbackGeneration;

    GattCallback(int callbackGeneration) {
      this.callbackGeneration = callbackGeneration;
    }

    private void post(Runnable runnable) {
      handler.post(
          () -> {
            if (callbackGeneration == generation) {
              runnable.run();
            }
          });
    }

    @Override
    public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
      post(
          () -> {
            if (newState == BluetoothProfile.STATE_CONNECTED
                && status == BluetoothGatt.GATT_SUCCESS) {
              BrailleDisplayLog.d(TAG, "GATT connected; requesting MTU " + REQUESTED_MTU);
              if (g.requestMtu(REQUESTED_MTU)) {
                handler.postDelayed(mtuTimeoutRunnable, MTU_TIMEOUT_MS);
              } else {
                discoverServices();
              }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED
                || status != BluetoothGatt.GATT_SUCCESS) {
              onLinkLost("state=" + newState + ", status=" + status);
            }
          });
    }

    @Override
    public void onMtuChanged(BluetoothGatt g, int newMtu, int status) {
      post(
          () -> {
            if (status == BluetoothGatt.GATT_SUCCESS) {
              mtu = newMtu;
            }
            BrailleDisplayLog.d(TAG, "MTU changed: " + newMtu + ", status=" + status);
            discoverServices();
          });
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt g, int status) {
      post(
          () -> {
            if (status == BluetoothGatt.GATT_SUCCESS) {
              DotPadBleTransport.this.onServicesDiscovered(g);
            } else {
              onLinkLost("service discovery status " + status);
            }
          });
    }

    @Override
    public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
      post(
          () -> {
            if (status == BluetoothGatt.GATT_SUCCESS) {
              onReady();
            } else {
              onLinkLost("enable notifications status " + status);
            }
          });
    }

    @Override
    public void onCharacteristicWrite(
        BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
      post(
          () -> {
            if (status != BluetoothGatt.GATT_SUCCESS) {
              BrailleDisplayLog.w(TAG, "Characteristic write status " + status);
            }
            onChunkWritten();
          });
    }

    @Override
    public void onCharacteristicChanged(
        BluetoothGatt g, BluetoothGattCharacteristic characteristic, byte[] value) {
      // Tiramisu and later.
      onNotification(characteristic, value);
    }

    @Override
    @SuppressWarnings("deprecation") // Called instead of the overload above before Tiramisu.
    public void onCharacteristicChanged(
        BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        onNotification(characteristic, characteristic.getValue());
      }
    }

    private void onNotification(
        BluetoothGattCharacteristic characteristic, @Nullable byte[] value) {
      if (value == null || !READ_CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
        return;
      }
      byte[] copy = value.clone();
      post(
          () -> {
            Callback cb = callback;
            if (cb != null) {
              cb.onDataReceived(copy);
            }
          });
    }
  }
}
