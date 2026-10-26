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

/**
 * A byte pipe to a Dot Pad: Bluetooth LE ({@link DotPadBleTransport}) or USB ({@link
 * DotPadUsbTransport}). Both carry the same {@link DotPadPacket} stream.
 *
 * <p>Methods are called, and callbacks delivered, on the main thread.
 */
public interface DotPadTransport {

  /** Connection lifecycle and incoming data. */
  interface Callback {
    /** The link is up and ready for writes; also called after each reconnection. */
    void onConnected();

    /** An established link dropped and reconnection attempt {@code attempt} is starting. */
    void onReconnecting(int attempt);

    /** An established link dropped and could not be re-established. */
    void onDisconnected();

    /** The first connection could not be established. */
    void onConnectFailure(Exception exception);

    /** Bytes arrived from the display, in arbitrary chunks. */
    void onDataReceived(byte[] data);
  }

  /** Queues {@code bytes} for sending. Returns false if the link is not ready. */
  boolean write(byte[] bytes);

  /** Closes the connection. No further callbacks are delivered. */
  void disconnect();

  boolean isReady();
}
