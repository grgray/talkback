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

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Pair;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.braille.brailledisplay.BrailleDisplayLog;
import com.google.android.accessibility.braille.brailledisplay.R;
import com.google.android.accessibility.braille.brailledisplay.platform.Connectioneer;
import com.google.android.accessibility.braille.brailledisplay.platform.PersistentStorage;
import com.google.android.accessibility.braille.brailledisplay.platform.connect.device.ConnectableDevice;
import com.google.android.accessibility.braille.brailledisplay.platform.connect.device.ConnectableUsbDevice;
import com.google.android.accessibility.braille.common.BrailleCommonTalkBackSpeaker;
import com.google.android.accessibility.braille.common.TalkBackSpeaker.AnnounceType;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import java.util.ArrayList;
import java.util.List;

/**
 * Lends the braille display to a third-party app and takes it back.
 *
 * <p>Works for any display TalkBack drives: it releases and reclaims the display through {@link
 * Connectioneer}'s normal disconnect and connect paths. See {@link BrailleDisplayAccessContract}
 * for the protocol.
 *
 * <p>Rules:
 *
 * <ul>
 *   <li>Only the foreground app may borrow the display.
 *   <li>When a different app comes to the foreground, access is revoked after {@link
 *       #FOREGROUND_GRACE_MS}, so brief switches (dialogs, the notification shade) don't bounce the
 *       display. Locking or turning off the screen never revokes access.
 *   <li>A revoked app gets {@link #RELEASE_TIMEOUT_MS} to disconnect before TalkBack reconnects.
 *   <li>If the borrowing app's process dies, TalkBack reclaims the display immediately.
 *   <li>If the user connects a display from TalkBack's settings, TalkBack reclaims it.
 * </ul>
 *
 * <p><b>Open lending</b>, for clients that can't use the access service, such as web apps using
 * Web Bluetooth: {@link #lendToForegroundApp} releases the display so whatever the user connects
 * next can use it. It starts when the user activates a button marked with {@link
 * #CONNECT_BUTTON_ROLE_DESCRIPTION}, or with the lend command. With no client to ask, TalkBack
 * takes the display back when the display's Bluetooth connection ends, or if nothing connects
 * within {@link #OPEN_LEND_CONNECT_TIMEOUT_MS}, or if the user leaves the app before anything
 * connects.
 *
 * <p>All state is on the main thread.
 */
public final class BrailleDisplayLeaseManager {
  private static final String TAG = "BrailleDisplayLease";
  /** How long another app must stay in the foreground before access is revoked. */
  static final long FOREGROUND_GRACE_MS = 1_000L;
  /** How long a revoked app has to disconnect before TalkBack reconnects anyway. */
  static final long RELEASE_TIMEOUT_MS = 2_000L;
  /** Time for TalkBack's link to the display to drop before the app is told to connect. */
  static final long LINK_RELEASE_DELAY_MS = 500L;
  /** How long an open lend waits for something to connect before TalkBack takes the display back. */
  static final long OPEN_LEND_CONNECT_TIMEOUT_MS = 60_000L;
  /** How long the tap on a web app's connect button lasts. */
  private static final long CONNECT_TAP_DURATION_MS = 50;
  /** Pause between lending the display and tapping a web app's connect button. */
  private static final long CONNECT_TAP_DELAY_MS = 500;
  /** Pause after the tap before touch exploration covers the button again. */
  private static final long PASSTHROUGH_CLEAR_DELAY_MS = 300;
  /** Most nodes to look through when searching a browser's device list. */
  private static final int MAX_CHOOSER_NODES = 300;

  /**
   * Role description that marks a web app's "connect to braille display" button, set with {@code
   * aria-roledescription}. Activating it lends the display.
   */
  public static final String CONNECT_BUTTON_ROLE_DESCRIPTION = "braille display connect";

  private enum State {
    /** TalkBack owns the display (or has none). */
    IDLE,
    /** TalkBack is letting go of the display for an app. */
    GRANTING,
    /** An app holds the display. */
    GRANTED,
    /** TalkBack asked the app to give the display back. */
    REVOKING,
  }

  /** An app's hold, or request for a hold, on the display. */
  private final class Lease implements IBinder.DeathRecipient {
    final int uid;
    final String packageName;
    final Messenger client;

