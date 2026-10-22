package dev.omalauncher.steps

import android.Manifest
import android.app.Activity
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import dev.omalauncher.news.NewsStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Steps per day from the phone's step counter, which only counts up from the last boot: each reading adds what the
 * counter moved since the one before to the day it was read on (a smaller count means the phone restarted, and all
 * of it is new). Kept for [DAYS] days. Pure, so it can be tested; [Steps] feeds it and keeps it.
 */
class StepLog(
    var last: Long = -1,
    val days: MutableMap<String, Long> = LinkedHashMap(),
) {
    /** The counter read [counter] on [day] (yyyy-MM-dd); returns the steps it added. */
    fun add(
        counter: Long,
        day: String,
    ): Long {
        val moved =
            when {
                last < 0 -> 0
                counter < last -> counter
                else -> counter - last
            }
        last = counter
        days[day] = (days[day] ?: 0) + moved
        if (days.size > DAYS) days.keys.sorted().take(days.size - DAYS).forEach { days.remove(it) }
        return moved
    }

    /** The [n] days up to [today], oldest first, with the steps of each (0 for a day with none). */
    fun week(
        today: LocalDate,
        n: Int = 7,
    ): List<Pair<LocalDate, Long>> = (n - 1 downTo 0).map { today.minusDays(it.toLong()) }.map { it to (days[it.toString()] ?: 0L) }

    fun json(): JSONObject = JSONObject().put("last", last).put("days", JSONObject(days as Map<*, *>))

    companion object {
        const val DAYS = 14

        fun parse(text: String?): StepLog =
            runCatching {
                val o = JSONObject(text!!)
                val d = LinkedHashMap<String, Long>()
                o.optJSONObject("days")?.let { j -> for (k in j.keys()) d[k] = j.getLong(k) }
                StepLog(o.optLong("last", -1), d)
            }.getOrDefault(StepLog())
    }
}

/**
 * The Steps widget's count (Boss, 2026-10-21: "very simple just count my steps per day"): the step counter is
 * listened to while the screen is on, so the pet walks while he does, and read every quarter hour by [StepsJob]
 * otherwise. What the widget shows is in the guest's ~/.cache/soma-steps/steps.json:
 * `{available, allowed, today, week: [{day, steps}], walkingUntil, updated}`.
 * Needs the activity recognition permission, asked once by [StepsAllow] from the widget.
 */
object Steps {
    private const val PREFS = "steps"

    /** A step within this long of now counts as walking (the counter reports in batches a few seconds apart). */
    private const val WALKING_MS = 6_000L

    private val main = Handler(Looper.getMainLooper())
    private var log: StepLog? = null
    private var lastStepAt = 0L
    private var listening = false
    private var watching = false
    private var lastWrite = 0L

