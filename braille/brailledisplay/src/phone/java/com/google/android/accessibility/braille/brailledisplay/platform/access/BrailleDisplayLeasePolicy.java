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

import androidx.annotation.Nullable;

/**
 * The rules for lending the braille display to other apps and taking it back, with no Android
 * dependencies so they can be unit tested. {@link BrailleDisplayLeaseManager} feeds events in and
 * carries out what these rules decide through a {@link Host}.
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
 *   <li>An app that asks while another is giving the display back is handed it directly, without
 *       TalkBack reconnecting in between.
 *   <li>If the user connects a display from TalkBack's settings, TalkBack reclaims it.
 * </ul>
 *
 * <p><b>Open lending</b>, for clients that can't use the access service, such as web apps using
 * Web Bluetooth: {@link #lendOpenly} releases the display so whatever the user connects next can
 * use it. With no client to ask, TalkBack takes the display back when the display's Bluetooth
 * connection ends after something connected, if nothing connects within {@link
 * #OPEN_LEND_CONNECT_TIMEOUT_MS}, or if the user leaves the app before anything connects. An app
 * asking through the access service takes over an open lend.
 *
 * <p>Not thread safe: call everything on one thread (the main thread).
 */
final class BrailleDisplayLeasePolicy {
  /** How long another app must stay in the foreground before access is revoked. */
  static final long FOREGROUND_GRACE_MS = 1_000L;
  /** How long a revoked app has to disconnect before TalkBack reconnects anyway. */
  static final long RELEASE_TIMEOUT_MS = 2_000L;
  /** Time for TalkBack's link to the display to drop before the app is told to connect. */
  static final long LINK_RELEASE_DELAY_MS = 500L;
  /** How long an open lend waits for something to connect before TalkBack takes the display back. */
  static final long OPEN_LEND_CONNECT_TIMEOUT_MS = 90_000L;

  /** Timers the rules start; the host calls {@link #onTimer} when one goes off. */
  enum Timer {
    /** TalkBack's link to the display has had time to drop: tell the app it may connect. */
    FINISH_GRANT,
    /** Another app has been in the foreground for {@link #FOREGROUND_GRACE_MS}. */
    FOREGROUND_GRACE,
    /** A revoked app has had {@link #RELEASE_TIMEOUT_MS} to let go. */
    RELEASE_TIMEOUT,
    /** Nothing connected to an open lend in {@link #OPEN_LEND_CONNECT_TIMEOUT_MS}. */
    OPEN_LEND_TIMEOUT,
    /** Another app has been in front of an open lend for {@link #FOREGROUND_GRACE_MS}. */
    OPEN_LEND_FOREGROUND,
  }

  /** Lease states. */
  enum State {
    /** TalkBack owns the display (or has none), or it is lent openly. */
    IDLE,
    /** TalkBack is letting go of the display for an app. */
    GRANTING,
    /** An app holds the display. */
    GRANTED,
    /** TalkBack asked the app to give the display back. */
    REVOKING,
  }

  /** An app that asked for, or holds, the display. */
  static final class Client {
    final int uid;
    final String packageName;
    /** The host's way of reaching the app, such as its {@code Messenger}. */
    final Object channel;

    Client(int uid, String packageName, Object channel) {
      this.uid = uid;
      this.packageName = packageName;
      this.channel = channel;
    }

    @Override
    public String toString() {
      return packageName;
    }
  }

  /** What the rules need from the system, and the actions they take. */
  interface Host {
    /** Starts {@code timer}, replacing it if it is already running. */
    void schedule(Timer timer, long delayMs);

    void cancel(Timer timer);

    void cancelAllTimers();

    /** Starts watching for {@code client}'s process dying; it then calls {@link #onClientDied}. */
    void link(Client client);

    void unlink(Client client);

    /** Tells {@code client} it may connect, with the details of the display it can use. */
    void sendGranted(Client client, boolean displayReleased);

    /** Tells {@code client} to give the display back. */
    void sendRevoked(Client client);

    /** Tells {@code client} it can't have the display, with a reason from the access contract. */
    void sendDenied(Client client, int reason);

