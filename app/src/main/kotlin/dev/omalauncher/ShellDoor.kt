package dev.omalauncher

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import dev.omalauncher.phone.PhoneControlHeal
import java.io.File
import java.nio.file.Files

/*
 * What tools on a computer use over adb, now that the working launcher isn't debuggable and has no run-as
 * (docs/NON-DEBUGGABLE.md). Only Android's shell user gets an answer, the same people run-as let in, and only in the
 * working build. `content call --uri content://PACKAGE.door --method NAME`:
 *   open, close     the door: shut unless opened. Closing also empties the folder below.
 *   status          says open or closed.
 *   run ID          runs /root/.cache/oma-mac/ID.sh in his Linux. Open door only. It runs what a script can.
 *   wait ID         waits a few seconds for that run to end and says its exit code, which it left in ID.code. Open door only.
 *   phone-control-wanted   whether he wants phone control on: for oma-install. Open or shut.
 * `content read`, `content write` and `content delete --uri content://PACKAGE.door/guest/NAME` read, write and delete
 * one file in that folder, and no other. Open door only.
 */

/** The switch file, in the app's own files. */
private const val DOOR_SWITCH = "shell-door-open"

/** The key-file door this one replaced, removed where it was left open. */
private const val OLD_SWITCH = "allow-debug-exec"

/** The one folder the door reads and writes, under the app's files: /root/.cache/oma-mac in his Linux. */
private const val DOOR_FOLDER = "arch/root/.cache/oma-mac"

/** The longest `wait` holds its call: oma-mac asks again. */
private const val DOOR_WAIT_MILLIS = 5_000L

/** Empties [dir] without following a link out of it, whatever a script left there. */
private fun emptyWithoutFollowing(dir: File) {
    for (child in dir.listFiles().orEmpty()) {
        if (!Files.isSymbolicLink(child.toPath()) && child.isDirectory) emptyWithoutFollowing(child)
        child.delete()
    }
}

/** Whether [uid] gets an answer: only Android's shell user, and only in a working build. Not root: nothing else needs it. */
fun shellDoorAnswers(
    uid: Int,
    working: Boolean,
): Boolean = working && uid == Process.SHELL_UID

/** A file name the door takes: one plain name, so no path can lead out of its folder. */
fun shellDoorName(name: String?): Boolean = name != null && Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}").matches(name)

/** What `run` takes: the id oma-mac makes, 16 hex digits. */
fun shellDoorRunId(id: String?): Boolean = id != null && Regex("[0-9a-f]{16}").matches(id)

class ShellDoor : ContentProvider() {
    private val files get() = requireNotNull(context).filesDir
    private val folder get() = File(files, DOOR_FOLDER)
    private val open get() = File(files, DOOR_SWITCH).exists()

    /** The folder, unless his Linux has made it or the way to it a link: then there is nothing to read or write. */
    private val realFolder: File
        get() {
            check(folder.canonicalPath == File(files.canonicalFile, DOOR_FOLDER).path) { "Not a file of the door's" }
            return folder
        }

    /** One plain file of the folder's. A link, even to nothing yet, is not one: opening it would write where it points. */
    private fun doorFile(name: String?): File {
        require(shellDoorName(name)) { "Not a file of the door's" }
        val file = File(realFolder, name!!)
        require(!Files.isSymbolicLink(file.toPath())) { "Not a file of the door's" }
        return file
    }

    override fun onCreate(): Boolean {
        context?.let { File(it.filesDir, OLD_SWITCH).delete() }
        return true
    }

    private fun guestFile(uri: Uri): File {
        val segments = uri.pathSegments
        require(segments.size == 2 && segments[0] == "guest") { "Not a file of the door's" }
        return doorFile(segments[1])
    }

    private fun onlyShell() {
        if (!shellDoorAnswers(Binder.getCallingUid(), BuildConfig.WORKING_BUILD)) throw SecurityException("Only Android's shell user")
    }

    private fun onlyOpen() {
        check(open) { "The door is closed" }
    }

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle {
        onlyShell()
        val answer = Bundle()
        when (method) {
            "open" -> {
                realFolder.mkdirs()
                File(files, DOOR_SWITCH).writeText("open\n")
            }
            "close" -> {
                File(files, DOOR_SWITCH).delete()
                if (Files.isSymbolicLink(folder.toPath())) folder.delete() else emptyWithoutFollowing(folder)
            }
            "status" -> answer.putString("state", if (open) "open" else "closed")
            "run" -> {
                onlyOpen()
                require(shellDoorRunId(arg)) { "Not a run id" }
                val runtime = (requireNotNull(context).applicationContext as App).runtime
                runtime.launch("bash /root/.cache/oma-mac/$arg.sh")
            }
            "wait" -> {
                onlyOpen()
                require(shellDoorRunId(arg)) { "Not a run id" }
                val code = doorFile("$arg.code")
                val until = SystemClock.uptimeMillis() + DOOR_WAIT_MILLIS
                while (code.length() == 0L && SystemClock.uptimeMillis() < until) SystemClock.sleep(50)
                if (code.length() > 0L) answer.putString("code", code.readText().trim())
            }
            "phone-control-wanted" -> answer.putBoolean("wanted", PhoneControlHeal.wanted(requireNotNull(context)))
            else -> throw IllegalArgumentException("No such method")
        }
        return answer
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        onlyShell()
        onlyOpen()
        val file = guestFile(uri)
        return when (mode.replace("t", "").replace("b", "")) {
            "r" -> {
                if (!file.exists()) throw java.io.FileNotFoundException(file.name)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            "w" -> ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
            else -> throw IllegalArgumentException("Only read and write")
        }
    }

    override fun getType(uri: Uri): String? = null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        onlyShell()
        onlyOpen()
        return if (guestFile(uri).delete()) 1 else 0
    }
}
