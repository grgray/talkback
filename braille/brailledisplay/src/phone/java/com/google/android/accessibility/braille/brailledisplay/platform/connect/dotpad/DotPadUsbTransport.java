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

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.HandlerThread;
import androidx.annotation.Nullable;
import com.google.android.accessibility.braille.brailledisplay.BrailleDisplayLog;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * USB transport for the Dot Pad, which connects through an FTDI USB-to-serial chip.
 *
 * <p>Android has no kernel driver for FTDI chips, so this talks to the chip directly: it claims
 * the first interface (port A), configures the serial port with FTDI vendor requests (115200 baud,
 * 8 data bits, no parity, 1 stop bit, no flow control) and then exchanges {@link DotPadPacket}
 * bytes over the bulk endpoints. Every bulk IN packet from an FTDI chip starts with two modem
 * status bytes, which are stripped before the data is passed on.
 *
 * <p>Public methods must be called on the main thread, and callbacks are delivered on it. USB
 * I/O runs on a background thread so blocking transfers never stall TalkBack.
 */
public class DotPadUsbTransport implements DotPadTransport {
  private static final String TAG = "DotPadUsbTransport";

  public static final int VENDOR_FTDI = 0x0403;
  /** FT2232 (dual port), used by the Dot Pad 320 family. */
  public static final int PRODUCT_FT2232 = 0x6010;
  /** FT-X series (single port), also accepted by Dot Inc's SDKs. */
  public static final int PRODUCT_FT_X = 0x6015;

  // FTDI vendor requests (host to device, vendor type, device recipient).
  private static final int FTDI_REQUEST_TYPE_OUT = 0x40;
  private static final int FTDI_SIO_RESET = 0;
  private static final int FTDI_SIO_MODEM_CTRL = 1;
  private static final int FTDI_SIO_SET_FLOW_CTRL = 2;
  private static final int FTDI_SIO_SET_BAUD_RATE = 3;
  private static final int FTDI_SIO_SET_DATA = 4;
  private static final int FTDI_SIO_SET_LATENCY_TIMER = 9;
  private static final int FTDI_RESET_SIO = 0;
  private static final int FTDI_PURGE_RX = 1;
  private static final int FTDI_PURGE_TX = 2;
  /** DTR and RTS on (each high byte bit enables changing the matching low byte bit). */
  private static final int FTDI_MODEM_DTR_RTS_ON = 0x0303;
  /** 3 MHz base clock / 26 = 115384 baud, within 0.2% of 115200. */
  private static final int FTDI_BAUD_115200 = 0x001A;
  /** 8 data bits, no parity, 1 stop bit. */
  private static final int FTDI_DATA_8N1 = 0x0008;
  /** Milliseconds the chip waits before sending a partly filled packet; the default is 16. */
  private static final int FTDI_LATENCY_MS = 2;
  private static final int FTDI_STATUS_BYTES = 2;

  private static final int CONTROL_TIMEOUT_MS = 1_000;
  private static final int WRITE_TIMEOUT_MS = 2_000;
  /** Short read timeout so the reader notices a disconnect promptly. */
  private static final int READ_TIMEOUT_MS = 100;
  private static final int DATA_INTERFACE_INDEX = 0;

  /** Returns true if {@code vendorId}/{@code productId} identify a Dot Pad's USB serial chip. */
  public static boolean isDotPadUsbId(int vendorId, int productId) {
    return vendorId == VENDOR_FTDI && (productId == PRODUCT_FT2232 || productId == PRODUCT_FT_X);
  }

  private final Context context;
  private final Handler mainHandler;

  @Nullable private Callback callback;
  @Nullable private HandlerThread ioThread;
  @Nullable private Handler ioHandler;
  @Nullable private Thread readerThread;
  /** Incremented on every connect and disconnect so callbacks from an old link are ignored. */
  private volatile int generation;
  private boolean ready;

  // Owned by the I/O and reader threads.
  @Nullable private volatile UsbDeviceConnection connection;
  @Nullable private volatile UsbInterface claimedInterface;
  @Nullable private volatile UsbEndpoint inEndpoint;
  @Nullable private volatile UsbEndpoint outEndpoint;
  private volatile boolean running;

  public DotPadUsbTransport(Context context, Handler mainHandler) {
    this.context = context;
    this.mainHandler = mainHandler;
  }

