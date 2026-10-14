package dev.omalauncher.phone

import android.app.*
import android.content.*
import android.os.*
import dev.omalauncher.R

/** Accessibility consent enables on-demand access; Stop remains a persistent user override. */
object PhoneAccess {
    @Volatile private var paused = false
    val active get() = PhoneService.instance != null && !paused
    /** Where the service stands with Android now, read from its settings: see [PhoneLink]. */
    fun link(context: Context): PhoneLink {
        val mine = ComponentName(context, PhoneService::class.java)
        val listed = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == mine }
        return phoneLink(PhoneService.instance != null, listed, SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime(), PhoneControlHeal.gaveUp(context))
    }
    fun load(context: Context) {
        paused = context.getSharedPreferences("phone-control", Context.MODE_PRIVATE).getBoolean("paused", false)
    }
    fun resume(context: Context) {
        paused = false
        context.getSharedPreferences("phone-control", Context.MODE_PRIVATE).edit().putBoolean("paused", false).apply()
        PhoneControlHeal.resumed(context)
        PhoneService.instance?.ringChanged()
    }
    fun stop(context: Context) {
        paused = true
        context.getSharedPreferences("phone-control", Context.MODE_PRIVATE).edit().putBoolean("paused", true).apply()
        PhoneControlHeal.paused(context)
        clearIndicator(context)
        // Paused: the ring over other apps goes with it.
        PhoneService.instance?.ringChanged()
    }
    fun clearIndicator(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(40)
    }
    private fun showIndicator(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("phone-control", "Agent phone control", NotificationManager.IMPORTANCE_DEFAULT))
        val stop = PendingIntent.getBroadcast(context, 0, Intent(context, StopPhoneControl::class.java), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(context, 0, Intent(context, PhoneControlActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(40, Notification.Builder(context, "phone-control")
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Agent phone control is available")
            .setContentText("Enabled automatically for phone tasks · Stop pauses access")
            .setOnlyAlertOnce(true).setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build())
    }
    fun requireAccess(context: Context) {
        PhoneControlPolicy.requireAllowed(
            connected = PhoneService.instance != null,
            paused = paused,
            locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked,
            awake = context.getSystemService(PowerManager::class.java).isInteractive,
        )
        showIndicator(context)
    }
}

class StopPhoneControl : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = PhoneAccess.stop(context)
}

class PhoneControlActivity : Activity() {
    private lateinit var status: android.widget.TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            status.text = when { PhoneService.instance == null -> "Enable Accessibility access below"; PhoneAccess.active -> "Ready · agents use phone controls automatically"; else -> "Paused · tap Resume to allow phone tasks again" }
            handler.postDelayed(this, 500)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(28, 48, 28, 28)
        }
        fun text(value: String) { layout.addView(android.widget.TextView(this).apply { text = value; textSize = 18f; setPadding(0, 12, 0, 18) }) }
        text("Agent phone control")
        text("Once Accessibility access is enabled, agents running in Omalauncher can read your screen, take screenshots, tap, swipe and enter text when you ask them to use your phone. No Start button or timer is needed. Screen content may reach your chosen AI provider. Keep the phone unlocked. Protected screens may be unavailable.")
        status = android.widget.TextView(this).apply { textSize = 20f }
        layout.addView(status)
        fun button(label: String, action: () -> Unit) { layout.addView(android.widget.Button(this).apply { text = label; setOnClickListener { action() } }) }
        button("Accessibility permission") { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("Resume if paused") { PhoneAccess.resume(this) }
        button("Pause phone control") { PhoneAccess.stop(this) }
        button("Back to launcher") { finish() }
        setContentView(layout)
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacksAndMessages(null); super.onPause() }
}
