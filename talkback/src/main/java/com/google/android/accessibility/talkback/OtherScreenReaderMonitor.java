/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback;

import static com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityManager;
import androidx.annotation.Nullable;
import com.google.android.accessibility.utils.output.SpeechController;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.List;

/**
 * Keeps this TalkBack quiet while another screen reader is switched on.
 *
 * <p>A user who relies on a screen reader cannot turn it off to switch to this one, so both run
 * for a moment. While another screen reader is enabled this one mutes its speech and says once
 * how to turn the other one off. When the other one goes, speech comes back.
 */
public class OtherScreenReaderMonitor {
  private static final String TAG = "OtherScreenReaderMonitor";

  private final Context context;
  private final SpeechController speechController;
  private final ContentResolver contentResolver;
  private final ContentObserver observer;

  /** True while another screen reader is enabled. */
  private boolean otherOn;
  /** The other screen reader's name, for the announcement when it goes. */
  private CharSequence otherName = "";
  /** True while this class has muted the speech, so a user's own mute is left alone. */
  private boolean mutedByMonitor;

  public OtherScreenReaderMonitor(Context context, SpeechController speechController) {
    this.context = context;
    this.speechController = speechController;
    contentResolver = context.getContentResolver();
    observer =
        new ContentObserver(new Handler(Looper.getMainLooper())) {
          @Override
          public void onChange(boolean selfChange, @Nullable Uri uri) {
            update();
          }
        };
  }

  /** Starts watching; call once speech is ready, before TalkBack's "on" announcement. */
  public void start() {
    contentResolver.registerContentObserver(
        Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
        /* notifyForDescendants= */ false,
        observer);
    update();
  }

  public void stop() {
    contentResolver.unregisterContentObserver(observer);
    if (mutedByMonitor) {
      speechController.setMute(false);
    }
    otherOn = false;
    mutedByMonitor = false;
  }

  private void update() {
    CharSequence other = findOtherScreenReader();
    if (other != null && !otherOn) {
      otherOn = true;
      otherName = other;
      LogUtils.i(TAG, "Another screen reader is on (%s); muting speech.", other);
      boolean wasMuted = speechController.isMute();
      speechController.setMute(false);
      speechController.speak(
          context.getString(
              R.string.other_screen_reader_on, context.getString(R.string.talkback_title), other),
          SpeechController.QUEUE_MODE_FLUSH_ALL,
          /* flags= */ 0,
          /* speechParams= */ null,
          EVENT_ID_UNTRACKED);
      speechController.setMute(true);
      mutedByMonitor = !wasMuted;
    } else if (other == null && otherOn) {
      LogUtils.i(TAG, "No other screen reader is on; speaking again.");
      otherOn = false;
      if (mutedByMonitor) {
        speechController.setMute(false);
        mutedByMonitor = false;
      }
      speechController.speak(
          context.getString(
              R.string.other_screen_reader_off,
              otherName,
              context.getString(R.string.talkback_title)),
          SpeechController.QUEUE_MODE_FLUSH_ALL,
          /* flags= */ 0,
          /* speechParams= */ null,
          EVENT_ID_UNTRACKED);
    }
  }

  /** Returns the name of another enabled screen reader, or null if there is none. */
  @Nullable
  private CharSequence findOtherScreenReader() {
    String enabled =
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
    if (TextUtils.isEmpty(enabled)) {
      return null;
    }
    AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
    List<AccessibilityServiceInfo> installed = manager.getInstalledAccessibilityServiceList();
    for (String flattened : enabled.split(":")) {
      ComponentName component = ComponentName.unflattenFromString(flattened);
      if (component == null || component.getPackageName().equals(context.getPackageName())) {
        continue;
      }
      for (AccessibilityServiceInfo info : installed) {
        ServiceInfo service = info.getResolveInfo().serviceInfo;
        if (component.equals(new ComponentName(service.packageName, service.name))
            && isScreenReader(info)) {
          return info.getResolveInfo().loadLabel(context.getPackageManager());
        }
      }
    }
    return null;
  }

  /** A screen reader speaks and drives touch exploration. */
  private static boolean isScreenReader(AccessibilityServiceInfo info) {
    return (info.feedbackType & AccessibilityServiceInfo.FEEDBACK_SPOKEN) != 0
        && (info.getCapabilities()
                & AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION)
            != 0;
  }
}
