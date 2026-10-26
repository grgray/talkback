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

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import androidx.annotation.Nullable;

/**
 * Bound service through which apps borrow the braille display from TalkBack. Binding requires
 * {@link BrailleDisplayAccessContract#PERMISSION_BRAILLE_DISPLAY_ACCESS}; messages are handed to
 * {@link BrailleDisplayLeaseManager} on the main thread, identified by the sender's uid.
 */
public class BrailleDisplayAccessService extends Service {

  private final Messenger messenger =
      new Messenger(
          new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message message) {
              BrailleDisplayLeaseManager leaseManager =
                  BrailleDisplayLeaseManager.getInstance(getApplicationContext());
              switch (message.what) {
                case BrailleDisplayAccessContract.MSG_REQUEST_ACCESS:
                  leaseManager.onRequestAccess(message.sendingUid, message.replyTo);
                  break;
                case BrailleDisplayAccessContract.MSG_RELEASE_ACCESS:
                  leaseManager.onReleaseAccess(message.sendingUid);
                  break;
                case BrailleDisplayAccessContract.MSG_QUERY_DISPLAY:
                  leaseManager.onQueryDisplay(message.sendingUid, message.replyTo);
                  break;
                default:
                  super.handleMessage(message);
              }
            }
          });

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return BrailleDisplayAccessContract.ACTION_BRAILLE_DISPLAY_ACCESS.equals(intent.getAction())
        ? messenger.getBinder()
        : null;
  }
}
