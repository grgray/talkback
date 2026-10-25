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

import com.google.android.accessibility.braille.brailledisplay.BrailleDisplayLog;
import com.google.android.accessibility.braille.brailledisplay.platform.connect.D2dConnection;
import com.google.android.accessibility.braille.brailledisplay.platform.connect.device.ConnectableDevice;
import com.google.android.accessibility.braille.brailledisplay.platform.lib.Utils;

/**
 * {@link D2dConnection} implementation backed by {@link DotPadDriver}.
 */
public class DotPadConnection implements D2dConnection {
  private static final String TAG = "DotPadConnection";

  private final ConnectableDevice device;
  private final DotPadDriver driver;
  private Callback callback;
  private boolean isShutdown;

  public DotPadConnection(ConnectableDevice device, DotPadDriver driver) {
    this.device = device;
    this.driver = driver;
  }

  @Override
  public void open(Callback callback) {
    Utils.assertMainThread();
    BrailleDisplayLog.d(TAG, "open DotPadConnection for " + device);
    this.callback = callback;
    driver.setTrafficCallback(callback);
  }

  @Override
  public void sendOutgoingPacket(byte[] packet) {
    if (isShutdown) {
      return;
    }
    // DotPadDriver sends packets directly via DotPadProcess.
  }

  @Override
  public void shutdown() {
    BrailleDisplayLog.d(TAG, "shutdown DotPadConnection");
    isShutdown = true;
    driver.setTrafficCallback(null);
    driver.disconnect();
  }

  @Override
  public ConnectableDevice getDevice() {
    return device;
  }

  public Callback getCallback() {
    return callback;
  }
}
