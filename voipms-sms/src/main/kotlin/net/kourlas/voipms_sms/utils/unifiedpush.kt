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
import net.kourlas.voipms_sms.BuildConfig
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.notifications.Notifications
import net.kourlas.voipms_sms.notifications.workers.UnifiedPushRegistrationWorker
import net.kourlas.voipms_sms.preferences.didsConfigured
import net.kourlas.voipms_sms.preferences.getUnifiedPushRelayUrl
import net.kourlas.voipms_sms.preferences.setSetupCompletedForVersion
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Enables push notifications via UnifiedPush. Shared by the F-Droid flavor and
 * by the combined primary build when the user selects UnifiedPush as the
 * notification method.
 *
 * Requires the relay URL to be configured (Settings -> Synchronization). Selects
 * the current/default distributor (e.g. ntfy), then hands off to
 * [UnifiedPushRegistrationWorker] which fetches the relay's VAPID key and
 * registers one UnifiedPush instance per DID. Endpoints arrive asynchronously in
 * [net.kourlas.voipms_sms.notifications.VoipmsPushService.onNewEndpoint].
 */
fun enableUnifiedPushNotifications(
    context: Context,
    activityToShowError: FragmentActivity? = null
) {
    // Quit quietly if DIDs are not configured or notifications are disabled.
    if (!didsConfigured(context)
        || !Notifications.getInstance(context).getNotificationsEnabled()
    ) {
        setSetupCompletedForVersion(context, BuildConfig.VERSION_CODE.toLong())
        return
    }

    // The relay URL is a user setting; nothing relay-specific is baked in.
    if (getUnifiedPushRelayUrl(context).isBlank()) {
        if (activityToShowError != null) {
            showSnackbar(
                activityToShowError, R.id.coordinator_layout,
                activityToShowError.getString(
                    R.string.push_notifications_fail_no_relay
                )
            )
        }
        return
    }

    val appContext = context.applicationContext
    // Distributor selection (and the AND_3 link flow) needs an Activity the
    // first time; afterwards the acked distributor is reused from any context.
    val distributorContext: Context = activityToShowError ?: context
    UnifiedPush.tryUseCurrentOrDefaultDistributor(distributorContext) { success ->
        if (success) {
            UnifiedPushRegistrationWorker.setup(appContext)
        } else if (activityToShowError != null) {
            showSnackbar(
                activityToShowError, R.id.coordinator_layout,
                activityToShowError.getString(
                    R.string.push_notifications_fail_no_distributor
                )
            )
        }
    }
}
