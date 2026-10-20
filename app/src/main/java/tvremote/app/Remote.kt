package tvremote.app

import android.content.Context
import android.content.SharedPreferences
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tvremote.AuthenticationException
import tvremote.Button
import tvremote.Companion
import tvremote.CompanionException
import tvremote.Credentials
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit

private const val TAG = "tvremote"

/** Something the remote can drive: an Apple TV (Companion protocol) or a Linux box (HTTP). */
sealed interface Device { val name: String }
data class AppleTv(override val name: String, val host: String, val port: Int, val model: String) : Device
data class LinuxBox(override val name: String, val host: String, val port: Int) : Device

sealed interface Link {
    data object Idle : Link
    data object Connecting : Link
    data object Pairing : Link
    data object Connected : Link
    data class Failed(val message: String) : Link
}

class PinPrompt(val tv: String, private val answer: SynchronousQueue<String>) {
    fun reply(pin: String) {
        answer.offer(pin)
        Remote.pinPrompt = null
    }
}

/** App state and the single Apple TV connection; outlives activity recreation. */
object Remote {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor() // Companion calls block
    private lateinit var prefs: SharedPreferences
    private var tv: Companion? = null
    private val myName = Build.MODEL // shown on the TV under Remotes and Devices

    val tvs = mutableStateListOf<AppleTv>()
    val boxes = mutableStateListOf<LinuxBox>() // manually-added Linux devices
    var selected by mutableStateOf<Device?>(null)
        private set
    var link by mutableStateOf<Link>(Link.Idle)
        private set
    var pinPrompt by mutableStateOf<PinPrompt?>(null)

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("tvremote", Context.MODE_PRIVATE)
        prefs.getStringSet("boxes", emptySet())!!.forEach { s ->
            val p = s.split("|")
            if (p.size == 3) boxes.add(LinuxBox(p[0], p[1], p[2].toIntOrNull() ?: 8009))
        }
        boxes.sortBy { it.name }
        // Reselect a saved Linux box on launch; Apple TVs reselect once mDNS finds them.
        boxes.firstOrNull { it.name == prefs.getString("last", null) }?.let { select(it) }
    }

    private fun ui(block: () -> Unit) = main.post(block)

    fun found(t: AppleTv) = ui {
        tvs.removeAll { it.name == t.name }
        tvs.add(t)
        tvs.sortBy { it.name }
        if (selected == null && t.name == prefs.getString("last", null)) select(t)
        else if (selected is AppleTv && selected?.name == t.name && selected != t) selected = t // address changed
    }

    fun select(d: Device) {
        selected = d
        prefs.edit().putString("last", d.name).apply()
        when (d) {
            is AppleTv -> connect()
            is LinuxBox -> { worker.execute { tv?.close(); tv = null }; ui { link = Link.Connected } }
        }
    }

    fun addLinuxBox(name: String, host: String, port: Int = 8009) {
        val box = LinuxBox(name.ifBlank { host }, host, port)
        boxes.removeAll { it.name == box.name }
        boxes.add(box)
        boxes.sortBy { it.name }
        prefs.edit().putStringSet("boxes", boxes.map { "${it.name}|${it.host}|${it.port}" }.toSet()).apply()
        select(box)
    }

    fun removeLinuxBox(box: LinuxBox) {
        boxes.remove(box)
        prefs.edit().putStringSet("boxes", boxes.map { "${it.name}|${it.host}|${it.port}" }.toSet()).apply()
        if (selected == box) { selected = null; link = Link.Idle }
    }

    /** (Re)connect to the selected Apple TV, pairing first if it has never been paired. */
    fun connect() {
        val target = selected
        if (target !is AppleTv) return
        if (link == Link.Connecting || link == Link.Pairing) return
        link = Link.Connecting
        worker.execute { open(target) }
    }

    fun disconnect() = worker.execute {
        tv?.close(); tv = null
        // A Linux box has no held socket, so it stays "connected" across backgrounding.
        ui { if (selected is LinuxBox) link = Link.Connected else if (link == Link.Connected) link = Link.Idle }
    }

    fun press(b: Button) = hold(b, 0)

    /** Long-press: the TV needs to see the button held for about a second. */
    fun longPress(b: Button) = hold(b, 1000)

    private fun hold(b: Button, millis: Long) {
        when (val d = selected) {
            is LinuxBox -> worker.execute { sendKey(d, b, millis) }
            is AppleTv -> {
                if (tv?.isOpen != true) return connect()
                worker.execute {
                    val c = tv ?: return@execute
                    try {
                        c.hold(b, millis)
                    } catch (e: Exception) {
                        Log.i(TAG, "press $b failed: $e")
                    }
                }
            }
            null -> {}
        }
    }

    /** Fire-and-(mostly)-forget HTTP press to the Linux listener (linux/linuxremote.py). */
    private fun sendKey(box: LinuxBox, b: Button, millis: Long) {
        try {
            val url = java.net.URL("http://${box.host}:${box.port}/press?b=${b.name.lowercase()}&ms=$millis")
            (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 2000; readTimeout = 2000
                responseCode // sends the request
                disconnect()
            }
        } catch (e: Exception) {
            Log.i(TAG, "press $b to ${box.name} failed: $e")
        }
    }

    private fun open(target: AppleTv) {
        tv?.close(); tv = null
        try {
            var creds = prefs.getString(credKey(target), null)?.let(Credentials::parse)
            if (creds == null) {
                ui { link = Link.Pairing }
                creds = Companion(target.host, target.port, ::log).use { it.pair(myName) { askPin(target.name) } }
                prefs.edit().putString(credKey(target), creds.toString()).apply()
                ui { link = Link.Connecting }
            }
            val c = Companion(target.host, target.port, ::log)
            try {
                c.verify(creds)
            } catch (e: Exception) {
                c.close()
                // A rejection means the pairing was removed on the TV (Settings > Remotes and
                // Devices); forget it so the next attempt pairs again.
                if (e !is AuthenticationException) throw e
                prefs.edit().remove(credKey(target)).apply()
                throw CompanionException("This phone isn't paired with ${target.name} anymore. Tap to pair again.")
            }
            c.startSession(creds, myName)
            c.onClosed = { ui { if (tv === c) { tv = null; link = Link.Idle } } }
            tv = c
            ui { link = Link.Connected }
        } catch (e: Exception) {
            Log.i(TAG, "connect to ${target.name} failed: $e")
            val message = when {
                e.message == "cancelled" -> null
                e.message == "wrong PIN" -> "That code didn't match. Tap the pad to try again."
                e is java.net.ConnectException || e is java.net.SocketTimeoutException ->
                    "Couldn't reach ${target.name}. Check that it's on and on this Wi-Fi network."
                else -> e.message ?: e.toString()
            }
            ui { link = if (message == null) Link.Idle else Link.Failed(message) }
        }
    }

    private fun credKey(t: AppleTv) = "creds:${t.name}"

    /** Blocks the worker until the user types the PIN shown on the TV. */
    private fun askPin(tvName: String): String {
        val answer = SynchronousQueue<String>()
        ui { pinPrompt = PinPrompt(tvName, answer) }
        val pin = answer.poll(2, TimeUnit.MINUTES) ?: ""
        if (pin.isEmpty()) { ui { pinPrompt = null }; throw CompanionException("cancelled") }
        return pin
    }

    private fun log(msg: String) { Log.d(TAG, msg) }
}

