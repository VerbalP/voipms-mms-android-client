/*
 * VoIP.ms SMS
 * Copyright (C) 2017-2021 Michael Kourlas
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

package net.kourlas.voipms_sms.utils

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import net.kourlas.voipms_sms.BuildConfig
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.notifications.Notifications
import net.kourlas.voipms_sms.notifications.workers.NotificationsRegistrationWorker
import net.kourlas.voipms_sms.preferences.NOTIFICATION_METHOD_UNIFIEDPUSH
import net.kourlas.voipms_sms.preferences.didsConfigured
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.preferences.getNotificationMethod
import net.kourlas.voipms_sms.preferences.setSetupCompletedForVersion

/**
 * Retrieves the Firebase installation ID.
 */
suspend fun getInstallationId(): String {
    return try {
        FirebaseInstallations.getInstance().id.await()
    } catch (e: Exception) {
        "Not available"
    }
}

/**
 * Subscribes to FCM topics corresponding to the currently configured DIDs.
 *
 * Combined build: a no-op unless the notification method is FCM and Google Play
 * Services is available.
 */
fun subscribeToDidTopics(context: Context) {
    // Skip when the user has chosen UnifiedPush (no FCM topics in that mode).
    if (getNotificationMethod(context) == NOTIFICATION_METHOD_UNIFIEDPUSH) {
        return
    }

    // Do not subscribe to DID topics if Google Play Services is unavailable
    if (GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(
                context
            ) != ConnectionResult.SUCCESS
    ) {
        return
    }

    // Subscribe to topics for current DIDs
    for (did in getDids(context, onlyShowNotifications = true)) {
        FirebaseMessaging.getInstance().subscribeToTopic("did-$did")
    }
}

/**
 * Attempt to enable push notifications, dispatching on the notification-method
 * preference. In FCM mode, may show an error using a snackbar on the specified
 * activity if Google Play Services is unavailable.
 *
 * Switching method is authoritative on the VoIP.ms side: each method rewrites
 * the SMS URL callback for every notification DID (FCM -> /fcm, UnifiedPush ->
 * /callback), so any registration left over from the other method is inert and
 * needs no explicit teardown.
 */
fun enablePushNotifications(
    context: Context,
    activityToShowError: FragmentActivity? = null
) {
    // UnifiedPush mode: delegate to the shared implementation.
    if (getNotificationMethod(context) == NOTIFICATION_METHOD_UNIFIEDPUSH) {
        enableUnifiedPushNotifications(context, activityToShowError)
        return
    }

    // FCM mode.

    // Check if Google Play Services is available
    if (GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(
                context
            ) != ConnectionResult.SUCCESS
    ) {
        if (activityToShowError != null) {
            showSnackbar(
                activityToShowError, R.id.coordinator_layout,
                activityToShowError.getString(
                    R.string.push_notifications_fail_google_play
                )
            )
        }
        setSetupCompletedForVersion(context, BuildConfig.VERSION_CODE.toLong())
        return
    }

    // Check if DIDs are configured and that notifications are enabled,
    // and silently quit if not
    if (!didsConfigured(context)
        || !Notifications.getInstance(context).getNotificationsEnabled()
    ) {
        setSetupCompletedForVersion(context, BuildConfig.VERSION_CODE.toLong())
        return
    }

    // Subscribe to DID topics
    subscribeToDidTopics(context)

    // Start push notifications registration service
    NotificationsRegistrationWorker.registerForPushNotifications(context)
}
