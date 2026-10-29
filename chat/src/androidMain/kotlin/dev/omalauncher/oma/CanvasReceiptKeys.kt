package dev.omalauncher.oma

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore

import java.security.Signature
import java.security.spec.ECGenParameterSpec

import org.json.JSONObject

/** No signing function is exposed to JavaScript. Private key never leaves Android Keystore. */
object CanvasReceiptKeys {
    private const val ALIAS = "soma-canvas-receipts"
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    @Synchronized fun publicKey(): JSONObject {
        if (!store().containsAlias(ALIAS)) KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKeyPair()
        val encoded = store().getCertificate(ALIAS).publicKey.encoded
        return JSONObject().put("publicKey", Base64.encodeToString(encoded, Base64.NO_WRAP)).put("deviceId", CanvasReceipt.sha256(encoded))
    }
    @Synchronized fun sign(source: ArtifactReplySource, request: ArtifactReplyRequest, submissionId: String): JSONObject? {
        val store = store()
        if (!store.containsAlias(ALIAS)) return null // Only owner Link creates the key.
        val encoded = store.getCertificate(ALIAS).publicKey.encoded
        return signed(store, CanvasReceipt.receipt(CanvasReceipt.sha256(encoded), submissionId, System.currentTimeMillis(), source, request))
    }

    /** Boss's own words ([BossReceipt]): null unless his finger sent them and this Soma is linked (holds the key). */
    @Synchronized fun signBoss(words: BossWords): JSONObject? {
        if (!words.vouched) return null
        val store = store()
        if (!store.containsAlias(ALIAS)) return null
        val encoded = store.getCertificate(ALIAS).publicKey.encoded
        val receipt = BossReceipt.receipt(CanvasReceipt.sha256(encoded), System.currentTimeMillis(), java.util.UUID.randomUUID().toString(), words) ?: return null
        return signed(store, receipt)
    }

    private fun signed(store: KeyStore, receipt: JSONObject): JSONObject {
        val serialized = CanvasReceipt.canonical(receipt)
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(store.getKey(ALIAS, null) as java.security.PrivateKey)
            update(serialized.toByteArray(Charsets.UTF_8))
        }.sign()
        return JSONObject().put("receipt", serialized).put("sig", Base64.encodeToString(signature, Base64.NO_WRAP))
    }
}
