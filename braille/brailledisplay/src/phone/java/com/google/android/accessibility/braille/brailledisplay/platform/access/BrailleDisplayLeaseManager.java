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
import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.annotation.Nullable;
import com.google.android.accessibility.braille.brailledisplay.BrailleDisplayLog;
import com.google.android.accessibility.braille.brailledisplay.R;
import com.google.android.accessibility.braille.brailledisplay.platform.Connectioneer;
import com.google.android.accessibility.braille.brailledisplay.platform.connect.device.ConnectableDevice;
import com.google.android.accessibility.braille.brailledisplay.platform.connect.device.ConnectableUsbDevice;
import com.google.android.accessibility.braille.common.BrailleCommonTalkBackSpeaker;
import com.google.android.accessibility.braille.common.TalkBackSpeaker.AnnounceType;
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
            BrailleDisplayAccessContract.MSG_ACCESS_GRANTED, grantedArg(), describe(releasedDevice));
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
    Bundle data = describe(device);
    if (data != null) {
      data.putBoolean(BrailleDisplayAccessContract.KEY_CONNECTED_TO_TALKBACK, connectedToTalkBack);
    }
    asker.send(BrailleDisplayAccessContract.MSG_DISPLAY_INFO, data != null ? 1 : 0, data);
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
        BrailleDisplayAccessContract.MSG_ACCESS_GRANTED, grantedArg(), describe(releasedDevice));
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

  // ---- Foreground tracking ----

  /** Feeds accessibility events in to notice when the holder leaves the foreground. */
  public void onAccessibilityEvent(AccessibilityEvent event) {
    if (lease == null) {
      return;
    }
    int type = event.getEventType();
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