  /** Opens {@code device}, which must already have USB permission, replacing any connection. */
  public void connect(UsbDevice device, Callback callback) {
    disconnect();
    this.callback = callback;
    int connectGeneration = ++generation;
    HandlerThread thread = new HandlerThread("DotPadUsbIO");
    thread.start();
    ioThread = thread;
    ioHandler = new Handler(thread.getLooper());
    ioHandler.post(
        () -> {
          try {
            open(device);
          } catch (IOException | RuntimeException e) {
            BrailleDisplayLog.e(TAG, "Failed to open Dot Pad USB device", e);
            closeOnIoThread();
            postToMain(
                connectGeneration,
                () -> {
                  Callback cb = this.callback;
                  this.callback = null;
                  shutdownThreads();
                  if (cb != null) {
                    cb.onConnectFailure(e);
                  }
                });
            return;
          }
          startReader(device, connectGeneration);
          postToMain(
              connectGeneration,
              () -> {
                ready = true;
                BrailleDisplayLog.d(TAG, "Dot Pad USB link ready");
                Callback cb = this.callback;
                if (cb != null) {
                  cb.onConnected();
                }
              });
        });
  }

  @Override
  public void disconnect() {
    generation++;
    callback = null;
    ready = false;
    running = false;
    Handler io = ioHandler;
    if (io != null) {
      io.post(this::closeOnIoThread);
    }
    shutdownThreads();
  }

  @Override
  public boolean isReady() {
    return ready;
  }

  @Override
  public boolean write(byte[] bytes) {
    Handler io = ioHandler;
    if (!ready || io == null) {
      return false;
    }
    int writeGeneration = generation;
    byte[] copy = bytes.clone();
    io.post(
        () -> {
          try {
            writeOnIoThread(copy);
          } catch (IOException e) {
            BrailleDisplayLog.e(TAG, "USB write failed", e);
            postToMain(writeGeneration, () -> onLinkLost("write failed"));
          }
        });
    return true;
  }

  // ---- Main thread ----

  private void postToMain(int expectedGeneration, Runnable runnable) {
    mainHandler.post(
        () -> {
          if (expectedGeneration == generation) {
            runnable.run();
          }
        });
  }

  private void onLinkLost(String reason) {
    BrailleDisplayLog.d(TAG, "Dot Pad USB link lost: " + reason);
    Callback cb = callback;
    boolean wasReady = ready;
    disconnect();
    if (cb == null) {
      return;
    }
    if (wasReady) {
      cb.onDisconnected();
    } else {
      cb.onConnectFailure(new IOException("Dot Pad USB connection failed: " + reason));
    }
  }

  private void shutdownThreads() {
    HandlerThread thread = ioThread;
    ioThread = null;
    ioHandler = null;
    if (thread != null) {
      thread.quitSafely();
    }
    readerThread = null;
  }

  // ---- I/O thread ----

