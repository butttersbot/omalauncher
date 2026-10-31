package dev.omalauncher.artifactpreview

import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * Another app asking the launcher's shell door for everything it does, through Android's own content resolver: each
 * has to be refused for not being the shell user. `am instrument -w dev.omalauncher.artifactpreview/.DoorRefusalInstrumentation`.
 */
class DoorRefusalInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val door = Uri.parse("content://dev.omalauncher.boss.door")
        val file = door.buildUpon().appendPath("guest").appendPath("x").build()
        val resolver = context.contentResolver
        val asks = linkedMapOf<String, () -> Any?>(
            "status" to { resolver.call(door, "status", null, null) },
            "open" to { resolver.call(door, "open", null, null) },
            "close" to { resolver.call(door, "close", null, null) },
            "run" to { resolver.call(door, "run", "0123456789abcdef", null) },
            "phone-control-wanted" to { resolver.call(door, "phone-control-wanted", null, null) },
            "read" to { resolver.openFileDescriptor(file, "r") },
            "write" to { resolver.openFileDescriptor(file, "w") },
            "delete" to { resolver.delete(file, null, null) },
            "query" to { resolver.query(door, null, null, null, null).also { it?.close() } },
        )
        val out = Bundle()
        for ((name, ask) in asks) {
            val said = runCatching { ask() }.fold({ "ANSWERED $it" }, { "refused ${it.javaClass.simpleName}: ${it.message}" })
            Log.i("SomaWalk", "door $name: $said")
            out.putString(name, said)
        }
        finish(Activity.RESULT_OK, out)
    }
}