    /** Returns whether TalkBack's accessibility service is running. */
    boolean isServiceRunning();

    /** Returns whether TalkBack is connected or connecting to a display. */
    boolean hasDisplay();

    /**
     * Disconnects TalkBack from its display and stops it reconnecting, remembering the display.
     * Returns whether there was a display to release.
     */
    boolean releaseDisplay();

    /** Reconnects TalkBack to the display it released. */
    void reclaimDisplay();

    /** Forgets the released display without reconnecting; the user is connecting one themselves. */
    void forgetReleasedDisplay();

    /** Returns the package of the foreground app, or {@code null} if it can't be told. */
    @Nullable
    String foregroundPackage();

    boolean packageBelongsToUid(String packageName, int uid);

    boolean isScreenLocked();

    /** Starts watching the released display's link; changes call {@link #onDisplayLinkChanged}. */
    void startWatchingDisplayLink();

    void stopWatchingDisplayLink();

    void announceLent(String packageName);

    void announceLentOpenly();

    void announceReturned();

    void log(String message);
  }

  private final Host host;
  private State state = State.IDLE;
  @Nullable private Client lease;
  /** An app that asked for the display while another app was still giving it back. */
  @Nullable private Client pending;
  /** True while TalkBack has given up the display, from the first grant until it reclaims it. */
  private boolean displaySuspended;
  /** Whether TalkBack had a display to release when it gave it up. */
  private boolean displayReleased;
  private boolean graceScheduled;

  /** True while the display is lent openly, to whatever connects next. */
  private boolean openLend;
  /** The app in the foreground when the display was lent openly, such as the browser. */
  @Nullable private String openLendPackage;
  /** True once something has connected to the display during an open lend. */
  private boolean openLendConnected;

  BrailleDisplayLeasePolicy(Host host) {
    this.host = host;
  }

  // ---- Queries ----

  /** True while an app holds, or is being given, the display, or it is lent openly. */
  boolean isDisplaySuspended() {
    return displaySuspended;
  }

  State getState() {
    return state;
  }

  @Nullable
  Client getLease() {
    return lease;
  }

  @Nullable
  Client getPending() {
    return pending;
  }

  boolean isOpenLend() {
    return openLend;
  }

  /** True while the display is lent openly and nothing has connected to it yet. */
  boolean isOpenLendWaiting() {
    return openLend && !openLendConnected;
  }

  /** The app the display was lent openly to, or {@code null}. */
  @Nullable
  String getOpenLendPackage() {
    return openLendPackage;
  }

  /** Returns whether {@code uid} is the foreground app, or TalkBack can't tell. */
  boolean isForeground(int uid) {
    if (!host.isServiceRunning()) {
      // TalkBack isn't running, so there is nothing to protect; allow.
      return true;
    }
    String foreground = host.foregroundPackage();
    return foreground == null || host.packageBelongsToUid(foreground, uid);
  }

  // ---- Events ----

  /** TalkBack's accessibility service stopped. Any app keeps the display. */
  void onServiceStopped() {
    stopOpenLend();
    if (lease != null) {
      host.unlink(lease);
      lease = null;
    }
    if (pending != null) {
      host.unlink(pending);
      pending = null;
    }
    host.cancelAllTimers();
    graceScheduled = false;
    state = State.IDLE;
    displaySuspended = false;
    displayReleased = false;
    host.forgetReleasedDisplay();
  }