    fun allowed(context: Context) = context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun sensor(context: Context): Sensor? = context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    private fun log(context: Context): StepLog = log ?: StepLog.parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("log", null)).also { log = it }

    private val listener =
        object : SensorEventListener {
            var context: Context? = null

            override fun onSensorChanged(event: SensorEvent) {
                val c = context ?: return
                reading(c, event.values[0].toLong())
            }

            override fun onAccuracyChanged(
                sensor: Sensor?,
                accuracy: Int,
            ) {}
        }

    /** Starts counting: listens while the screen is on, and has [StepsJob] read the counter while it's off. Main thread. */
    fun start(context: Context) {
        val app = context.applicationContext
        write(app)
        if (!allowed(app) || sensor(app) == null) return
        StepsJob.schedule(app)
        if (!watching) {
            watching = true
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        c: Context,
                        intent: Intent,
                    ) {
                        if (intent.action == Intent.ACTION_SCREEN_ON) listen(app) else stop(app)
                    }
                }
            val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }
            if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(receiver, filter)
        }
        if (app.getSystemService(PowerManager::class.java).isInteractive) listen(app)
    }

    private fun listen(context: Context) {
        if (listening || !allowed(context)) return
        val s = sensor(context) ?: return
        listener.context = context
        // Batched for a couple of seconds: the hub keeps counting and the phone isn't woken for every step.
        listening = context.getSystemService(SensorManager::class.java).registerListener(listener, s, SensorManager.SENSOR_DELAY_NORMAL, 2_000_000, main)
    }

    private fun stop(context: Context) {
        if (!listening) return
        context.getSystemService(SensorManager::class.java).unregisterListener(listener)
        listening = false
    }

    /** One reading of the counter: into the log, kept, and the widget's file written (at most every few seconds while walking). */
    fun reading(
        context: Context,
        counter: Long,
    ) {
        val l = log(context)
        val now = System.currentTimeMillis()
        val moved = l.add(counter, LocalDate.now().toString())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("log", l.json().toString()).apply()
        val wasWalking = now - lastStepAt < WALKING_MS
        if (moved > 0) lastStepAt = now
        if (moved > 0 && wasWalking && now - lastWrite < 3_000) return
        write(context)
    }

    /** Writes steps.json for the widget. */
    fun write(context: Context) {
        lastWrite = System.currentTimeMillis()
        runCatching {
            val l = log(context)
            val today = LocalDate.now()
            val week = JSONArray()
            for ((day, n) in l.week(today)) week.put(JSONObject().put("day", day.dayOfWeek.name.take(1)).put("date", day.toString()).put("steps", n))
            val o =
                JSONObject()
                    .put("available", sensor(context) != null)
                    .put("allowed", allowed(context))
                    .put("today", l.days[today.toString()] ?: 0L)
                    .put("week", week)
                    .put("walkingUntil", if (lastStepAt == 0L) 0 else (lastStepAt + WALKING_MS) / 1000)
                    .put("updated", Instant.now().epochSecond)
                    .put("zone", ZoneId.systemDefault().id)
            val d = File(NewsStore.home(context), ".cache/soma-steps").apply { mkdirs() }
            val tmp = File(d, "steps.json.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(File(d, "steps.json"))
        }
    }

    /** One reading with the screen off, for [StepsJob]: listens until the counter answers, then lets go. */
    fun readOnce(
        context: Context,
        done: () -> Unit,
    ) {
        val s = sensor(context)
        if (s == null || !allowed(context)) return done()
        if (listening) {
            write(context)
            return done()
        }
        val sm = context.getSystemService(SensorManager::class.java)
        var finished = false
        val once =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (finished) return
                    finished = true
                    sm.unregisterListener(this)
                    reading(context, event.values[0].toLong())
                    done()
                }

                override fun onAccuracyChanged(
                    sensor: Sensor?,
                    accuracy: Int,
                ) {}
            }
        sm.registerListener(once, s, SensorManager.SENSOR_DELAY_NORMAL, 0, main)
        // A counter that says nothing (no steps since boot on some phones) still gets the day's file rewritten.
        main.postDelayed({
            if (!finished) {
                finished = true
                sm.unregisterListener(once)
                write(context)
                done()
            }
        }, 10_000)
    }
}

/** Reads the step counter every quarter hour, so the day's tally holds while the screen is off. */
class StepsJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Steps.readOnce(applicationContext) { jobFinished(params, false) }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val ID = 0x57e95

        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(ID) != null) return
            js.schedule(JobInfo.Builder(ID, ComponentName(context, StepsJob::class.java)).setPeriodic(15 * 60_000L).setPersisted(false).build())
        }
    }
}

/** Asks for the activity recognition permission, from the Steps widget's tap; starts counting once it's given. */
class StepsAllow : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Steps.allowed(this)) return done()
        requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 1)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        done()
    }

    private fun done() {
        Steps.start(this)
        finish()
    }

    companion object {
        fun open(context: Context) = context.startActivity(Intent(context, StepsAllow::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