/**
 * Finds Apple TVs advertising the Companion service. Resolves one at a time: before
 * Android 14, NsdManager rejects concurrent resolves.
 */
class Discovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(s: NsdServiceInfo) = enqueue(s)
        override fun onServiceLost(s: NsdServiceInfo) {}
        override fun onDiscoveryStarted(t: String) {}
        override fun onDiscoveryStopped(t: String) {}
        override fun onStartDiscoveryFailed(t: String, code: Int) {}
        override fun onStopDiscoveryFailed(t: String, code: Int) {}
    }

    fun start() = nsd.discoverServices("_companion-link._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
    fun stop() { runCatching { nsd.stopServiceDiscovery(listener) } }

    @Synchronized private fun enqueue(s: NsdServiceInfo) {
        queue.addLast(s)
        if (!resolving) next()
    }

    @Synchronized private fun next() {
        val s = queue.removeFirstOrNull() ?: run { resolving = false; return }
        resolving = true
        @Suppress("DEPRECATION")
        nsd.resolveService(s, object : NsdManager.ResolveListener {
            override fun onServiceResolved(r: NsdServiceInfo) {
                val model = r.attributes["rpMd"]?.let { String(it) } ?: ""
                @Suppress("DEPRECATION")
                val host = r.host?.hostAddress
                // Macs and HomePods advertise the Companion service too; only Apple TVs take remote input.
                if (host != null && model.startsWith("AppleTV")) Remote.found(AppleTv(r.serviceName, host, r.port, model))
                next()
            }
            override fun onResolveFailed(s: NsdServiceInfo, code: Int) = next()
        })
    }
}
