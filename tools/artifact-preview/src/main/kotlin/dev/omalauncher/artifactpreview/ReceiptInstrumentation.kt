package dev.omalauncher.artifactpreview

import android.app.Instrumentation
import android.os.Bundle
import android.util.Base64
import android.util.Log
import dev.omalauncher.oma.*
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** Emulator-only cryptographic check. It neither links a device nor calls Canvas nor sends answers. */
class ReceiptInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        try {
            val publicInfo = CanvasReceiptKeys.publicKey()
            check(publicInfo.toString() == CanvasReceiptKeys.publicKey().toString())
            val bytes = Base64.decode(publicInfo.getString("publicKey"), Base64.DEFAULT)
            check(CanvasReceipt.sha256(bytes) == publicInfo.getString("deviceId"))
            val source = ArtifactReplySource("fixture", "mock.html", "1", CanvasReceipt.sha256("fixture".toByteArray()))
            val signed = CanvasReceiptKeys.sign(source, ArtifactReplyRequest("phone:B", "approve", """{"decision":"approve"}"""))!!
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
            fun verify(text: String) = Signature.getInstance("SHA256withECDSA").run {
                initVerify(key); update(text.toByteArray(Charsets.UTF_8)); verify(Base64.decode(signed.getString("sig"), Base64.DEFAULT))
            }
            val canonical = CanvasReceipt.canonical(signed.getJSONObject("receipt"))
            check(verify(canonical)); check(!verify(canonical.replace("approve", "changes")))
            Log.i("SomaWalk", "artifact receipt signature PASS")
            finish(0, Bundle().apply { putString("result", "PASS") })
        } catch (e: Throwable) {
            Log.e("SomaWalk", "artifact receipt FAIL", e)
            finish(1, Bundle().apply { putString("error", e.toString()) })
        }
    }
}