  private void open(UsbDevice device) throws IOException {
    UsbManager usbManager = context.getSystemService(UsbManager.class);
    if (usbManager == null) {
      throw new IOException("UsbManager unavailable");
    }
    if (device.getInterfaceCount() <= DATA_INTERFACE_INDEX) {
      throw new IOException("No USB interface");
    }
    UsbDeviceConnection opened = usbManager.openDevice(device);
    if (opened == null) {
      throw new IOException("openDevice failed (no permission?)");
    }
    connection = opened;
    UsbInterface usbInterface = device.getInterface(DATA_INTERFACE_INDEX);
    if (!opened.claimInterface(usbInterface, /* force= */ true)) {
      throw new IOException("claimInterface failed");
    }
    claimedInterface = usbInterface;
    for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
      UsbEndpoint endpoint = usbInterface.getEndpoint(i);
      if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) {
        continue;
      }
      if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
        inEndpoint = endpoint;
      } else {
        outEndpoint = endpoint;
      }
    }
    if (inEndpoint == null || outEndpoint == null) {
      throw new IOException("Bulk endpoints not found");
    }
    // FTDI requests address a port by number: port A = 1, port B = 2.
    int port = usbInterface.getId() + 1;
    control(FTDI_SIO_RESET, FTDI_RESET_SIO, port);
    control(FTDI_SIO_SET_BAUD_RATE, FTDI_BAUD_115200, port);
    control(FTDI_SIO_SET_DATA, FTDI_DATA_8N1, port);
    control(FTDI_SIO_SET_FLOW_CTRL, 0, port);
    control(FTDI_SIO_MODEM_CTRL, FTDI_MODEM_DTR_RTS_ON, port);
    control(FTDI_SIO_SET_LATENCY_TIMER, FTDI_LATENCY_MS, port);
    control(FTDI_SIO_RESET, FTDI_PURGE_RX, port);
    control(FTDI_SIO_RESET, FTDI_PURGE_TX, port);
    BrailleDisplayLog.d(
        TAG,
        "Opened Dot Pad USB "
            + String.format(Locale.US, "%04X:%04X", device.getVendorId(), device.getProductId())
            + " port "
            + port
            + ", max packet "
            + inEndpoint.getMaxPacketSize());
  }

  private void control(int request, int value, int index) throws IOException {
    UsbDeviceConnection current = connection;
    if (current == null) {
      throw new IOException("Not open");
    }
    int result =
        current.controlTransfer(
            FTDI_REQUEST_TYPE_OUT, request, value, index, null, 0, CONTROL_TIMEOUT_MS);
    if (result < 0) {
      throw new IOException("FTDI request " + request + " failed: " + result);
    }
  }

  private void writeOnIoThread(byte[] bytes) throws IOException {
    UsbDeviceConnection current = connection;
    UsbEndpoint out = outEndpoint;
    if (current == null || out == null) {
      throw new IOException("Not open");
    }
    int offset = 0;
    while (offset < bytes.length) {
      int sent = current.bulkTransfer(out, bytes, offset, bytes.length - offset, WRITE_TIMEOUT_MS);
      if (sent <= 0) {
        throw new IOException("bulkTransfer OUT returned " + sent);
      }
      offset += sent;
    }
  }

  private void closeOnIoThread() {
    UsbDeviceConnection current = connection;
    connection = null;
    UsbInterface usbInterface = claimedInterface;
    claimedInterface = null;
    inEndpoint = null;
    outEndpoint = null;
    if (current != null) {
      if (usbInterface != null) {
        current.releaseInterface(usbInterface);
      }
      current.close();
    }
  }

  // ---- Reader thread ----

  private void startReader(UsbDevice device, int readerGeneration) {
    running = true;
    Thread thread = new Thread(() -> readLoop(device, readerGeneration), "DotPadUsbReader");
    readerThread = thread;
    thread.start();
  }

  private void readLoop(UsbDevice device, int readerGeneration) {
    UsbEndpoint in = inEndpoint;
    UsbDeviceConnection current = connection;
    if (in == null || current == null) {
      return;
    }
    int packetSize = in.getMaxPacketSize();
    byte[] buffer = new byte[packetSize * 4];
    UsbManager usbManager = context.getSystemService(UsbManager.class);
    while (running && readerGeneration == generation) {
      int read = current.bulkTransfer(in, buffer, buffer.length, READ_TIMEOUT_MS);
      if (read > FTDI_STATUS_BYTES) {
        byte[] data = stripStatusBytes(buffer, read, packetSize);
        if (data.length > 0) {
          postToMain(
              readerGeneration,
              () -> {
                Callback cb = callback;
                if (cb != null) {
                  cb.onDataReceived(data);
                }
              });
        }
      } else if (read < 0 && !isAttached(usbManager, device)) {
        // A timeout also returns -1, so only treat it as a disconnect once the device is gone.
        postToMain(readerGeneration, () -> onLinkLost("device detached"));
        return;
      }
    }
  }

  /** Removes the two FTDI status bytes at the start of every {@code packetSize}-byte packet. */
  static byte[] stripStatusBytes(byte[] buffer, int length, int packetSize) {
    ByteArrayOutputStream data = new ByteArrayOutputStream(length);
    for (int start = 0; start < length; start += packetSize) {
      int end = Math.min(start + packetSize, length);
      if (end - start > FTDI_STATUS_BYTES) {
        data.write(buffer, start + FTDI_STATUS_BYTES, end - start - FTDI_STATUS_BYTES);
      }
    }
    return data.toByteArray();
  }

  private static boolean isAttached(@Nullable UsbManager usbManager, UsbDevice device) {
    return usbManager != null && usbManager.getDeviceList().containsKey(device.getDeviceName());
  }
}
