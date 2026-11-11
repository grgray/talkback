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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Lends the braille display to a third-party app and takes it back.
 *
 * <p>Works for any display TalkBack drives: it releases and reclaims the display through {@link
 * Connectioneer}'s normal disconnect and connect paths. See {@link BrailleDisplayAccessContract}
 * for the protocol, and {@link BrailleDisplayLeasePolicy} for the rules of when an app may hold the
 * display. This class connects those rules to Android: the apps' messengers and death links,
 * timers, the foreground app, the display's Bluetooth link and speech.
 *
 * <p><b>Open lending</b>, for clients that can't use the access service, such as web apps using
 * Web Bluetooth: {@link #lendToForegroundApp} releases the display so whatever the user connects
 * next can use it. It starts when the user activates a button marked with {@link
 * #CONNECT_BUTTON_ROLE_DESCRIPTION}, or with the lend command. While waiting, TalkBack selects the
 * display in the browser's device list.
 *
 * <p>All state is on the main thread.
 */
public final class BrailleDisplayLeaseManager {
  private static final String TAG = "BrailleDisplayLease";
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
  private final BrailleDisplayLeasePolicy policy;
  private final Map<BrailleDisplayLeasePolicy.Timer, Runnable> timers =
      new EnumMap<>(BrailleDisplayLeasePolicy.Timer.class);
  private final Map<BrailleDisplayLeasePolicy.Client, IBinder.DeathRecipient> deathLinks =
      new HashMap<>();

  @Nullable private AccessibilityService accessibilityService;
  /** The display TalkBack released, to reconnect when the lease ends; may be null. */
  @Nullable private ConnectableDevice releasedDevice;
  @Nullable private BroadcastReceiver aclReceiver;
  /** Whether the display has been selected in the browser's device list during this open lend. */
  private boolean chooserDeviceSelected;
  /** Whether focus has been moved to the device list's pair button during this open lend. */
  private boolean chooserPairFocused;

  private BrailleDisplayLeaseManager(Context context) {
    this.context = context;
    this.policy = new BrailleDisplayLeasePolicy(new AndroidHost());
    for (BrailleDisplayLeasePolicy.Timer timer : BrailleDisplayLeasePolicy.Timer.values()) {
      timers.put(timer, () -> policy.onTimer(timer));
    }
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
    policy.onServiceStopped();
    accessibilityService = null;
  }

  /** True while an app holds, or is being given, the display. */
  public boolean isDisplayLent() {
    return policy.isDisplaySuspended();
  }

  // ---- Requests from apps ----

  void onRequestAccess(int uid, @Nullable Messenger replyTo) {
    if (replyTo == null) {
      return;
    }
    policy.onRequestAccess(new BrailleDisplayLeasePolicy.Client(uid, packageForUid(uid), replyTo));
  }

  /** Tells a foreground app which display TalkBack is using or has lent, without lending it. */
  void onQueryDisplay(int uid, @Nullable Messenger replyTo) {
    if (replyTo == null) {
      return;
    }
    if (!policy.isForeground(uid)) {
      send(
          replyTo,
          packageForUid(uid),
          BrailleDisplayAccessContract.MSG_ACCESS_DENIED,
          BrailleDisplayAccessContract.DENIED_NOT_FOREGROUND,
          null);
      return;
    }
    ConnectableDevice device = null;
    boolean connectedToTalkBack = false;
    if (policy.isDisplaySuspended()) {
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
    send(
        replyTo,
        packageForUid(uid),
        BrailleDisplayAccessContract.MSG_DISPLAY_INFO,
        data != null ? 1 : 0,
        data);
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
    policy.onReleaseAccess(uid);
  }

  /**
   * The user chose to connect a display from TalkBack's settings: tell any app to let go. The
   * caller connects the display itself.
   */
  public void onUserReclaimedDisplay() {
    policy.onUserReclaimedDisplay();
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
    if (!policy.lendOpenly()) {
      return false;
    }
    chooserDeviceSelected = false;
    chooserPairFocused = false;
    return true;
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
    String openLendPackage = policy.getOpenLendPackage();
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
            policy.onDisplayLinkChanged(
                BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction()));
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

  // ---- Foreground tracking ----

  /**
   * Feeds accessibility events in: to lend the display when a marked connect button is activated,
   * to select the display in a browser's device list, and to notice when the app holding the
   * display leaves the foreground.
   */
  public void onAccessibilityEvent(AccessibilityEvent event) {
    int type = event.getEventType();
    if (type == AccessibilityEvent.TYPE_VIEW_CLICKED
        && !policy.isDisplaySuspended()
        && isConnectButton(AccessibilityNodeInfoUtils.toCompat(event.getSource()))) {
      BrailleDisplayLog.d(TAG, "Connect button activated; lending the display");
      lendToForegroundApp();
      return;
    }
    if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
      policy.onWindowsChanged();
    }
    if (policy.isOpenLendWaiting()
        && (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
      selectInDeviceChooser();
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

  private static void send(
      Messenger messenger, String packageName, int what, int arg1, @Nullable Bundle data) {
    Message message = Message.obtain(null, what, arg1, 0);
    if (data != null) {
      message.setData(data);
    }
    try {
      messenger.send(message);
    } catch (RemoteException e) {
      BrailleDisplayLog.w(TAG, "Could not reach " + packageName + ": " + e);
    }
  }

  private static void send(BrailleDisplayLeasePolicy.Client client, int what, int arg1) {
    send(client, what, arg1, null);
  }

  private static void send(
      BrailleDisplayLeasePolicy.Client client, int what, int arg1, @Nullable Bundle data) {
    send((Messenger) client.channel, client.packageName, what, arg1, data);
  }

  /** Carries out {@link BrailleDisplayLeasePolicy}'s decisions on Android. */
  private final class AndroidHost implements BrailleDisplayLeasePolicy.Host {
    @Override
    public void schedule(BrailleDisplayLeasePolicy.Timer timer, long delayMs) {
      Runnable runnable = timers.get(timer);
      mainHandler.removeCallbacks(runnable);
      mainHandler.postDelayed(runnable, delayMs);
    }

    @Override
    public void cancel(BrailleDisplayLeasePolicy.Timer timer) {
      mainHandler.removeCallbacks(timers.get(timer));
    }

    @Override
    public void cancelAllTimers() {
      for (Runnable runnable : timers.values()) {
        mainHandler.removeCallbacks(runnable);
      }
    }

    @Override
    public void link(BrailleDisplayLeasePolicy.Client client) {
      if (deathLinks.containsKey(client)) {
        return;
      }
      IBinder.DeathRecipient recipient =
          () -> mainHandler.post(() -> policy.onClientDied(client));
      deathLinks.put(client, recipient);
      try {
        ((Messenger) client.channel).getBinder().linkToDeath(recipient, 0);
      } catch (RemoteException e) {
        // Already dead; binderDied will not be called, so handle it now.
        mainHandler.post(() -> policy.onClientDied(client));
      }
    }

    @Override
    public void unlink(BrailleDisplayLeasePolicy.Client client) {
      IBinder.DeathRecipient recipient = deathLinks.remove(client);
      if (recipient != null) {
        ((Messenger) client.channel).getBinder().unlinkToDeath(recipient, 0);
      }
    }

    @Override
    public void sendGranted(BrailleDisplayLeasePolicy.Client client, boolean displayReleased) {
      send(
          client,
          BrailleDisplayAccessContract.MSG_ACCESS_GRANTED,
          displayReleased
              ? BrailleDisplayAccessContract.GRANTED_DISPLAY_RELEASED
              : BrailleDisplayAccessContract.GRANTED_NO_DISPLAY,
          describeForApp(releasedDevice));
    }

    @Override
    public void sendRevoked(BrailleDisplayLeasePolicy.Client client) {
      send(client, BrailleDisplayAccessContract.MSG_ACCESS_REVOKED, 0);
    }

    @Override
    public void sendDenied(BrailleDisplayLeasePolicy.Client client, int reason) {
      send(client, BrailleDisplayAccessContract.MSG_ACCESS_DENIED, reason);
    }

    @Override
    public boolean isServiceRunning() {
      return accessibilityService != null;
    }

    @Override
    public boolean hasDisplay() {
      return Connectioneer.getInstance(context).getConnectingOrConnectedDevice().isPresent();
    }

    @Override
    public boolean releaseDisplay() {
      releasedDevice = Connectioneer.getInstance(context).releaseDisplayForExternalApp().orElse(null);
      return releasedDevice != null;
    }

    @Override
    public void reclaimDisplay() {
      ConnectableDevice device = releasedDevice;
      releasedDevice = null;
      Connectioneer.getInstance(context).reclaimDisplayFromExternalApp(device);
    }

    @Override
    public void forgetReleasedDisplay() {
      releasedDevice = null;
    }

    @Override
    @Nullable
    public String foregroundPackage() {
      return BrailleDisplayLeaseManager.this.foregroundPackage();
    }

    @Override
    public boolean packageBelongsToUid(String packageName, int uid) {
      return BrailleDisplayLeaseManager.this.packageBelongsToUid(packageName, uid);
    }

    @Override
    public boolean isScreenLocked() {
      return BrailleDisplayLeaseManager.this.isScreenLocked();
    }

    @Override
    public void startWatchingDisplayLink() {
      registerAclReceiver();
    }

    @Override
    public void stopWatchingDisplayLink() {
      unregisterAclReceiver();
    }

    @Override
    public void announceLent(String packageName) {
      announce(context.getString(R.string.bd_access_lent, appLabel(packageName)));
    }

    @Override
    public void announceLentOpenly() {
      announce(context.getString(R.string.bd_access_lent_open));
    }

    @Override
    public void announceReturned() {
      announce(context.getString(R.string.bd_access_returned));
    }

    @Override
    public void log(String message) {
      BrailleDisplayLog.d(TAG, message);
    }
  }
}
