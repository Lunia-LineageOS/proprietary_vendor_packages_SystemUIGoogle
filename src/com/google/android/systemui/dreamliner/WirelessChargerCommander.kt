package com.google.android.systemui.dreamliner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.ResultReceiver
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.systemui.dagger.SysUISingleton
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import javax.inject.Inject
import vendor.google.wireless_charger.FanMode

@SysUISingleton
class WirelessChargerCommander @Inject constructor(val wirelessCharger: Optional<WirelessCharger>) {
    fun interface WirelessChargerFanLevelChangedCallback {
        fun onFanLevelChanged(fanLevel: Int)
    }

    val isFanLevelCallbackRegistered = AtomicBoolean(false)
    val wirelessChargerFanLevelChangedCallback =
        CopyOnWriteArrayList<WirelessChargerFanLevelChangedCallback>()

    val fanLevelEventListener =
        object : Consumer<Int> {
            override fun accept(fanLevel: Int) {
                wirelessChargerFanLevelChangedCallback.forEach { it.onFanLevelChanged(fanLevel) }
            }
        }

    val commandIntents =
        IntentFilter().apply {
            addAction(ACTION_GET_DOCK_INFO)
            addAction(ACTION_KEY_EXCHANGE)
            addAction(ACTION_CHALLENGE)
            addAction(ACTION_GET_FAN_INFO)
            addAction(ACTION_GET_FAN_LEVEL)
            addAction(ACTION_SET_FAN)
            addAction(ACTION_GET_WPC_DIGESTS)
            addAction(ACTION_GET_WPC_CERTIFICATE)
            addAction(ACTION_GET_WPC_CHALLENGE_RESPONSE)
            addAction(ACTION_GET_FEATURES)
            addAction(ACTION_SET_FEATURES)
        }

