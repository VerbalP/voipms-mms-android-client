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

/**
 * F-Droid flavor: push is UnifiedPush only. These are the flavor entry points
 * the shared code calls; the real work lives in the shared
 * [enableUnifiedPushNotifications].
 */

/**
 * Not applicable to the F-Droid flavor (UnifiedPush has no equivalent of a
 * Firebase installation ID).
 */
fun getInstallationId(): String {
    return "Not supported"
}

/**
 * No-op for the F-Droid flavor. With UnifiedPush there are no FCM-style topics;
 * each DID is registered as its own UnifiedPush instance instead.
 */
@Suppress("UNUSED_PARAMETER")
fun subscribeToDidTopics(context: Context) {
    // Do nothing.
}

/**
 * Enables push notifications for the F-Droid flavor via UnifiedPush.
 */
fun enablePushNotifications(
    context: Context,
    activityToShowError: FragmentActivity? = null
) {
    enableUnifiedPushNotifications(context, activityToShowError)
}
