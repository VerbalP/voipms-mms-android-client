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

package net.kourlas.voipms_sms.notifications.workers

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.notifications.Notifications
import net.kourlas.voipms_sms.preferences.accountConfigured
import net.kourlas.voipms_sms.preferences.getConnectTimeout
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.preferences.getEmail
import net.kourlas.voipms_sms.preferences.getPassword
import net.kourlas.voipms_sms.preferences.getReadTimeout
import net.kourlas.voipms_sms.preferences.getUnifiedPushRegistrationSecret
import net.kourlas.voipms_sms.preferences.getUnifiedPushRelayUrl
import net.kourlas.voipms_sms.preferences.getUnifiedPushVapidKey
import net.kourlas.voipms_sms.preferences.setUnifiedPushVapidKey
import net.kourlas.voipms_sms.utils.HttpClientManager
import net.kourlas.voipms_sms.utils.JsonParserManager
import net.kourlas.voipms_sms.utils.httpPostWithMultipartFormData
import net.kourlas.voipms_sms.utils.logException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.unifiedpush.android.connector.UnifiedPush
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * F-Droid flavor worker. Three actions:
 *  - SETUP: fetch the relay's VAPID public key and register one UnifiedPush
 *    instance per DID.
 *  - REGISTER: a DID's push endpoint became available -> store it on the relay
 *    and point the VoIP.ms callback for that DID at the relay.
 *  - UNREGISTER: remove a DID's registration from the relay.
 *
 * All relay settings (URL, registration secret) are read from user preferences,
 * never from the app source.
 */
class UnifiedPushRegistrationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val action = inputData.getString(KEY_ACTION) ?: ACTION_SETUP

        return try {
            when (action) {
                ACTION_SETUP -> doSetup()

                ACTION_REGISTER -> {
                    val did = inputData.getString(KEY_DID) ?: return Result.success()
                    val endpoint = inputData.getString(KEY_ENDPOINT)
                        ?: return Result.success()
                    val p256dh = inputData.getString(KEY_P256DH)
                        ?: return Result.success()
                    val auth = inputData.getString(KEY_AUTH)
                        ?: return Result.success()

                    // Tell the relay where to push for this DID. If the network
                    // is down, retry — losing this registration means no pushes.
                    if (!relayRegister(did, endpoint, p256dh, auth)) {
                        return Result.retry()
                    }
                    setVoipMsCallback(did, enabled = true)
                    Result.success()
                }

                ACTION_UNREGISTER -> {
                    val did = inputData.getString(KEY_DID) ?: return Result.success()
                    relayUnregister(did)
                    setVoipMsCallback(did, enabled = false)
                    Result.success()
                }

                else -> Result.success()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Result.retry()
        } catch (e: Exception) {
            logException(e)
            Result.success()
        }
    }

    /** Fetch the relay's VAPID key, then register every notification DID. */
    private suspend fun doSetup(): Result {
        val relayUrl = getUnifiedPushRelayUrl(applicationContext)
        if (relayUrl.isBlank()) return Result.success()

        try {
            val vapid = fetchVapid(relayUrl)
            if (!vapid.isNullOrBlank()) {
                setUnifiedPushVapidKey(applicationContext, vapid)
            }
        } catch (e: IOException) {
            // Keep going with whatever (if anything) is cached.
        }

        val vapid = getUnifiedPushVapidKey(applicationContext).ifBlank { null }
        for (did in getDids(applicationContext, onlyShowNotifications = true)) {
            try {
                UnifiedPush.register(
                    applicationContext,
                    instance = did,
                    messageForDistributor = did,
                    vapid = vapid
                )
            } catch (e: Exception) {
                logException(e)
            }
        }
        return Result.success()
    }

    @SuppressLint("InlinedApi")
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = Notifications.getInstance(applicationContext)
            .getSyncRegisterPushNotificationsNotification()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                Notifications.SYNC_REGISTER_PUSH_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            )
        } else {
            ForegroundInfo(
                Notifications.SYNC_REGISTER_PUSH_NOTIFICATION_ID,
                notification
            )
        }
    }

    @JsonClass(generateAdapter = true)
    data class RegisterRequest(
        val did: String,
        val endpoint: String,
        val p256dh: String,
        val auth: String
    )

    @JsonClass(generateAdapter = true)
    data class DidRequest(val did: String)

    @JsonClass(generateAdapter = true)
    data class VapidResponse(val publicKey: String?)

    @JsonClass(generateAdapter = true)
    data class SetSmsResponse(val status: String)

    private fun relayBaseUrl(): String =
        getUnifiedPushRelayUrl(applicationContext).trimEnd('/')

    private fun newClient() =
        HttpClientManager.getInstance().client.newBuilder()
            .readTimeout(
                getReadTimeout(applicationContext) * 1000L, TimeUnit.MILLISECONDS
            )
            .connectTimeout(
                getConnectTimeout(applicationContext) * 1000L,
                TimeUnit.MILLISECONDS
            )
            .build()

    private suspend fun fetchVapid(relayUrl: String): String? {
        val request = Request.Builder()
            .url(relayUrl.trimEnd('/') + "/vapid")
            .get()
            .build()
        return withContext(Dispatchers.IO) {
            newClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body.string()
                JsonParserManager.getInstance().parser
                    .adapter(VapidResponse::class.java)
                    .fromJson(body)
                    ?.publicKey
            }
        }
    }

    private suspend fun relayRegister(
        did: String, endpoint: String, p256dh: String, auth: String
    ): Boolean {
        val json = JsonParserManager.getInstance().parser
            .adapter(RegisterRequest::class.java)
            .toJson(RegisterRequest(did, endpoint, p256dh, auth))
        return relayCall("/api/register", "POST", json)
    }

    private suspend fun relayUnregister(did: String): Boolean {
        val json = JsonParserManager.getInstance().parser
            .adapter(DidRequest::class.java)
            .toJson(DidRequest(did))
        return relayCall("/api/register", "DELETE", json)
    }

    private suspend fun relayCall(
        path: String, method: String, jsonBody: String
    ): Boolean {
        val body = jsonBody.toRequestBody("application/json".toMediaType())
        val builder = Request.Builder()
            .url(relayBaseUrl() + path)
            .header(
                "Authorization",
                "Bearer " + getUnifiedPushRegistrationSecret(applicationContext)
            )
        when (method) {
            "DELETE" -> builder.delete(body)
            else -> builder.post(body)
        }
        val request = builder.build()
        return withContext(Dispatchers.IO) {
            newClient().newCall(request).execute().use { it.isSuccessful }
        }
    }

    /**
     * Configure (or disable) the VoIP.ms SMS URL callback for [did] so that
     * incoming SMS hit our relay.
     */
    private suspend fun setVoipMsCallback(did: String, enabled: Boolean) {
        if (!accountConfigured(applicationContext)) return
        try {
            httpPostWithMultipartFormData<SetSmsResponse>(
                applicationContext,
                "https://voip.ms/api/v1/rest.php",
                mapOf(
                    "api_username" to getEmail(applicationContext),
                    "api_password" to getPassword(applicationContext),
                    "method" to "setSMS",
                    "did" to did,
                    "enable" to "1",
                    "url_callback_enable" to if (enabled) "1" else "0",
                    "url_callback" to (relayBaseUrl() + "/callback?did={TO}"),
                    "url_callback_retry" to "1"
                )
            )
        } catch (e: IOException) {
            // Best effort; the relay registration itself already succeeded.
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logException(e)
        }
    }

    companion object {
        private const val KEY_ACTION = "action"
        private const val KEY_DID = "did"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_P256DH = "p256dh"
        private const val KEY_AUTH = "auth"
        private const val ACTION_SETUP = "setup"
        private const val ACTION_REGISTER = "register"
        private const val ACTION_UNREGISTER = "unregister"

        private fun enqueue(context: Context, name: String, data: androidx.work.Data) {
            val work = OneTimeWorkRequestBuilder<UnifiedPushRegistrationWorker>()
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, work)
        }

        /** Fetch the relay VAPID key and (re)register every DID. */
        fun setup(context: Context) {
            enqueue(
                context, "unifiedpush_setup",
                workDataOf(KEY_ACTION to ACTION_SETUP)
            )
        }

        fun registerEndpoint(
            context: Context,
            did: String,
            endpoint: String,
            p256dh: String,
            auth: String
        ) {
            enqueue(
                context, "unifiedpush_register_$did",
                workDataOf(
                    KEY_ACTION to ACTION_REGISTER,
                    KEY_DID to did,
                    KEY_ENDPOINT to endpoint,
                    KEY_P256DH to p256dh,
                    KEY_AUTH to auth
                )
            )
        }

        fun unregisterEndpoint(context: Context, did: String) {
            enqueue(
                context, "unifiedpush_unregister_$did",
                workDataOf(KEY_ACTION to ACTION_UNREGISTER, KEY_DID to did)
            )
        }
    }
}
