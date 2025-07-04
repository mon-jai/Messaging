/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.messaging.datamodel.action;

import android.os.Parcel;
import android.os.Parcelable;

import com.android.messaging.datamodel.BugleNotifications;

/**
 * Updates the message notification (generally, to include voice replies we've
 * made since the notification was first posted).
 */
public class UpdateMessageNotificationAction extends Action {

    public static void updateMessageNotification() {
        new UpdateMessageNotificationAction().start();
    }

    private UpdateMessageNotificationAction() {
    }

    @Override
    protected Object executeAction() {
        BugleNotifications.update(BugleNotifications.UPDATE_MESSAGES);
        return null;
    }

    private UpdateMessageNotificationAction(final Parcel in) {
        super(in);
    }

    public static final Parcelable.Creator<UpdateMessageNotificationAction> CREATOR
            = new Parcelable.Creator<UpdateMessageNotificationAction>() {
        @Override
        public UpdateMessageNotificationAction createFromParcel(final Parcel in) {
            return new UpdateMessageNotificationAction(in);
        }

        @Override
        public UpdateMessageNotificationAction[] newArray(final int size) {
            return new UpdateMessageNotificationAction[size];
        }
    };

    @Override
    public void writeToParcel(final Parcel parcel, final int flags) {
        writeActionToParcel(parcel, flags);
    }
}