    Lease(int uid, String packageName, Messenger client) {
      this.uid = uid;
      this.packageName = packageName;
      this.client = client;
    }

    @Override
    public void binderDied() {
      mainHandler.post(() -> onClientDied(this));
    }

    void link() {
      try {
        client.getBinder().linkToDeath(this, 0);
      } catch (RemoteException e) {
        // Already dead; binderDied will not be called, so handle it now.
        mainHandler.post(() -> onClientDied(this));
      }
    }

    void unlink() {
      client.getBinder().unlinkToDeath(this, 0);
    }

    void send(int what, int arg1) {
      send(what, arg1, null);
    }

    void send(int what, int arg1, @Nullable Bundle data) {
      Message message = Message.obtain(null, what, arg1, 0);
      if (data != null) {
        message.setData(data);
      }
      try {
        client.send(message);
      } catch (RemoteException e) {
        BrailleDisplayLog.w(TAG, "Could not reach " + packageName + ": " + e);
      }
    }
  }

  @SuppressLint("StaticFieldLeak")
  private static BrailleDisplayLeaseManager instance;

  /** Returns the singleton instance. Main thread only. */
  public static synchronized BrailleDisplayLeaseManager getInstance(Context context) {
    if (instance == null) {
      instance = new BrailleDisplayLeaseManager(context.getApplicationContext());
    }
    return instance;
  }

  private final Context context;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final Runnable finishGrantRunnable = this::finishGrant;
  private final Runnable foregroundGraceRunnable = this::onForegroundGraceElapsed;
  private boolean graceScheduled;
  private final Runnable releaseTimeoutRunnable = this::onReleaseTimeout;

  @Nullable private AccessibilityService accessibilityService;
  private State state = State.IDLE;
  @Nullable private Lease lease;
  /** An app that asked for the display while another app was still giving it back. */
  @Nullable private Lease pending;
  /** True while TalkBack has given up the display, from the first grant until it reclaims it. */
  private boolean displaySuspended;
  /** The display TalkBack released, to reconnect when the lease ends; may be null. */
  @Nullable private ConnectableDevice releasedDevice;

  // Open lending (no client): all main thread.
  private final Runnable openLendTimeoutRunnable = this::onOpenLendTimeout;
  private final Runnable openLendForegroundRunnable = this::onOpenLendForegroundElapsed;
  /** True while the display is lent openly, to whatever connects next. */
  private boolean openLend;
  /** The app in the foreground when the display was lent openly, such as the browser. */
  @Nullable private String openLendPackage;
  /** True once something has connected to the display during an open lend. */
  private boolean openLendConnected;
  @Nullable private BroadcastReceiver aclReceiver;
  /** Whether the display has been selected in the browser's device list during this open lend. */
  private boolean chooserDeviceSelected;
  /** Whether focus has been moved to the device list's pair button during this open lend. */
  private boolean chooserPairFocused;

  private BrailleDisplayLeaseManager(Context context) {
    this.context = context;
  }

  /** TalkBack's accessibility service started; it is used to find the foreground app. */
  public void onServiceStarted(AccessibilityService service) {
    accessibilityService = service;
  }

  /**
   * TalkBack's accessibility service stopped. Any app keeps the display; there is nothing to
   * reclaim it into.
   */
  public void onServiceStopped() {
    stopOpenLend();
    accessibilityService = null;
    if (lease != null) {
      lease.unlink();
      lease = null;
    }
    if (pending != null) {
      pending.unlink();
      pending = null;
    }
    mainHandler.removeCallbacksAndMessages(null);
    graceScheduled = false;
    state = State.IDLE;
    displaySuspended = false;
    releasedDevice = null;
  }

  /** True while an app holds, or is being given, the display. */
  public boolean isDisplayLent() {
    return displaySuspended;
  }

  // ---- Requests from apps ----

