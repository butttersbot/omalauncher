package dev.omalauncher.connections

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.json.JSONObject

/** Not exported through any intent, agent bridge or CLI. Every signature needs the owner's OS-authenticated operation. */
internal object ConnectionOwnerKey {
    private const val ALIAS = "soma-connections-owner-v1"
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    @Synchronized fun publicKey(): JSONObject {
        if (!store().containsAlias(ALIAS)) {
            val builder = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true).setUnlockedDeviceRequired(true)
            if (Build.VERSION.SDK_INT >= 30) builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            else @Suppress("DEPRECATION") builder.setUserAuthenticationValidityDurationSeconds(-1)
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(builder.build()) }.generateKeyPair()
        }
        val store = store()
        val key = store.getKey(ALIAS, null) as PrivateKey
        val info = KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
        require(info.isUserAuthenticationRequired && info.userAuthenticationValidityDurationSeconds <= 0) { "Owner key is not auth-per-use" }
        val bytes = store.getCertificate(ALIAS).publicKey.encoded
        val id = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return JSONObject().put("schema", 1).put("algorithm", "SHA256withECDSA-P256")
            .put("key_id", id).put("public_key", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("auth_per_use", true)
    }
    fun exportPublic(home: File): JSONObject {
        val value = publicKey()
        val directory = File(home, ".local/share/soma/connections").apply { mkdirs() }
        val target = File(directory, "owner-public-key.json")
        val temp = File(directory, ".owner-public-key.new")
        java.io.FileOutputStream(temp).use { out -> out.write(value.toString(2).toByteArray()); out.fd.sync() }
        java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return value
    }
    fun authenticate(activity: Activity, plan: OwnerServicePlan, description: String,
        done: (Result<Pair<String, String>>) -> Unit): CancellationSignal {
        require(plan.fresh(System.currentTimeMillis())) { "Restore approval expired" }
        require(!activity.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Owner unlock required" }
        val public = publicKey()
        val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(store().getKey(ALIAS, null) as PrivateKey) }
        val builder = BiometricPrompt.Builder(activity).setTitle("Restore Connections").setSubtitle("Owner approval for this repair only")
            .setDescription(description.take(500)).setConfirmationRequired(true)
        if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        else builder.setNegativeButton("Cancel", activity.mainExecutor) { _, _ -> done(Result.failure(IllegalStateException("Owner cancelled"))) }
        val cancellation = CancellationSignal()
        builder.build().authenticate(BiometricPrompt.CryptoObject(signature), cancellation, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { done(Result.failure(IllegalStateException("Owner authentication failed ($errorCode)"))) }
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                done(runCatching {
                    require(plan.fresh(System.currentTimeMillis()) && !activity.isFinishing && !activity.isDestroyed) { "Restore approval expired or closed" }
                    require(!activity.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Owner unlock required" }
                    val approved = result.cryptoObject?.signature ?: error("Missing owner-authenticated operation")
                    approved.update(plan.payload.toByteArray(Charsets.UTF_8))
                    public.getString("key_id") to Base64.encodeToString(approved.sign(), Base64.NO_WRAP)
                })
            }
        })
        return cancellation
    }
}
