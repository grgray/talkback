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

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import com.google.android.accessibility.braille.brailledisplay.BrailleDisplayLog;
import com.google.android.accessibility.braille.brailledisplay.platform.connect.Connector;
import com.google.android.accessibility.braille.brailledisplay.platform.connect.device.ConnectableBluetoothDevice;
import com.google.android.accessibility.braille.brailledisplay.platform.connect.device.ConnectableDevice;
import com.google.android.accessibility.braille.brailledisplay.platform.lib.Utils;

/**
 * Sets up a Bluetooth Low Energy (BLE) connection to a Dot Pad braille display using {@link
 * DotPadDriver}.
 */
public class DotPadConnector extends Connector {
  private static final String TAG = "DotPadConnector";

  private final DotPadDriver driver;
  private boolean disconnected;
  private boolean connectedNotified;

  public DotPadConnector(
      Context context, ConnectableDevice device, Connector.Callback callback) {
    super(device, callback);
    Utils.assertMainThread();
    this.driver = DotPadDriver.getInstance(context);
  }

  @Override
  public void connect() {
    BrailleDisplayLog.d(TAG, "connect to DotPad: " + getDevice());
    disconnected = false;
    connectedNotified = false;
    BluetoothDevice bluetoothDevice = ((ConnectableBluetoothDevice) getDevice()).bluetoothDevice();
    driver.connect(
        bluetoothDevice,
        new DotPadDriver.ConnectionCallback() {
          @Override
          public void onConnected() {
            if (disconnected) {
              BrailleDisplayLog.d(TAG, "Ignore onConnected after disconnect");
              return;
            }
            if (connectedNotified) {
              BrailleDisplayLog.d(TAG, "DotPad reconnected; connection remains active");
              return;
            }
            connectedNotified = true;
            BrailleDisplayLog.d(TAG, "DotPad connected; notifying Connector.Callback");
            DotPadConnection connection = new DotPadConnection(getDevice(), driver);
            getConnectorCallback().onConnectSuccess(connection);
          }

          @Override
          public void onReconnecting(int attempt) {
            if (disconnected) {
              return;
            }
            BrailleDisplayLog.d(TAG, "DotPad BLE reconnecting (attempt " + attempt + ")");
          }

          @Override
          public void onDisconnected() {
            if (disconnected) {
              return;
            }
            BrailleDisplayLog.d(TAG, "DotPad disconnected; notifying Connector.Callback");
            disconnected = true;
            connectedNotified = false;
            getConnectorCallback().onDisconnected();
          }

          @Override
          public void onConnectFailure(Exception exception) {
            if (disconnected) {
              return;
            }
            BrailleDisplayLog.e(TAG, "DotPad connect failure", exception);
            disconnected = true;
            connectedNotified = false;
            getConnectorCallback().onConnectFailure(getDevice(), exception);
          }
        });
  }

  @Override
  public void disconnect() {
    BrailleDisplayLog.d(TAG, "disconnect DotPadConnector");
    disconnected = true;
    driver.disconnect();
  }
}