  void onRequestAccess(int uid, @Nullable Messenger replyTo) {
    if (replyTo == null) {
      return;
    }
    String packageName = packageForUid(uid);
    Lease request = new Lease(uid, packageName, replyTo);
    if (!isForegroundUid(uid)) {
      BrailleDisplayLog.w(TAG, packageName + " asked for the display but is not in the foreground");
      request.send(
          BrailleDisplayAccessContract.MSG_ACCESS_DENIED,
          BrailleDisplayAccessContract.DENIED_NOT_FOREGROUND);
      return;
    }
    BrailleDisplayLog.d(TAG, packageName + " requests the display; state " + state);
    if (lease != null && lease.uid == uid && state != State.REVOKING) {
      // Repeated request from the current holder.
      if (state == State.GRANTED) {
        request.send(
            BrailleDisplayAccessContract.MSG_ACCESS_GRANTED, grantedArg(), describeForApp(releasedDevice));
      }
      cancelGrace();
      return;
    }
    if (lease == null) {
      grant(request);
      return;
    }
    // Another app holds the display, or the holder is giving it back: queue this request and make
    // sure the holder is being asked to release. The requester is in the foreground, so there is
    // no grace period.
    if (pending != null) {
      pending.unlink();
    }
    pending = request;
    request.link();
    revoke();
  }

  /** Tells a foreground app which display TalkBack is using or has lent, without lending it. */
  void onQueryDisplay(int uid, @Nullable Messenger replyTo) {
    if (replyTo == null) {
      return;
    }
    Lease asker = new Lease(uid, packageForUid(uid), replyTo);
    if (!isForegroundUid(uid)) {
      asker.send(
          BrailleDisplayAccessContract.MSG_ACCESS_DENIED,
          BrailleDisplayAccessContract.DENIED_NOT_FOREGROUND);
      return;
    }
    ConnectableDevice device = null;
    boolean connectedToTalkBack = false;
    if (displaySuspended) {
      device = releasedDevice;
    } else if (accessibilityService != null) {
      Connectioneer connectioneer = Connectioneer.getInstance(context);
      device = connectioneer.getConnectingOrConnectedDevice().orElse(null);
      connectedToTalkBack = device != null && connectioneer.isConnected();
    }
    Bundle data = describeForApp(device);
    if (data != null) {
      data.putBoolean(BrailleDisplayAccessContract.KEY_CONNECTED_TO_TALKBACK, connectedToTalkBack);
    }
    asker.send(BrailleDisplayAccessContract.MSG_DISPLAY_INFO, data != null ? 1 : 0, data);
  }

  /**
   * Describes {@code device}, or if it is null the display TalkBack used most recently, so an app
   * can connect to it without scanning.
   */
  @Nullable
  private Bundle describeForApp(@Nullable ConnectableDevice device) {
    if (device != null) {
      return describe(device);
    }
    // TalkBack remembers Bluetooth displays only, most recent first.
    List<Pair<String, String>> remembered = PersistentStorage.getRememberedDevices(context);
    if (remembered.isEmpty()) {
      return null;
    }
    Pair<String, String> latest = remembered.get(0);
    Bundle data = new Bundle();
    data.putString(BrailleDisplayAccessContract.KEY_DISPLAY_NAME, latest.first);
    data.putString(
        BrailleDisplayAccessContract.KEY_TRANSPORT,
        BrailleDisplayAccessContract.TRANSPORT_BLUETOOTH);
    data.putString(BrailleDisplayAccessContract.KEY_BLUETOOTH_ADDRESS, latest.second);
    data.putBoolean(BrailleDisplayAccessContract.KEY_REMEMBERED, true);
    return data;
  }

  /** Describes {@code device} so an app can connect to it without scanning. */
  @Nullable
  private static Bundle describe(@Nullable ConnectableDevice device) {
    if (device == null) {
      return null;
    }
    Bundle data = new Bundle();
    data.putString(BrailleDisplayAccessContract.KEY_DISPLAY_NAME, device.name());
    if (device instanceof ConnectableUsbDevice) {
      data.putString(
          BrailleDisplayAccessContract.KEY_TRANSPORT, BrailleDisplayAccessContract.TRANSPORT_USB);
      data.putString(
          BrailleDisplayAccessContract.KEY_USB_DEVICE_NAME,
          ((ConnectableUsbDevice) device).usbDevice().getDeviceName());
      data.putInt(BrailleDisplayAccessContract.KEY_USB_VENDOR_ID, device.vendorId());
      data.putInt(BrailleDisplayAccessContract.KEY_USB_PRODUCT_ID, device.productId());
    } else {
      data.putString(
          BrailleDisplayAccessContract.KEY_TRANSPORT,
          BrailleDisplayAccessContract.TRANSPORT_BLUETOOTH);
      data.putString(BrailleDisplayAccessContract.KEY_BLUETOOTH_ADDRESS, device.address());
    }
    return data;
  }

