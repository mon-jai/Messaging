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

package com.android.messaging.receiver;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.provider.Telephony;
import android.provider.Telephony.Sms;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.datamodel.BugleNotifications;
import com.android.messaging.datamodel.MessageNotificationState;
import com.android.messaging.datamodel.action.ReceiveSmsMessageAction;
import com.android.messaging.sms.MmsUtils;
import com.android.messaging.ui.UIIntents;
import com.android.messaging.util.BugleGservices;
import com.android.messaging.util.BugleGservicesKeys;
import com.android.messaging.util.DebugUtils;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.NotificationChannelUtil;
import com.android.messaging.util.OsUtil;
import com.android.messaging.util.PendingIntentConstants;
import com.android.messaging.util.PhoneUtils;

import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationCompat.Builder;
import androidx.core.app.NotificationCompat.Style;
import androidx.core.app.NotificationManagerCompat;

/**
 * Class that receives incoming SMS messages through android.provider.Telephony.SMS_RECEIVED
 *
 * This class processes phone verification SMS messages
 */
public final class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = LogUtil.BUGLE_TAG;

    private static ArrayList<Pattern> sIgnoreSmsPatterns;

    /**
     * Enable or disable the SmsReceiver as appropriate. This receiver is not used when running as the
     * primary user and the SmsDeliverReceiver is used for receiving incoming SMS messages.
     * When running as a secondary user, this receiver is still used to trigger the incoming
     * notification.
     */
    public static void updateSmsReceiveHandler(final Context context) {

        // When we're running as the secondary user, we don't get the new SMS_DELIVER intent,
        // only the primary user receives that. As secondary, we need to go old-school and
        // listen for the SMS_RECEIVED intent. For the secondary user, use this SmsReceiver
        // for both sms and mms notification. For the primary user we don't use the SmsReceiver.
        boolean smsReceiverEnabled = OsUtil.isSecondaryUser();

        final PackageManager packageManager = context.getPackageManager();
        final boolean logv = LogUtil.isLoggable(TAG, LogUtil.VERBOSE);
        if (smsReceiverEnabled) {
            if (logv) {
                LogUtil.v(TAG, "Enabling SMS message receiving");
            }
            packageManager.setComponentEnabledSetting(
                    new ComponentName(context, SmsReceiver.class),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);

        } else {
            if (logv) {
                LogUtil.v(TAG, "Disabling SMS message receiving");
            }
            packageManager.setComponentEnabledSetting(
                    new ComponentName(context, SmsReceiver.class),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
    }

    private static final String EXTRA_ERROR_CODE = "errorCode";
    private static final String EXTRA_SUB_ID = "subscription";

    public static void deliverSmsIntent(final Context context, final Intent intent) {
        final android.telephony.SmsMessage[] messages = getMessagesFromIntent(intent);

        // Check messages for validity
        if (messages == null || messages.length < 1) {
            LogUtil.e(TAG, "processReceivedSms: null or zero or ignored message");
            return;
        }

        final int errorCode =
                intent.getIntExtra(EXTRA_ERROR_CODE, SendStatusReceiver.NO_ERROR_CODE);
        // Always convert negative subIds into -1
        int subId = PhoneUtils.getDefault().getEffectiveIncomingSubIdFromSystem(
                intent, EXTRA_SUB_ID);
        deliverSmsMessages(context, subId, errorCode, messages);
        if (MmsUtils.isDumpSmsEnabled()) {
            final String format = intent.getStringExtra("format");
            DebugUtils.dumpSms(messages[0].getTimestampMillis(), messages, format);
        }
    }

    public static void deliverSmsMessages(final Context context, final int subId,
            final int errorCode, final android.telephony.SmsMessage[] messages) {
        final ContentValues messageValues =
                MmsUtils.parseReceivedSmsMessage(context, messages, errorCode);

        LogUtil.v(TAG, "SmsReceiver.deliverSmsMessages");

        final long nowInMillis =  System.currentTimeMillis();
        final long receivedTimestampMs = MmsUtils.getMessageDate(messages[0], nowInMillis);

        messageValues.put(Sms.Inbox.DATE, receivedTimestampMs);
        // Default to unread and unseen for us but ReceiveSmsMessageAction will override
        // seen for the telephony db.
        messageValues.put(Sms.Inbox.READ, 0);
        messageValues.put(Sms.Inbox.SEEN, 0);
        messageValues.put(Sms.SUBSCRIPTION_ID, subId);

        if (messages[0].getMessageClass() == android.telephony.SmsMessage.MessageClass.CLASS_0 ||
                DebugUtils.debugClassZeroSmsEnabled()) {
            Factory.get().getUIIntents().launchClassZeroActivity(context, messageValues);
        } else {
            final ReceiveSmsMessageAction action = new ReceiveSmsMessageAction(messageValues);
            action.start();
        }
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        LogUtil.v(TAG, "SmsReceiver.onReceive " + intent);
        // We only take delivery of SMS messages in SmsDeliverReceiver.
        if (PhoneUtils.getDefault().isSmsEnabled()) {
            final String action = intent.getAction();
            if (OsUtil.isSecondaryUser() &&
                    (Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(action) ||
                            // TODO: update this with the actual constant from Telephony
                            "android.provider.Telephony.MMS_DOWNLOADED".equals(action))) {
                postNewMessageSecondaryUserNotification();
            }
        }
    }

    private static class SecondaryUserNotificationState extends MessageNotificationState {
        SecondaryUserNotificationState() {
            super(null);
        }
    }

    public static void postNewMessageSecondaryUserNotification() {
        final Context context = Factory.get().getApplicationContext();
        final Resources resources = context.getResources();
        final PendingIntent pendingIntent = UIIntents.get()
                .getPendingIntentForSecondaryUserNewMessageNotification(context);

        final NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context, NotificationChannelUtil.INCOMING_MESSAGES);
        builder.setContentTitle(resources.getString(R.string.secondary_user_new_message_title))
                .setTicker(resources.getString(R.string.secondary_user_new_message_ticker))
                .setSmallIcon(R.drawable.ic_sms_light)
        // Returning PRIORITY_HIGH causes L to put up a HUD notification. Without it, the ticker
        // isn't displayed.
                .setPriority(Notification.PRIORITY_HIGH)
                .setContentIntent(pendingIntent);

        final NotificationCompat.BigTextStyle bigTextStyle =
                new NotificationCompat.BigTextStyle(builder);
        bigTextStyle.bigText(resources.getString(R.string.secondary_user_new_message_title));
        final Notification notification = bigTextStyle.build();

        final NotificationManagerCompat notificationManager =
                NotificationManagerCompat.from(Factory.get().getApplicationContext());

        notificationManager.notify(getNotificationTag(),
                PendingIntentConstants.SMS_SECONDARY_USER_NOTIFICATION_ID, notification);
    }

    /**
     * Cancel the notification
     */
    public static void cancelSecondaryUserNotification() {
        final NotificationManagerCompat notificationManager =
                NotificationManagerCompat.from(Factory.get().getApplicationContext());
        notificationManager.cancel(getNotificationTag(),
                PendingIntentConstants.SMS_SECONDARY_USER_NOTIFICATION_ID);
    }

    private static String getNotificationTag() {
        return Factory.get().getApplicationContext().getPackageName() + ":secondaryuser";
    }

    /**
     * Compile all of the patterns we check for to ignore system SMS messages.
     */
    private static void compileIgnoreSmsPatterns() {
        // Get the pattern set from GServices
        final String smsIgnoreRegex = BugleGservices.get().getString(
                BugleGservicesKeys.SMS_IGNORE_MESSAGE_REGEX,
                BugleGservicesKeys.SMS_IGNORE_MESSAGE_REGEX_DEFAULT);
        if (smsIgnoreRegex != null) {
            final String[] ignoreSmsExpressions = smsIgnoreRegex.split("\n");
            if (ignoreSmsExpressions.length != 0) {
                sIgnoreSmsPatterns = new ArrayList<Pattern>();
                for (int i = 0; i < ignoreSmsExpressions.length; i++) {
                    try {
                        sIgnoreSmsPatterns.add(Pattern.compile(ignoreSmsExpressions[i]));
                    } catch (PatternSyntaxException e) {
                        LogUtil.e(TAG, "compileIgnoreSmsPatterns: Skipping bad expression: " +
                                ignoreSmsExpressions[i]);
                    }
                }
            }
        }
    }

    /**
     * Get the SMS messages from the specified SMS intent.
     * @return the messages. If there is an error or the message should be ignored, return null.
     */
    public static android.telephony.SmsMessage[] getMessagesFromIntent(Intent intent) {
        final android.telephony.SmsMessage[] messages = Sms.Intents.getMessagesFromIntent(intent);

        // Check messages for validity
        if (messages == null || messages.length < 1) {
            return null;
        }
        // Sometimes, SmsMessage.mWrappedSmsMessage is null causing NPE when we access
        // the methods on it although the SmsMessage itself is not null. So do this check
        // before we do anything on the parsed SmsMessages.
        try {
            final String messageBody = messages[0].getDisplayMessageBody();
            if (messageBody != null) {
                // Compile patterns if necessary
                if (sIgnoreSmsPatterns == null) {
                    compileIgnoreSmsPatterns();
                }
                // Check against filters
                for (final Pattern pattern : sIgnoreSmsPatterns) {
                    if (pattern.matcher(messageBody).matches()) {
                        return null;
                    }
                }
            }
        } catch (final NullPointerException e) {
            LogUtil.e(TAG, "shouldIgnoreMessage: NPE inside SmsMessage");
            return null;
        }
        return messages;
    }
}
