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

package com.google.android.accessibility.braille.brailledisplay.platform.access;

/**
 * The protocol between TalkBack and an app that wants to use the braille display directly.
 *
 * <p>A braille display accepts only one connection, so an app that talks to the display itself
 * (for example through a vendor SDK) must first borrow it from TalkBack. The app binds to {@link
 * BrailleDisplayAccessService} with {@link #ACTION_BRAILLE_DISPLAY_ACCESS} and exchanges {@link
 * android.os.Message}s with it through a {@link android.os.Messenger}:
 *
 * <ol>
 *   <li>The app sends {@link #MSG_REQUEST_ACCESS} with {@code replyTo} set to its own Messenger.
 *   <li>TalkBack disconnects from the display and replies {@link #MSG_ACCESS_GRANTED}, or replies
 *       {@link #MSG_ACCESS_DENIED} with a reason in {@code arg1}.
 *   <li>The app connects to the display and uses it.
 *   <li>When the app is done it disconnects and sends {@link #MSG_RELEASE_ACCESS}; TalkBack
 *       reconnects.
 *   <li>If another app comes to the foreground, TalkBack sends {@link #MSG_ACCESS_REVOKED}. The app
 *       must disconnect promptly and send {@link #MSG_RELEASE_ACCESS}; after a short timeout
 *       TalkBack reconnects anyway.
 * </ol>
 *
 * <p>Only the app in the foreground may request access. Locking or turning off the screen does not
 * revoke it. If the app's process dies, TalkBack reclaims the display immediately.
 *
 * <p>These values are a stable contract: client libraries copy them.
 */
public final class BrailleDisplayAccessContract {
  /** Intent action to bind to TalkBack's access service. */
  public static final String ACTION_BRAILLE_DISPLAY_ACCESS =
      "com.google.android.accessibility.braille.action.BRAILLE_DISPLAY_ACCESS";

  /** Permission a client must hold to bind to the access service. */
  public static final String PERMISSION_BRAILLE_DISPLAY_ACCESS =
      "com.google.android.accessibility.braille.permission.BRAILLE_DISPLAY_ACCESS";

  // Client to TalkBack.
  public static final int MSG_REQUEST_ACCESS = 1;
  public static final int MSG_RELEASE_ACCESS = 2;
  /**
   * Asks which braille display TalkBack is using, without borrowing it. TalkBack replies {@link
   * #MSG_DISPLAY_INFO}, or {@link #MSG_ACCESS_DENIED} if the app isn't in the foreground.
   */
  public static final int MSG_QUERY_DISPLAY = 3;

  // TalkBack to client.
  /**
   * The display is free for the app. {@code arg1} is {@link #GRANTED_DISPLAY_RELEASED} or {@link
   * #GRANTED_NO_DISPLAY}; with a released display, the data {@link android.os.Bundle} describes it
   * (see the {@code KEY_*} constants) so the app can connect without scanning.
   */
  public static final int MSG_ACCESS_GRANTED = 101;

  public static final int MSG_ACCESS_DENIED = 102;
  public static final int MSG_ACCESS_REVOKED = 103;

  /**
   * Reply to {@link #MSG_QUERY_DISPLAY}. {@code arg1} is 1 if TalkBack is using (or has lent) a
   * display, or remembers one it used before (see {@link #KEY_REMEMBERED}), described by the data
   * {@link android.os.Bundle}; 0 if not.
   */
  public static final int MSG_DISPLAY_INFO = 104;

  // Display description keys in the data Bundle of MSG_ACCESS_GRANTED and MSG_DISPLAY_INFO.
  /** String: the display's name, for example "DotPad320X_BC5B". */
  public static final String KEY_DISPLAY_NAME = "display_name";

  /** String: {@link #TRANSPORT_BLUETOOTH} or {@link #TRANSPORT_USB}. */
  public static final String KEY_TRANSPORT = "transport";

  /** String: Bluetooth address, for {@code BluetoothAdapter.getRemoteDevice()}. */
  public static final String KEY_BLUETOOTH_ADDRESS = "bluetooth_address";

  /** String: {@code UsbDevice.getDeviceName()}, a key of {@code UsbManager.getDeviceList()}. */
  public static final String KEY_USB_DEVICE_NAME = "usb_device_name";

  /** Int: USB vendor ID. */
  public static final String KEY_USB_VENDOR_ID = "usb_vendor_id";

  /** Int: USB product ID. */
  public static final String KEY_USB_PRODUCT_ID = "usb_product_id";

  /** Boolean: true if TalkBack is connected to the display now (false if lent or connecting). */
  public static final String KEY_CONNECTED_TO_TALKBACK = "connected_to_talkback";

  /**
   * Boolean: true if TalkBack has no display connected and this is the one it used most recently.
   * It may be switched off or out of range.
   */
  public static final String KEY_REMEMBERED = "remembered";

  public static final String TRANSPORT_BLUETOOTH = "bluetooth";
  public static final String TRANSPORT_USB = "usb";

  /**
   * {@link #MSG_ACCESS_GRANTED} {@code arg1}: 1 if TalkBack was connected to a display and released
   * it, 0 if it had no display connected.
   */
  public static final int GRANTED_DISPLAY_RELEASED = 1;

  public static final int GRANTED_NO_DISPLAY = 0;

  // MSG_ACCESS_DENIED reasons (arg1).
  /** Only the app in the foreground may request access or query the display. */
  public static final int DENIED_NOT_FOREGROUND = 1;

  private BrailleDisplayAccessContract() {}
}