  void onReleaseAccess(int uid) {
    if (pending != null && pending.uid == uid) {
      pending.unlink();
      pending = null;
    }
    if (lease == null || lease.uid != uid) {
      return;
    }
    BrailleDisplayLog.d(TAG, lease.packageName + " released the display");
    endLease();
  }

  // ---- State transitions ----

  private void grant(Lease request) {
    // An app asking through the access service takes over an open lend; the display is already
    // released.
    stopOpenLend();
    lease = request;
    if (pending == request) {
      pending = null;
    } else {
      request.link();
    }
    state = State.GRANTING;
    cancelGrace();
    long delay = 0;
    // Without TalkBack's service running there is no TalkBack connection to release.
    if (!displaySuspended && accessibilityService != null) {
      displaySuspended = true;
      releasedDevice = Connectioneer.getInstance(context).releaseDisplayForExternalApp().orElse(null);
      if (releasedDevice != null) {
        delay = LINK_RELEASE_DELAY_MS;
      }
    }
    announce(context.getString(R.string.bd_access_lent, appLabel(request.packageName)));
    mainHandler.postDelayed(finishGrantRunnable, delay);
  }

  private void finishGrant() {
    if (lease == null || state != State.GRANTING) {
      return;
    }
    state = State.GRANTED;
    BrailleDisplayLog.d(TAG, "Display lent to " + lease.packageName);
    lease.send(
        BrailleDisplayAccessContract.MSG_ACCESS_GRANTED, grantedArg(), describeForApp(releasedDevice));
    // The app may have left the foreground while TalkBack was letting go.
    onForegroundMaybeChanged();
  }

  private int grantedArg() {
    return releasedDevice != null
        ? BrailleDisplayAccessContract.GRANTED_DISPLAY_RELEASED
        : BrailleDisplayAccessContract.GRANTED_NO_DISPLAY;
  }

  /** Asks the holder to give the display back, and reclaims it after a timeout. */
  private void revoke() {
    if (lease == null || state == State.REVOKING) {
      return;
    }
    cancelGrace();
    mainHandler.removeCallbacks(finishGrantRunnable);
    boolean wasGranted = state == State.GRANTED;
    state = State.REVOKING;
    BrailleDisplayLog.d(TAG, "Revoking the display from " + lease.packageName);
    if (wasGranted) {
      lease.send(BrailleDisplayAccessContract.MSG_ACCESS_REVOKED, 0);
      mainHandler.postDelayed(releaseTimeoutRunnable, RELEASE_TIMEOUT_MS);
    } else {
      // Never told it was granted, so it has not connected: end at once.
      endLease();
    }
  }

  private void onReleaseTimeout() {
    if (lease != null) {
      BrailleDisplayLog.w(TAG, lease.packageName + " did not release the display in time");
    }
    endLease();
  }

  private void onClientDied(Lease died) {
    if (pending == died) {
      pending = null;
    }
    if (lease == died) {
      BrailleDisplayLog.w(TAG, died.packageName + " died while holding the display");
      endLease();
    }
  }

  /** The current lease is over: hand the display to a waiting app, or back to TalkBack. */
  private void endLease() {
    mainHandler.removeCallbacks(finishGrantRunnable);
    cancelGrace();
    mainHandler.removeCallbacks(releaseTimeoutRunnable);
    if (lease != null) {
      lease.unlink();
      lease = null;
    }
    state = State.IDLE;
    Lease next = pending;
    if (next != null && isForegroundUid(next.uid)) {
      // Pass the display straight to the waiting app without reconnecting TalkBack in between.
      grant(next);
      return;
    }
    if (next != null) {
      next.send(
          BrailleDisplayAccessContract.MSG_ACCESS_DENIED,
          BrailleDisplayAccessContract.DENIED_NOT_FOREGROUND);
      next.unlink();
      pending = null;
    }
    reclaim();
  }