    @VisibleForTesting
    val commandReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                Log.d(TAG, "onReceive(): ${intent?.action}")
                when (intent?.action) {
                    ACTION_GET_DOCK_INFO -> getInformation(intent)
                    ACTION_KEY_EXCHANGE -> keyExchange(intent)
                    ACTION_CHALLENGE -> challenge(intent)
                    ACTION_GET_FAN_INFO -> getFanInfo(intent)
                    ACTION_GET_FAN_LEVEL -> getFanLevel(intent)
                    ACTION_SET_FAN -> setFan(intent)
                    ACTION_GET_WPC_DIGESTS -> getWpcDigests(intent)
                    ACTION_GET_WPC_CERTIFICATE -> getWpcCertificate(intent)
                    ACTION_GET_WPC_CHALLENGE_RESPONSE -> getWpcChallengeResponse(intent)
                    ACTION_GET_FEATURES -> getFeatures(intent)
                    ACTION_SET_FEATURES -> setFeatures(intent)
                }
            }
        }

    fun registerFanLevelChangedCallback() {
        asyncIfPresent(wirelessCharger) { wlc ->
            wlc as WirelessChargerImpl
            if (isFanLevelCallbackRegistered.compareAndSet(false, true)) {
                wlc.mFanLevelEventListeners.add(fanLevelEventListener)
                if (
                    wlc.mIsFanLevelCallbackRegistered.compareAndSet(false, true) &&
                        wlc.initHALInterface()
                ) {
                    try {
                        wlc.mWirelessCharger.registerFanLevelChangedCallback(
                            wlc.mIWirelessChargerFanLevelChangedCallback
                        )
                    } catch (e: Exception) {
                        Log.i(HAL_TAG, "register fan level changed callback fail: ${e.message}")
                    }
                }
            }
        }
    }

    fun asyncIsDockPresent(callback: WirelessCharger.IsDockPresentCallback) {
        asyncIfPresent(wirelessCharger) { wlc ->
            wlc.asyncIsDockPresent {
                docked,
                type,
                orientation,
                isGetInfoSupported,
                id,
                manufacturerCode ->
                Log.i(
                    TAG,
                    "IDP() response: d=$docked, i=$id, m=$manufacturerCode, t=$type, " +
                        "o=$orientation, sgi=$isGetInfoSupported",
                )
                callback.onCallback(
                    docked,
                    type,
                    orientation,
                    isGetInfoSupported,
                    id,
                    manufacturerCode,
                )
            }
        }
    }

    private fun getInformation(intent: Intent) {
        Log.d(TAG, "GI()")
        val resultReceiver = intent.getResultReceiver() ?: return
        asyncIfPresent(wirelessCharger) { wlc ->
            wlc.getInformation { result, dockInfo ->
                Log.d(TAG, "GI() result: $result")
                if (result != 0) {
                    if (result != 1) {
                        resultReceiver.send(result, null)
                    }
                    return@getInformation
                }
                Log.d(TAG, "GI() response: di=$dockInfo")
                val bundle = dockInfo?.let {
                    Bundle().apply {
                        putString("manufacturer", it.manufacturer)
                        putString("model", it.model)
                        putString("serialNumber", it.serialNumber)
                        putInt("accessoryType", it.accessoryType)
                    }
                }
                resultReceiver.send(0, bundle)
            }
        }
    }

    private fun keyExchange(intent: Intent) {
        Log.d(TAG, "KE()")
        val resultReceiver = intent.getResultReceiver() ?: return
        val publicKey = intent.getByteArrayExtra(EXTRA_PUBLIC_KEY)
        if (publicKey == null || publicKey.isEmpty()) {
            resultReceiver.send(-1, null)
            return
        }
        asyncIfPresent(wirelessCharger) { wlc ->
            wlc.keyExchange(publicKey) { result, dockId, dockPublicKey ->
                Log.d(TAG, "KE() result: $result")
                if (result != 0 || dockPublicKey == null) {
                    resultReceiver.send(result, null)
                    return@keyExchange
                }
                Log.d(TAG, "KE() response: pk=$dockPublicKey")
                val bundle =
                    if (dockPublicKey.isEmpty()) {
                        null
                    } else {
                        Bundle().apply {
                            putByte("dock_id", dockId)
                            putByteArray("dock_public_key", dockPublicKey.toByteArray())
                        }
                    }
                resultReceiver.send(0, bundle)
            }
        }
    }

    private fun challenge(intent: Intent) {
        Log.d(TAG, "C()")
        val resultReceiver = intent.getResultReceiver() ?: return
        val dockId = intent.getByteExtra(EXTRA_CHALLENGE_DOCK_ID, -1)
        val challengeData = intent.getByteArrayExtra(EXTRA_CHALLENGE_DATA)
        if (challengeData == null || challengeData.isEmpty() || dockId < 0) {
            resultReceiver.send(-1, null)
            return
        }
        asyncIfPresent(wirelessCharger) { wlc ->
            wlc.challenge(dockId, challengeData) { result, response ->
                Log.d(TAG, "C() result: $result")
                if (result != 0 || response == null) {
                    resultReceiver.send(result, null)
                    return@challenge
                }
                Log.d(TAG, "C() response: $response")
                val bundle =
                    if (response.isEmpty()) {
                        null
                    } else {
                        Bundle().apply {
                            putByteArray("challenge_response", response.toByteArray())
                        }
                    }
                resultReceiver.send(0, bundle)
            }
        }
    }

    private fun getFanInfo(intent: Intent) {
        val fanId = intent.getByteExtra(EXTRA_FAN_ID, -1)
        Log.d(TAG, "GFI(), i=$fanId")
        val resultReceiver = intent.getResultReceiver() ?: return
        asyncIfPresent(wirelessCharger) { wlc ->
            wlc.getFanInformation(fanId) { result, bundle ->
                Log.d(TAG, "GFI(), result=$result, i=$fanId")
                if (result != 0 || bundle == null) {
                    resultReceiver.send(result, null)
                    return@getFanInformation
                }
                Log.d(
                    TAG,
                    "GFI() response: i=${bundle.getByte("fan_id", -1)}" +
                        ", m=${bundle.getByte("fan_mode", -1)}" +
                        ", cr=${bundle.getInt("fan_current_rpm", -1)}" +
                        ", mnr=${bundle.getInt("fan_min_rpm", -1)}" +
                        ", mxr=${bundle.getInt("fan_max_rpm", -1)}" +
                        ", t=${bundle.getByte("fan_type", -1)}" +
                        ", c=${bundle.getByte("fan_count", -1)}",
                )
                resultReceiver.send(0, bundle)
            }
        }
    }

    private fun getFanLevel(intent: Intent) {
        Log.d(TAG, "GFL()")
        val resultReceiver = intent.getResultReceiver()
        asyncIfPresent(wirelessCharger) { wlc ->
            wlc as WirelessChargerImpl
            val startTime = System.currentTimeMillis()
            var fanLevel = -1
            if (wlc.initHALInterface()) {
                Log.d(HAL_TAG, "command=2")
                try {
                    fanLevel = wlc.mWirelessCharger.fanLevel
                } catch (e: Exception) {
                    Log.i(HAL_TAG, "command=2 fail: ${e.message}")
                }
            }
            Log.d(
                TAG,
                "GFL() response: l=$fanLevel, spending time=${System.currentTimeMillis() - startTime}",
            )
            if (resultReceiver != null) {
                resultReceiver.send(0, Bundle().apply { putInt("fan_level", fanLevel) })
            } else {
                wirelessChargerFanLevelChangedCallback.forEach { it.onFanLevelChanged(fanLevel) }
            }
        }
    }

    private fun setFan(intent: Intent) {
        val fanId = intent.getByteExtra(EXTRA_FAN_ID, 0)
        val fanMode = intent.getByteExtra(EXTRA_FAN_MODE, 0)
        val fanRpm = intent.getIntExtra(EXTRA_FAN_RPM, -1)
        Log.d(TAG, "SF(), i=$fanId, m=$fanMode, r=$fanRpm")
        val resultReceiver = intent.getResultReceiver()
        if (fanMode == FanMode.RPM && fanRpm == -1) {
            Log.e(TAG, "Failed to get r.")
            return
        }
        asyncIfPresent(wirelessCharger) { wlc ->
            wlc.setFan(fanId, fanMode, fanRpm) { result, bundle ->
                Log.w(TAG, "SF() result=$result")
                if (result != 0 || bundle == null) {
                    resultReceiver?.send(result, null)
                    return@setFan
                }
                Log.d(
                    TAG,
                    "SF() response: i=${bundle.getByte("fan_id", -1)}" +
                        ", m=${bundle.getByte("fan_mode", -1)}" +
                        ", cr=${bundle.getInt("fan_current_rpm", -1)}",
                )
                resultReceiver?.send(0, Bundle())
            }
        }
    }

    private fun getWpcDigests(intent: Intent) {
        val slotMask = intent.getByteExtra(EXTRA_SLOT_MASK, -1)
        Log.d(TAG, "GWAD(), s=$slotMask")
        val resultReceiver = intent.getResultReceiver() ?: return
        if (slotMask.toInt() == -1) {
            resultReceiver.send(-1, null)
            return
        }
        asyncIfPresent(wirelessCharger) { wlc ->
            wlc as WirelessChargerImpl
            if (!wlc.initHALInterface()) return@asyncIfPresent
            var error = 0
            var populatedMask: Byte = 0
            var returnedMask: Byte = 0
            var digests: ArrayList<ByteArray>? = null
            try {
                val wpcAuthDigests = wlc.mWirelessCharger.getWpcAuthDigests(slotMask)
                populatedMask = wpcAuthDigests.slotPopulatedMask
                returnedMask = wpcAuthDigests.slotReturnedMask
                digests = wpcAuthDigests.digests.toCollection(ArrayList())
            } catch (e: Exception) {
                error = WirelessChargerImpl.mapError(e)
                Log.i(HAL_TAG, "get wpc digests fail: ${e.message}")
            }
            Log.d(TAG, "GWAD() result: $error")
            if (error != 0 || digests == null) {
                resultReceiver.send(error, null)
                return@asyncIfPresent
            }
            Log.d(TAG, "GWAD() response: pm=$populatedMask, rm=$returnedMask, d=$digests")
            val bundle =
                Bundle().apply {
                    putByte("slot_populated_mask", populatedMask)
                    putByte("slot_returned_mask", returnedMask)
                    putParcelableArrayList(
                        "wpc_digests",
                        digests.mapTo(ArrayList()) {
                            Bundle().apply { putByteArray("wpc_digest", it) }
                        },
                    )
                }
            resultReceiver.send(0, bundle)
        }
    }

    private fun getWpcCertificate(intent: Intent) {
        val slotNumber = intent.getByteExtra(EXTRA_SLOT_NUMBER, -1)
        val certOffset = intent.getShortExtra(EXTRA_CERT_OFFSET, -1)
        val certLength = intent.getShortExtra(EXTRA_CERT_LENGTH, -1)
        Log.d(TAG, "GWAC(), s=$slotNumber, offset=$certOffset, length=$certLength")
        val resultReceiver = intent.getResultReceiver() ?: return
        if (slotNumber.toInt() == -1 || certOffset.toInt() == -1 || certLength.toInt() == -1) {
            resultReceiver.send(-1, null)
            return
        }
        asyncIfPresent(wirelessCharger) { wlc ->
            wlc as WirelessChargerImpl
            if (!wlc.initHALInterface()) return@asyncIfPresent
            var error = 0
            var cert: ArrayList<Byte>? = null
            try {
                cert =
                    WirelessChargerImpl.convertPrimitiveArrayToArrayList(
                        wlc.mWirelessCharger.getWpcAuthCertificate(
                            slotNumber,
                            certOffset.toInt().toChar(),
                            certLength.toInt().toChar(),
                        )
                    )
            } catch (e: Exception) {
                error = WirelessChargerImpl.mapError(e)
                Log.i(HAL_TAG, "get wpc cert fail: ${e.message}")
            }
            Log.d(TAG, "GWAC() result: $error")
            if (error != 0 || cert == null) {
                resultReceiver.send(error, null)
                return@asyncIfPresent
            }
            Log.d(TAG, "GWAC() response: c=$cert")
            val bundle =
                if (cert.isEmpty()) {
                    null
                } else {
                    Bundle().apply { putByteArray("wpc_cert", cert.toByteArray()) }
                }
            resultReceiver.send(0, bundle)
        }
    }

    private fun getWpcChallengeResponse(intent: Intent) {
        val slotNumber = intent.getByteExtra(EXTRA_SLOT_NUMBER, -1)
        Log.d(TAG, "GWACR(), s=$slotNumber")
        val resultReceiver = intent.getResultReceiver() ?: return
        val nonce = intent.getByteArrayExtra(EXTRA_WPC_NONCE)
        if (nonce == null || nonce.isEmpty()) {
            resultReceiver.send(-1, null)
            return
        }
        asyncIfPresent(wirelessCharger) { wlc ->
            wlc as WirelessChargerImpl
            if (!wlc.initHALInterface()) return@asyncIfPresent
            var error = 0
            var maxProtocolVersion: Byte = 0
            var populatedMask: Byte = 0
            var certLsb: Byte = 0
            var signatureR: ArrayList<Byte>? = null
            var signatureS: ArrayList<Byte>? = null
            try {
                val response = wlc.mWirelessCharger.getWpcAuthChallengeResponse(slotNumber, nonce)
                maxProtocolVersion = response.maxProtocolVersion
                populatedMask = response.slotPopulatedMask
                certLsb = response.certificateChainHashLsb
                signatureR =
                    WirelessChargerImpl.convertPrimitiveArrayToArrayList(response.signatureR)
                signatureS =
                    WirelessChargerImpl.convertPrimitiveArrayToArrayList(response.signatureS)
            } catch (e: Exception) {
                error = WirelessChargerImpl.mapError(e)
                Log.i(HAL_TAG, "get wpc challenge response fail: ${e.message}")
            }
            Log.d(TAG, "GWACR() result: $error")
            if (error != 0 || signatureR == null || signatureS == null) {
                resultReceiver.send(error, null)
                return@asyncIfPresent
            }
            Log.d(
                TAG,
                "GWACR() response: mpv=$maxProtocolVersion, pm=$populatedMask, chl=$certLsb" +
                    ", rv=$signatureR, sv=$signatureS",
            )
            val bundle =
                Bundle().apply {
                    putByte("max_protocol_ver", maxProtocolVersion)
                    putByte("slot_populated_mask", populatedMask)
                    putByte("cert_lsb", certLsb)
                    putByteArray("signature_r", signatureR.toByteArray())
                    putByteArray("signature_s", signatureS.toByteArray())
                }
            resultReceiver.send(0, bundle)
        }
    }

    private fun getFeatures(intent: Intent) {
        val chargerId = intent.getLongExtra(EXTRA_CHARGER_ID, -1L)
        Log.d(TAG, "GF(), c=$chargerId")
        val resultReceiver = intent.getResultReceiver() ?: return
        if (chargerId == -1L) {
            resultReceiver.send(-1, null)
            return
        }
        asyncIfPresent(wirelessCharger) { wlc ->
            wlc as WirelessChargerImpl
            if (!wlc.initHALInterface()) return@asyncIfPresent
            var error = 0
            var features = 0L
            try {
                features = wlc.mWirelessCharger.getFeatures(chargerId)
            } catch (e: Exception) {
                error = WirelessChargerImpl.mapError(e)
                Log.i(HAL_TAG, "get features fail: ${e.message}")
            }
            if (error != 0) {
                resultReceiver.send(error, null)
                return@asyncIfPresent
            }
            Log.d(TAG, "GF() response: f=$features")
            resultReceiver.send(0, Bundle().apply { putLong("charger_feature", features) })
        }
    }

    private fun setFeatures(intent: Intent) {
        val chargerId = intent.getLongExtra(EXTRA_CHARGER_ID, -1L)
        val chargerFeature = intent.getLongExtra(EXTRA_CHARGER_FEATURE, -1L)
        val resultReceiver = intent.getResultReceiver() ?: return
        Log.d(TAG, "SF(), c=$chargerId, feature=$chargerFeature")
        if (chargerId == -1L || chargerFeature == -1L) {
            resultReceiver.send(-1, null)
            return
        }
        asyncIfPresent(wirelessCharger) { wlc ->
            wlc as WirelessChargerImpl
            if (!wlc.initHALInterface()) return@asyncIfPresent
            var error = 0
            try {
                wlc.mWirelessCharger.setFeatures(chargerId, chargerFeature)
            } catch (e: Exception) {
                error = WirelessChargerImpl.mapError(e)
                Log.i(HAL_TAG, "set features fail: ${e.message}")
            }
            resultReceiver.send(error, null)
        }
    }

    private fun Intent.getResultReceiver(): ResultReceiver? =
        getParcelableExtra(Intent.EXTRA_RESULT_RECEIVER, ResultReceiver::class.java)

    companion object {
        private const val TAG = "WirelessChargerCommander"
        private const val HAL_TAG = "Dreamliner-WLC_HAL"

        private const val ACTION_GET_DOCK_INFO =
            "com.google.android.systemui.dreamliner.ACTION_GET_DOCK_INFO"
        private const val ACTION_KEY_EXCHANGE =
            "com.google.android.systemui.dreamliner.ACTION_KEY_EXCHANGE"
        private const val ACTION_CHALLENGE =
            "com.google.android.systemui.dreamliner.ACTION_CHALLENGE"
        private const val ACTION_GET_FAN_INFO =
            "com.google.android.systemui.dreamliner.ACTION_GET_FAN_INFO"
        private const val ACTION_GET_FAN_LEVEL =
            "com.google.android.systemui.dreamliner.ACTION_GET_FAN_LEVEL"
        private const val ACTION_SET_FAN = "com.google.android.systemui.dreamliner.ACTION_SET_FAN"
        private const val ACTION_GET_WPC_DIGESTS =
            "com.google.android.systemui.dreamliner.ACTION_GET_WPC_DIGESTS"
        private const val ACTION_GET_WPC_CERTIFICATE =
            "com.google.android.systemui.dreamliner.ACTION_GET_WPC_CERTIFICATE"
        private const val ACTION_GET_WPC_CHALLENGE_RESPONSE =
            "com.google.android.systemui.dreamliner.ACTION_GET_WPC_CHALLENGE_RESPONSE"
        private const val ACTION_GET_FEATURES =
            "com.google.android.systemui.dreamliner.ACTION_GET_FEATURES"
        private const val ACTION_SET_FEATURES =
            "com.google.android.systemui.dreamliner.ACTION_SET_FEATURES"

        private const val EXTRA_PUBLIC_KEY = "public_key"
        private const val EXTRA_CHALLENGE_DOCK_ID = "challenge_dock_id"
        private const val EXTRA_CHALLENGE_DATA = "challenge_data"
        private const val EXTRA_FAN_ID = "fan_id"
        private const val EXTRA_FAN_MODE = "fan_mode"
        private const val EXTRA_FAN_RPM = "fan_rpm"
        private const val EXTRA_SLOT_MASK = "slot_mask"
        private const val EXTRA_SLOT_NUMBER = "slot_number"
        private const val EXTRA_CERT_OFFSET = "cert_offset"
        private const val EXTRA_CERT_LENGTH = "cert_length"
        private const val EXTRA_WPC_NONCE = "wpc_nonce"
        private const val EXTRA_CHARGER_ID = "charger_id"
        private const val EXTRA_CHARGER_FEATURE = "charger_feature"

        private var singleThreadExecutor: ExecutorService? = null

        fun asyncIfPresent(
            wirelessCharger: Optional<WirelessCharger>,
            block: (WirelessCharger) -> Unit,
        ) {
            if (singleThreadExecutor == null) {
                singleThreadExecutor = Executors.newSingleThreadExecutor()
            }
            singleThreadExecutor?.execute { wirelessCharger.ifPresent { block(it) } }
        }
    }
}
