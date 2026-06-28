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

package net.kourlas.voipms_sms.notifications

import net.kourlas.voipms_sms.notifications.workers.UnifiedPushRegistrationWorker
import net.kourlas.voipms_sms.sms.workers.SyncWorker
import net.kourlas.voipms_sms.utils.logException
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Receives UnifiedPush events from the distributor (e.g. ntfy). The DID is used
 * as the UnifiedPush "instance" identifier. Available in every flavor: the
 * F-Droid build uses it exclusively, and the combined primary build uses it when
 * the user selects UnifiedPush as the notification method.
 */
class VoipmsPushService : PushService() {

    /**
     * A push endpoint became available for [instance] (a DID). Register it with
     * the relay and (re)configure the VoIP.ms callback for that DID.
     */
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val keys = endpoint.pubKeySet
        if (keys == null) {
            // Without Web Push keys the relay cannot encrypt; nothing to do.
            logException(
                Exception("UnifiedPush endpoint for $instance has no Web Push keys")
            )
            return
        }
        UnifiedPushRegistrationWorker.registerEndpoint(
            applicationContext,
            did = instance,
            endpoint = endpoint.url,
            p256dh = keys.pubKey,
            auth = keys.auth
        )
    }

    /**
     * A push message arrived for [instance] (a DID). The payload is only a
     * wake-up ping; fetch the actual messages from the VoIP.ms API — exactly
     * what the FCM flavor does in FcmListenerService.
     */
    override fun onMessage(message: PushMessage, instance: String) {
        if (Notifications.getInstance(applicationContext).getNotificationsEnabled()) {
            // 'instance' is the DID this push was registered for, so sync only
            // that DID — far fewer API calls, so the notification is much faster.
            SyncWorker.performPartialSynchronizationForDid(applicationContext, instance)
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        logException(
            Exception("UnifiedPush registration failed for $instance: $reason")
        )
    }

    override fun onUnregistered(instance: String) {
        UnifiedPushRegistrationWorker.unregisterEndpoint(
            applicationContext, did = instance
        )
    }
}