  /** An app asks to borrow the display. */
  void onRequestAccess(Client request) {
    if (!isForeground(request.uid)) {
      host.log(request + " asked for the display but is not in the foreground");
      host.sendDenied(request, BrailleDisplayAccessContract.DENIED_NOT_FOREGROUND);
      return;
    }
    host.log(request + " requests the display; state " + state);
    if (lease != null && lease.uid == request.uid && state != State.REVOKING) {
      // Repeated request from the current holder.
      if (state == State.GRANTED) {
        host.sendGranted(request, displayReleased);
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
      host.unlink(pending);
    }
    pending = request;
    host.link(request);
    revoke();
  }

  /** An app has finished with the display. */
  void onReleaseAccess(int uid) {
    if (pending != null && pending.uid == uid) {
      host.unlink(pending);
      pending = null;
    }
    if (lease == null || lease.uid != uid) {
      return;
    }
    host.log(lease + " released the display");
    endLease();
  }

  /** An app's process died. */
  void onClientDied(Client died) {
    if (pending == died) {
      pending = null;
    }
    if (lease == died) {
      host.log(died + " died while holding the display");
      endLease();
    }
  }

  /**
   * The user chose to connect a display from TalkBack's settings: tell any app to let go. The
   * caller connects the display itself.
   */
  void onUserReclaimedDisplay() {
    if (!displaySuspended) {
      return;
    }
    host.log("User reclaimed the display");
    stopOpenLend();
    host.cancelAllTimers();
    graceScheduled = false;
    if (lease != null) {
      host.sendRevoked(lease);
      host.unlink(lease);
      lease = null;
    }
    if (pending != null) {
      host.unlink(pending);
      pending = null;
    }
    state = State.IDLE;
    displaySuspended = false;
    displayReleased = false;
    host.forgetReleasedDisplay();
    host.announceReturned();
  }

  /**
   * Lends the display to whatever the user connects next. Returns {@code false} if there is no
   * display to lend, or it is already lent.
   */
  boolean lendOpenly() {
    if (displaySuspended || lease != null || !host.isServiceRunning() || !host.hasDisplay()) {
      return false;
    }
    displaySuspended = true;
    displayReleased = host.releaseDisplay();
    openLend = true;
    openLendPackage = host.foregroundPackage();
    openLendConnected = false;
    host.log("Display lent openly while " + openLendPackage + " is in front");
    host.startWatchingDisplayLink();
    host.schedule(Timer.OPEN_LEND_TIMEOUT, OPEN_LEND_CONNECT_TIMEOUT_MS);
    host.announceLentOpenly();
    return true;
  }

  /** Something connected to, or disconnected from, the openly lent display. */
  void onDisplayLinkChanged(boolean connected) {
    if (!openLend) {
      return;
    }
    if (connected) {
      host.log("Something connected to the lent display");
      openLendConnected = true;
      host.cancel(Timer.OPEN_LEND_TIMEOUT);
      host.cancel(Timer.OPEN_LEND_FOREGROUND);
    } else if (openLendConnected) {
      // TalkBack's own disconnect, just after lending, comes before anything connects, so only a
      // disconnect after a connection ends the lend.
      host.log("The lent display disconnected; taking it back");
      endOpenLend();
    }
  }

  /** The foreground window may have changed. */
  void onWindowsChanged() {
    if (openLend) {
      checkOpenLendForeground();
    }
    if (lease != null) {
      checkLeaseForeground();
    }
  }

  void onTimer(Timer timer) {
    switch (timer) {
      case FINISH_GRANT:
        finishGrant();
        break;
      case FOREGROUND_GRACE:
        onForegroundGraceElapsed();
        break;
      case RELEASE_TIMEOUT:
        if (lease != null) {
          host.log(lease + " did not release the display in time");
        }
        endLease();
        break;
      case OPEN_LEND_TIMEOUT:
        if (openLend && !openLendConnected) {
          host.log("Nothing connected to the lent display; taking it back");
          endOpenLend();
        }
        break;
      case OPEN_LEND_FOREGROUND:
        onOpenLendForegroundElapsed();
        break;
    }
  }

  // ---- State transitions ----

  private void grant(Client request) {
    // An app asking through the access service takes over an open lend; the display is already
    // released.
    stopOpenLend();
    lease = request;
    if (pending == request) {
      pending = null;
    } else {
      host.link(request);
    }
    state = State.GRANTING;
    cancelGrace();
    long delay = 0;
    // Without TalkBack's service running there is no TalkBack connection to release.
    if (!displaySuspended && host.isServiceRunning()) {
      displaySuspended = true;
      displayReleased = host.releaseDisplay();
      if (displayReleased) {
        delay = LINK_RELEASE_DELAY_MS;
      }
    }
    host.announceLent(request.packageName);
    host.schedule(Timer.FINISH_GRANT, delay);
  }

  private void finishGrant() {
    if (lease == null || state != State.GRANTING) {
      return;
    }
    state = State.GRANTED;
    host.log("Display lent to " + lease);
    host.sendGranted(lease, displayReleased);
    // The app may have left the foreground while TalkBack was letting go.
    checkLeaseForeground();
  }

  /** Asks the holder to give the display back, and reclaims it after a timeout. */
  private void revoke() {
    if (lease == null || state == State.REVOKING) {
      return;
    }
    cancelGrace();
    host.cancel(Timer.FINISH_GRANT);
    boolean wasGranted = state == State.GRANTED;
    state = State.REVOKING;
    host.log("Revoking the display from " + lease);
    if (wasGranted) {
      host.sendRevoked(lease);
      host.schedule(Timer.RELEASE_TIMEOUT, RELEASE_TIMEOUT_MS);
    } else {
      // Never told it was granted, so it has not connected: end at once.
      endLease();
    }
  }

  /** The current lease is over: hand the display to a waiting app, or back to TalkBack. */
  private void endLease() {
    host.cancel(Timer.FINISH_GRANT);
    cancelGrace();
    host.cancel(Timer.RELEASE_TIMEOUT);
    if (lease != null) {
      host.unlink(lease);
      lease = null;
    }
    state = State.IDLE;
    Client next = pending;
    if (next != null && isForeground(next.uid)) {
      // Pass the display straight to the waiting app without reconnecting TalkBack in between.
      grant(next);
      return;
    }
    if (next != null) {
      host.sendDenied(next, BrailleDisplayAccessContract.DENIED_NOT_FOREGROUND);
      host.unlink(next);
      pending = null;
    }
    reclaim();
  }

  private void reclaim() {
    if (!displaySuspended) {
      return;
    }
    displaySuspended = false;
    displayReleased = false;
    host.log("Reclaiming the display for TalkBack");
    host.announceReturned();
    host.reclaimDisplay();
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
    if (!openLend) {
      return;
    }
    host.cancel(Timer.OPEN_LEND_TIMEOUT);
    host.cancel(Timer.OPEN_LEND_FOREGROUND);
    host.stopWatchingDisplayLink();
    openLend = false;
    openLendPackage = null;
    openLendConnected = false;
  }

  // ---- Foreground tracking ----

  private void checkOpenLendForeground() {
    if (!openLend || openLendConnected || host.isScreenLocked()) {
      return;
    }
    String foreground = host.foregroundPackage();
    if (foreground != null && !foreground.equals(openLendPackage)) {
      host.schedule(Timer.OPEN_LEND_FOREGROUND, FOREGROUND_GRACE_MS);
    }
  }

  private void onOpenLendForegroundElapsed() {
    String foreground = host.foregroundPackage();
    if (openLend
        && !openLendConnected
        && foreground != null
        && !foreground.equals(openLendPackage)) {
      host.log("Left " + openLendPackage + " before anything connected");
      endOpenLend();
    }
  }

  private void checkLeaseForeground() {
    if (lease == null || state == State.REVOKING) {
      return;
    }
    if (host.isScreenLocked()) {
      // An app keeps the display while the phone is locked.
      cancelGrace();
      return;
    }
    String foreground = host.foregroundPackage();
    if (foreground == null) {
      return;
    }
    if (host.packageBelongsToUid(foreground, lease.uid)) {
      cancelGrace();
    } else if (!graceScheduled) {
      graceScheduled = true;
      host.schedule(Timer.FOREGROUND_GRACE, FOREGROUND_GRACE_MS);
    }
  }

  private void cancelGrace() {
    graceScheduled = false;
    host.cancel(Timer.FOREGROUND_GRACE);
  }

  private void onForegroundGraceElapsed() {
    graceScheduled = false;
    if (lease == null || host.isScreenLocked()) {
      return;
    }
    String foreground = host.foregroundPackage();
    if (foreground != null && !host.packageBelongsToUid(foreground, lease.uid)) {
      host.log(foreground + " is in the foreground; revoking");
      revoke();
    }
  }
}