  private void reclaim() {
    if (!displaySuspended) {
      return;
    }
    displaySuspended = false;
    ConnectableDevice device = releasedDevice;
    releasedDevice = null;
    BrailleDisplayLog.d(TAG, "Reclaiming the display for TalkBack");
    announce(context.getString(R.string.bd_access_returned));
    Connectioneer.getInstance(context).reclaimDisplayFromExternalApp(device);
  }

  /**
   * The user chose to connect a display from TalkBack's settings: tell any app to let go. The
   * caller connects the display itself.
   */
  public void onUserReclaimedDisplay() {
    if (!displaySuspended) {
      return;
    }
    BrailleDisplayLog.d(TAG, "User reclaimed the display");
    stopOpenLend();
    mainHandler.removeCallbacksAndMessages(null);
    graceScheduled = false;
    if (lease != null) {
      lease.send(BrailleDisplayAccessContract.MSG_ACCESS_REVOKED, 0);
      lease.unlink();
      lease = null;
    }
    if (pending != null) {
      pending.unlink();
      pending = null;
    }
    state = State.IDLE;
    displaySuspended = false;
    releasedDevice = null;
    announce(context.getString(R.string.bd_access_returned));
  }

  // ---- Open lending ----

  /** Returns whether {@code node} is a web app's button marked for connecting a braille display. */
  public static boolean isConnectButton(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null) {
      return false;
    }
    CharSequence roleDescription = node.getRoleDescription();
    return roleDescription != null
        && CONNECT_BUTTON_ROLE_DESCRIPTION.equalsIgnoreCase(roleDescription.toString().trim());
  }

  /**
   * If {@code node} is a web app's marked connect button, lends the display and taps the button for
   * real. Browsers only show their device list for a genuine user gesture, and an accessibility
   * click (from a braille display key or a double-tap) isn't one. Returns {@code false} if {@code
   * node} isn't a marked connect button or the tap can't be sent, so the caller activates it as
   * usual. Main thread only.
   */
  public boolean activateConnectButton(@Nullable AccessibilityNodeInfoCompat node) {
    if (!isConnectButton(node)) {
      return false;
    }
    // Let go first, so the display is free when the browser looks for it.
    lendToForegroundApp();
    AccessibilityService service = accessibilityService;
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    if (service == null || bounds.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      return false;
    }
    // While touch exploration is on, a tap only explores. A passthrough region over the button
    // sends touches there straight to the app, so the tap presses the button.
    service.setTouchExplorationPassthroughRegion(Display.DEFAULT_DISPLAY, new Region(bounds));
    Path path = new Path();
    path.moveTo(bounds.exactCenterX(), bounds.exactCenterY());
    GestureDescription tap =
        new GestureDescription.Builder()
            .addStroke(
                new GestureDescription.StrokeDescription(
                    path, /* startTime= */ 0, /* duration= */ CONNECT_TAP_DURATION_MS))
            .build();
    AccessibilityService.GestureResultCallback clearRegion =
        new AccessibilityService.GestureResultCallback() {
          @Override
          public void onCompleted(GestureDescription gesture) {
            clearPassthroughRegion(service);
          }

          @Override
          public void onCancelled(GestureDescription gesture) {
            clearPassthroughRegion(service);
          }
        };
    // Give the region a moment to take effect, and the display a moment to start advertising, so
    // it is already in the browser's list when the list opens.
    mainHandler.postDelayed(
        () -> {
          if (!service.dispatchGesture(tap, clearRegion, /* handler= */ null)) {
            clearPassthroughRegion(service);
          }
        },
        CONNECT_TAP_DELAY_MS);
    return true;
  }

  @RequiresApi(Build.VERSION_CODES.R)
  private void clearPassthroughRegion(AccessibilityService service) {
    // Wait for the tap's touch events to pass before touch exploration covers the button again.
    mainHandler.postDelayed(
        () -> service.setTouchExplorationPassthroughRegion(Display.DEFAULT_DISPLAY, new Region()),
        PASSTHROUGH_CLEAR_DELAY_MS);
  }

  /**
   * Lends the display to whatever the user connects next, for apps that can't use the access
   * service, such as web apps. Returns {@code false} if there is no display to lend, or it is
   * already lent.
   */
  public boolean lendToForegroundApp() {
    if (displaySuspended || lease != null || accessibilityService == null) {
      return false;
    }
    Connectioneer connectioneer = Connectioneer.getInstance(context);
    if (connectioneer.getConnectingOrConnectedDevice().isEmpty()) {
      return false;
    }
    displaySuspended = true;
    releasedDevice = connectioneer.releaseDisplayForExternalApp().orElse(null);
    openLend = true;
    openLendPackage = foregroundPackage();
    openLendConnected = false;
    chooserDeviceSelected = false;
    chooserPairFocused = false;
    BrailleDisplayLog.d(TAG, "Display lent openly while " + openLendPackage + " is in front");
    registerAclReceiver();
    mainHandler.postDelayed(openLendTimeoutRunnable, OPEN_LEND_CONNECT_TIMEOUT_MS);
    announce(context.getString(R.string.bd_access_lent_open));
    return true;
  }

  /** Ends an open lend and reconnects TalkBack. */
  private void endOpenLend() {
    if (!openLend) {
      return;
    }
    stopOpenLend();
    reclaim();
  }

  /** Clears the open lend state, without reconnecting. */
  private void stopOpenLend() {
    mainHandler.removeCallbacks(openLendTimeoutRunnable);
    mainHandler.removeCallbacks(openLendForegroundRunnable);
    unregisterAclReceiver();
    openLend = false;
    openLendPackage = null;
    openLendConnected = false;
    chooserDeviceSelected = false;
    chooserPairFocused = false;
  }

  private void onOpenLendTimeout() {
    if (openLend && !openLendConnected) {
      BrailleDisplayLog.d(TAG, "Nothing connected to the lent display; taking it back");
      endOpenLend();
    }
  }

  private void onOpenLendForegroundMaybeChanged() {
    if (!openLend || openLendConnected || isScreenLocked()) {
      return;
    }
    String foreground = foregroundPackage();
    if (foreground != null && !foreground.equals(openLendPackage)) {
      mainHandler.removeCallbacks(openLendForegroundRunnable);
      mainHandler.postDelayed(openLendForegroundRunnable, FOREGROUND_GRACE_MS);
    }
  }

  private void onOpenLendForegroundElapsed() {
    String foreground = foregroundPackage();
    if (openLend
        && !openLendConnected
        && foreground != null
        && !foreground.equals(openLendPackage)) {
      BrailleDisplayLog.d(TAG, "Left " + openLendPackage + " before anything connected");
      endOpenLend();
    }
  }

  /**
   * If the browser's device list is showing, selects the lent display in it and moves focus to the
   * list's pair button, so the user only has to activate it. Chrome's list doesn't expose device
   * names well to screen readers. The display is found by name, or else the first device listed is
   * taken; web apps for the display ask the browser to list only matching devices. Pairing itself
   * is left to the user.
   */
  private void selectInDeviceChooser() {
    AccessibilityService service = accessibilityService;
    if (service == null || openLendPackage == null || chooserPairFocused) {
      return;
    }
    AccessibilityNodeInfo root = service.getRootInActiveWindow();
    if (root == null || !openLendPackage.contentEquals(String.valueOf(root.getPackageName()))) {
      return;
    }
    List<AccessibilityNodeInfo> pairButtons =
        root.findAccessibilityNodeInfosByViewId(openLendPackage + ":id/positive");
    if (pairButtons.isEmpty()) {
      return;
    }
    AccessibilityNodeInfo pairButton = pairButtons.get(0);
    if (!chooserDeviceSelected) {
      AccessibilityNodeInfo item = findDeviceInChooser(root);
      if (item == null) {
        // Still scanning.
        return;
      }
      chooserDeviceSelected = item.performAction(AccessibilityNodeInfo.ACTION_CLICK);
      BrailleDisplayLog.d(TAG, "Selected the display in the device list: " + chooserDeviceSelected);
    }
    pairButton.refresh();
    if (chooserDeviceSelected && pairButton.isEnabled()) {
      chooserPairFocused =
          pairButton.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
      ConnectableDevice device = releasedDevice;
      String name =
          device != null && device.name() != null
              ? device.name()
              : context.getString(R.string.bd_access_display_generic_name);
      announce(context.getString(R.string.bd_access_display_selected, name));
    }
  }

  /**
   * Finds the lent display's entry in a device list: the entry showing its name, or else the first
   * entry of the list. Returns the node to select, or {@code null} if there is none yet.
   */
  @Nullable
  private AccessibilityNodeInfo findDeviceInChooser(AccessibilityNodeInfo root) {
    ConnectableDevice device = releasedDevice;
    String name = device != null ? device.name() : null;
    AccessibilityNodeInfo firstListEntry = null;
    List<AccessibilityNodeInfo> queue = new ArrayList<>();
    queue.add(root);
    for (int i = 0; i < queue.size() && i < MAX_CHOOSER_NODES; i++) {
      AccessibilityNodeInfo node = queue.get(i);
      if (name != null
          && (containsIgnoreCase(node.getText(), name)
              || containsIgnoreCase(node.getContentDescription(), name))) {
        AccessibilityNodeInfo clickable = clickableSelfOrAncestor(node);
        if (clickable != null) {
          return clickable;
        }
      }
      CharSequence className = node.getClassName();
      if (firstListEntry == null
          && className != null
          && (className.toString().endsWith("ListView")
              || className.toString().endsWith("RecyclerView"))
          && node.getChildCount() > 0) {
        AccessibilityNodeInfo entry = node.getChild(0);
        firstListEntry = entry != null ? clickableSelfOrDescendant(entry) : null;
      }
      for (int c = 0; c < node.getChildCount(); c++) {
        AccessibilityNodeInfo child = node.getChild(c);
        if (child != null) {
          queue.add(child);
        }
      }
    }
    return firstListEntry;
  }

  private static boolean containsIgnoreCase(@Nullable CharSequence text, String part) {
    return text != null && text.toString().toLowerCase().contains(part.toLowerCase());
  }

  @Nullable
  private static AccessibilityNodeInfo clickableSelfOrAncestor(AccessibilityNodeInfo node) {
    AccessibilityNodeInfo current = node;
    for (int depth = 0; current != null && depth < 5; depth++) {
      if (current.isClickable()) {
        return current;
      }
      current = current.getParent();
    }
    return null;
  }

  private static AccessibilityNodeInfo clickableSelfOrDescendant(AccessibilityNodeInfo node) {
    if (node.isClickable()) {
      return node;
    }
    for (int c = 0; c < node.getChildCount(); c++) {
      AccessibilityNodeInfo child = node.getChild(c);
      if (child != null && child.isClickable()) {
        return child;
      }
    }
    // A list entry may be selectable without saying it is clickable.
    return node;
  }

  /**
   * Watches the lent display's Bluetooth link: once something connects to it, TalkBack takes it
   * back when that connection ends, such as when the web page is closed.
   */
  @SuppressLint("MissingPermission")
  private void registerAclReceiver() {
    unregisterAclReceiver();
    ConnectableDevice device = releasedDevice;
    if (device == null || device instanceof ConnectableUsbDevice || device.address() == null) {
      // USB, or nothing known: rely on the timeout and on leaving the app.
      return;
    }
    String address = device.address();
    aclReceiver =
        new BroadcastReceiver() {
          @Override
          @SuppressWarnings("deprecation") // getParcelableExtra(String, Class) needs API 33.
          public void onReceive(Context receiverContext, Intent intent) {
            BluetoothDevice changed = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (changed == null || !address.equalsIgnoreCase(changed.getAddress())) {
              return;
            }
            onAclChanged(BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction()));
          }
        };
    IntentFilter filter = new IntentFilter();
    filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
    filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
    ContextCompat.registerReceiver(
        context, aclReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
  }

  private void unregisterAclReceiver() {
    if (aclReceiver != null) {
      context.unregisterReceiver(aclReceiver);
      aclReceiver = null;
    }
  }

  private void onAclChanged(boolean connected) {
    if (!openLend) {
      return;
    }
    if (connected) {
      BrailleDisplayLog.d(TAG, "Something connected to the lent display");
      openLendConnected = true;
      mainHandler.removeCallbacks(openLendTimeoutRunnable);
      mainHandler.removeCallbacks(openLendForegroundRunnable);
    } else if (openLendConnected) {
      // TalkBack's own disconnect, just after lending, comes before anything connects, so only a
      // disconnect after a connection ends the lend.
      BrailleDisplayLog.d(TAG, "The lent display disconnected; taking it back");
      endOpenLend();
    }
  }

  // ---- Foreground tracking ----

  /**
   * Feeds accessibility events in: to lend the display when a marked connect button is activated,
   * and to notice when the holder leaves the foreground.
   */
  public void onAccessibilityEvent(AccessibilityEvent event) {
    int type = event.getEventType();
    if (type == AccessibilityEvent.TYPE_VIEW_CLICKED
        && !displaySuspended
        && isConnectButton(AccessibilityNodeInfoUtils.toCompat(event.getSource()))) {
      BrailleDisplayLog.d(TAG, "Connect button activated; lending the display");
      lendToForegroundApp();
      return;
    }
    if (openLend
        && (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED)) {
      onOpenLendForegroundMaybeChanged();
    }
    if (openLend
        && !openLendConnected
        && (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
      selectInDeviceChooser();
    }
    if (lease == null) {
      return;
    }
    if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
      onForegroundMaybeChanged();
    }
  }

  private void onForegroundMaybeChanged() {
    if (lease == null || state == State.REVOKING) {
      return;
    }
    if (isScreenLocked()) {
      // An app keeps the display while the phone is locked.
      cancelGrace();
      return;
    }
    String foreground = foregroundPackage();
    if (foreground == null) {
      return;
    }
    if (packageBelongsToUid(foreground, lease.uid)) {
      cancelGrace();
    } else if (!graceScheduled) {
      graceScheduled = true;
      mainHandler.postDelayed(foregroundGraceRunnable, FOREGROUND_GRACE_MS);
    }
  }

  private void cancelGrace() {
    graceScheduled = false;
    mainHandler.removeCallbacks(foregroundGraceRunnable);
  }

  private void onForegroundGraceElapsed() {
    graceScheduled = false;
    if (lease == null || isScreenLocked()) {
      return;
    }
    String foreground = foregroundPackage();
    if (foreground != null && !packageBelongsToUid(foreground, lease.uid)) {
      BrailleDisplayLog.d(TAG, foreground + " is in the foreground; revoking");
      revoke();
    }
  }

  /** The package of the focused application window, or null if it can't be determined. */
  @Nullable
  private String foregroundPackage() {
    AccessibilityService service = accessibilityService;
    if (service == null) {
      return null;
    }
    List<AccessibilityWindowInfo> windows = service.getWindows();
    String fallback = null;
    for (AccessibilityWindowInfo window : windows) {
      if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
        continue;
      }
      AccessibilityNodeInfo root = window.getRoot();
      if (root == null || root.getPackageName() == null) {
        continue;
      }
      String packageName = root.getPackageName().toString();
      if (window.isFocused() || window.isActive()) {
        return packageName;
      }
      if (fallback == null) {
        fallback = packageName;
      }
    }
    return fallback;
  }

  private boolean isForegroundUid(int uid) {
    if (accessibilityService == null) {
      // TalkBack isn't running, so there is nothing to protect; allow.
      return true;
    }
    String foreground = foregroundPackage();
    return foreground == null || packageBelongsToUid(foreground, uid);
  }

  private boolean packageBelongsToUid(String packageName, int uid) {
    try {
      ApplicationInfo info = context.getPackageManager().getApplicationInfo(packageName, 0);
      return info.uid == uid;
    } catch (PackageManager.NameNotFoundException e) {
      return false;
    }
  }

  private String packageForUid(int uid) {
    String[] packages = context.getPackageManager().getPackagesForUid(uid);
    return (packages != null && packages.length > 0) ? packages[0] : "uid " + uid;
  }

  private CharSequence appLabel(String packageName) {
    try {
      PackageManager pm = context.getPackageManager();
      return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0));
    } catch (PackageManager.NameNotFoundException e) {
      return packageName;
    }
  }

  private boolean isScreenLocked() {
    KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
    return keyguard != null && keyguard.isKeyguardLocked();
  }

  private void announce(CharSequence text) {
    if (accessibilityService == null) {
      return;
    }
    BrailleCommonTalkBackSpeaker.getInstance().speak(text, AnnounceType.INTERRUPT);
  }
}
