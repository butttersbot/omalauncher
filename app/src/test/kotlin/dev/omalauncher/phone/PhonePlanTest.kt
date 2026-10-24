package dev.omalauncher.phone

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PhonePlanTest {
    @get:Rule val folder = TemporaryFolder()
    private val wall = 1_792_830_000_000L
    private fun input(vararg steps: JSONObject) = JSONObject().put("id", "$wall-fictional-unique")
        .put("steps", JSONArray(steps.toList()))
    private fun screen(text: String = "Display") = JSONObject().put("command", "screen").put("until", text).put("in", "com.android.settings")
    private fun open() = JSONObject().put("command", "open").put("package", "settings:display")
    private fun plan() = PhonePlan.parse(input(open(), screen()), wall)
    private fun refuses(input: JSONObject) {
        try { PhonePlan.parse(input, wall); fail("Unsafe plan accepted") } catch (_: Exception) { }
    }
    @Test fun validatesAllStepsAndEndingBeforeAnyAction() {
        refuses(input(open(), JSONObject().put("command", "shell"), screen()))
        refuses(input(JSONObject().put("command", "press").put("label", "Display"), screen()))
        refuses(input(open()))
        refuses(input(screen()).put("timeoutMs", 60_000))
        refuses(input(screen()).put("steps", JSONArray((1..41).map { screen() })))
        refuses(input(JSONObject().put("command", "open").put("package", "settings:unknown"), screen()))
        refuses(input(JSONObject().put("command", "press").put("label", "x").put("in", "com.android.settings").put("any", true), screen()))
    }
    @Test fun expiredRequestsCannotBecomeNewExecutionsAfterReceiptExpiry() {
        refuses(input(screen()).put("id", "${wall - PhonePlan.MAX_AGE_MS - 1}-fictional-unique"))
        refuses(input(screen()).put("id", "${wall + 120_001}-fictional-unique"))
    }
    @Test fun receiptSurvivesRestartAndNeverReplaysInterruptedOrCompletedPlan() {
        val file = folder.newFile().apply { delete() }
        val p = plan()
        val before = PhonePlanJournal(file, { wall }, "process-a")
        assertNull(before.begin(p))
        assertEquals("running", before.receipt(p.id).getString("state"))
        val after = PhonePlanJournal(file, { wall }, "process-b")
        val interrupted = after.begin(p)!!
        assertTrue(interrupted.getBoolean("replayed"))
        assertEquals("interrupted", interrupted.getString("state"))
        assertFalse(interrupted.getBoolean("ok"))
        after.end(p, "done", 2)
        val done = PhonePlanJournal(file, { wall }, "process-c").begin(p)!!
        assertTrue(done.getBoolean("ok")); assertEquals(2, done.getInt("completed"))
    }
    @Test fun reusingIdForDifferentStepsOrDisplayRefusesAndCorruptStorageFailsClosed() {
        val file = folder.newFile().apply { delete() }
        val journal = PhonePlanJournal(file, { wall })
        val p = plan();journal.begin(p)
        for (changed in listOf(PhonePlan.parse(input(open(), screen("Bluetooth")), wall), p.copy(display = "bg"))) {
            try { journal.begin(changed); fail("Reused id accepted") } catch (_: IllegalStateException) { }
        }
        file.writeText("not JSON")
        try { journal.begin(p); fail("Corruption erased receipt") } catch (_: Exception) { }
    }
    @Test fun reorderedJsonKeysHaveTheSameFingerprint() {
        val first = PhonePlan.parse(input(open(), screen()), wall)
        val second = PhonePlan.parse(JSONObject("""{"steps":[{"package":"settings:display","command":"open"},{"until":"Display","command":"screen","in":"com.android.settings"}],"id":"$wall-fictional-unique"}"""), wall)
        assertEquals(first.fingerprint, second.fingerprint)
    }
    @Test fun falseScreenAssertionStopsAndNeverDispatchesLaterSteps() {
        val p = PhonePlan.parse(input(screen("Missing"), open(), screen()), wall)
        var calls = 0;var result: JSONObject? = null
        PhonePlanRunner(p, { 0L }, {}, { _, reply -> calls++;reply(JSONObject().put("ok", true).put("shows", false)) }, { result = it }).start()
        assertEquals(1, calls);assertEquals(0, result!!.getInt("completed"));assertFalse(result!!.getBoolean("ok"))
    }
    @Test fun cancellationSuppressesDelayedAndDuplicateCallbacks() {
        var pending: ((JSONObject) -> Unit)? = null
        var calls = 0;val outputs = mutableListOf<JSONObject>()
        val runner = PhonePlanRunner(plan(), { 0L }, {}, { _, reply -> calls++;pending = reply }, { outputs += it })
        runner.start();runner.cancel();pending!!(JSONObject().put("ok", true));pending!!(JSONObject().put("ok", true))
        assertEquals(1, calls);assertEquals(1, outputs.size)
        assertEquals("cancelled", outputs.single().getString("state"));assertTrue(outputs.single().getBoolean("actionMayHaveRun"))
    }
    @Test fun lateSuccessAndGuardFailureDoNotAdvanceOrClaimSuccess() {
        var clock = 0L;var pending: ((JSONObject) -> Unit)? = null;var result: JSONObject? = null
        val runner = PhonePlanRunner(plan(), { clock }, {}, { _, reply -> pending = reply }, { result = it })
        runner.start();clock = 30_000;pending!!(JSONObject().put("ok", true))
        assertEquals("timed-out", result!!.getString("state"));assertEquals(0, result!!.getInt("completed"))
        var allowed = true;result = null
        val denied = PhonePlanRunner(plan(), { 0L }, { check(allowed) }, { _, reply -> pending = reply }, { result = it })
        denied.start();allowed = false;pending!!(JSONObject().put("ok", true))
        assertFalse(result!!.getBoolean("ok"));assertEquals(0, result!!.getInt("completed"))
    }
    @Test fun yieldingBetweenStepsLetsCancellationWin() {
        val scheduled = mutableListOf<() -> Unit>();var calls = 0;var result: JSONObject? = null
        val runner = PhonePlanRunner(plan(), { 0L }, {}, { _, reply -> calls++;reply(JSONObject().put("ok", true)) }, { result = it })
        runner.schedule = { scheduled += it }
        runner.start();assertEquals(1, calls);assertEquals(1, scheduled.size)
        runner.cancel();scheduled.single()()
        assertEquals(1, calls);assertEquals("cancelled", result!!.getString("state"))
    }

    @Test fun doneIncludesFreshFinalScreenAndOnlyOneCompletionPerStep() {
        var calls = 0;var result: JSONObject? = null
        PhonePlanRunner(plan(), { 0L }, {}, { _, reply ->
            calls++;val value = JSONObject().put("ok", true).put("shows", true)
            reply(value);reply(value)
        }, { result = it }).start()
        assertEquals(2, calls);assertEquals("done", result!!.getString("state"))
        assertEquals(2, result!!.getInt("completed"));assertTrue(result!!.getJSONObject("screen").getBoolean("shows"))
    }
}
