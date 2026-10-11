package tvremote

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Long-term pairing with one Apple TV. Serialized as "ltpk:ltsk:atvId:clientId" in hex,
 * the same shape pyatv uses for Companion credentials.
 */
data class Credentials(val atvPublicKey: ByteArray, val ourSeed: ByteArray, val atvId: ByteArray, val clientId: ByteArray) {
    override fun toString() = listOf(atvPublicKey, ourSeed, atvId, clientId).joinToString(":") { it.hex() }

    companion object {
        fun parse(s: String): Credentials {
            val p = s.trim().split(":").map { it.unhex() }
            require(p.size == 4) { "malformed credentials" }
            return Credentials(p[0], p[1], p[2], p[3])
        }
    }
}

/** Remote buttons (Companion `_hidC` codes, as found by pyatv). */
enum class Button(val code: Int) {
    Up(1), Down(2), Left(3), Right(4), Menu(5), Select(6), Home(7),
    VolumeUp(8), VolumeDown(9), Siri(10), Screensaver(11), Sleep(12), Wake(13), PlayPause(14),
}

class CompanionException(message: String) : IOException(message)

/**
 * A Companion protocol connection to an Apple TV (the protocol behind the iPhone's Apple TV
 * remote). Frames are a 1-byte type and 3-byte big-endian length; after pair-verify every
 * payload is ChaCha20-Poly1305 encrypted with the header as associated data.
 */
class Companion(host: String, port: Int, private val log: (String) -> Unit = {}) : Closeable {
    private val socket = Socket().apply { tcpNoDelay = true; connect(InetSocketAddress(host, port), 5000) }
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = socket.getOutputStream()
    private val pending = ConcurrentHashMap<Any, CompletableFuture<Map<*, *>>>()
    private var xid = random.nextInt(0x10000)

    @Volatile private var outKey: ByteArray? = null
    @Volatile private var inKey: ByteArray? = null
    @Volatile private var nextInKey: ByteArray? = null
    private var outCounter = 0L
    private var inCounter = 0L

    /** Events pushed by the device (`_t` = 1), keyed by event name. */
    var onEvent: (String, Map<*, *>) -> Unit = { _, _ -> }

    private val reader = Thread(::readLoop, "companion-reader").apply { isDaemon = true; start() }

    // --- pairing ---

    /**
     * One-time pair-setup. The TV shows a PIN once [Companion] sends the first message;
     * [pin] is called to collect it. Reconnect and call [verify] afterwards.
     */
    fun pair(name: String, pin: () -> String): Credentials {
        val m2 = auth(PS_START, PS_NEXT, Tlv8.encode(Tlv8.METHOD to byteArrayOf(0), Tlv8.SEQ_NO to byteArrayOf(1)), "_pwTy" to 1)
        val srp = SrpClient(pin(), m2.need(Tlv8.SALT), m2.need(Tlv8.PUBLIC_KEY))

        val m4 = auth(PS_NEXT, PS_NEXT, Tlv8.encode(Tlv8.SEQ_NO to byteArrayOf(3), Tlv8.PUBLIC_KEY to srp.publicKey, Tlv8.PROOF to srp.proof), "_pwTy" to 1)
        if (!srp.verifyServer(m4.need(Tlv8.PROOF))) throw CompanionException("Apple TV proof mismatch")

        val seed = randomBytes(32)
        val ourPub = Ed.publicKey(seed)
        val clientId = UUID.randomUUID().toString().uppercase().toByteArray()
        val signKey = hkdf("Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info", srp.sessionKey)
        val encKey = hkdf("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", srp.sessionKey)
        val inner = Tlv8.encode(
            Tlv8.IDENTIFIER to clientId,
            Tlv8.PUBLIC_KEY to ourPub,
            Tlv8.SIGNATURE to Ed.sign(seed, signKey + clientId + ourPub),
            Tlv8.NAME to Opack.pack(mapOf("name" to name)),
        )
        val m5 = ChaCha.seal(encKey, "PS-Msg05".toByteArray(), inner)
        val m6 = auth(PS_NEXT, PS_NEXT, Tlv8.encode(Tlv8.SEQ_NO to byteArrayOf(5), Tlv8.ENCRYPTED_DATA to m5), "_pwTy" to 1)

        val atv = Tlv8.decode(ChaCha.open(encKey, "PS-Msg06".toByteArray(), m6.need(Tlv8.ENCRYPTED_DATA)))
        val atvId = atv.need(Tlv8.IDENTIFIER)
        val atvPub = atv.need(Tlv8.PUBLIC_KEY)
        val atvSignKey = hkdf("Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info", srp.sessionKey)
        // The SRP proofs already authenticated the TV via the PIN; pyatv skips this check entirely.
        if (!Ed.verify(atvPub, atvSignKey + atvId + atvPub, atv.need(Tlv8.SIGNATURE))) {
            log("warning: Apple TV pair-setup signature did not verify")
        }
        return Credentials(atvPub, seed, atvId, clientId)
    }

    /** Pair-verify with saved credentials, then switch the connection to encrypted frames. */
    fun verify(creds: Credentials) {
        val ourPriv = randomBytes(32)
        val ourPub = Curve.publicKey(ourPriv)
        val m2 = auth(PV_START, PV_NEXT, Tlv8.encode(Tlv8.SEQ_NO to byteArrayOf(1), Tlv8.PUBLIC_KEY to ourPub), "_auTy" to 4)
        val atvPub = m2.need(Tlv8.PUBLIC_KEY)
        val shared = Curve.shared(ourPriv, atvPub)
        val key = hkdf("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", shared)

        val atv = Tlv8.decode(ChaCha.open(key, "PV-Msg02".toByteArray(), m2.need(Tlv8.ENCRYPTED_DATA)))
        val atvId = atv.need(Tlv8.IDENTIFIER)
        if (!atvId.contentEquals(creds.atvId)) throw CompanionException("connected to a different Apple TV")
        if (!Ed.verify(creds.atvPublicKey, atvPub + atvId + ourPub, atv.need(Tlv8.SIGNATURE))) {
            throw CompanionException("Apple TV signature invalid")
        }
        val proof = Tlv8.encode(
            Tlv8.IDENTIFIER to creds.clientId,
            Tlv8.SIGNATURE to Ed.sign(creds.ourSeed, ourPub + creds.clientId + atvPub),
        )
        // The TV may send encrypted frames right after its final reply, so the reader switches
        // to decryption itself once that reply has been read.
        nextInKey = hkdf("", "ServerEncrypt-main", shared)
        auth(PV_NEXT, PV_NEXT, Tlv8.encode(Tlv8.SEQ_NO to byteArrayOf(3), Tlv8.ENCRYPTED_DATA to ChaCha.seal(key, "PV-Msg03".toByteArray(), proof)))
        outKey = hkdf("", "ClientEncrypt-main", shared)
    }

    // --- session and commands ---

    /** The messages the iPhone remote sends after pair-verify, before any button works. */
    fun startSession(creds: Credentials, name: String) {
        val id = creds.clientId
        request("_systemInfo", mapOf(
            "_bf" to 0, "_cf" to 512, "_clFl" to 128,
            "_i" to sha512(id).copyOf(6).hex(), // stable per pairing
            "_idsID" to id,
            "_pubID" to sha512(id).copyOf(6).joinToString(":") { "%02X".format(it) },
            "_sf" to 256, "_sv" to "170.18",
            "model" to "iPhone10,6", "name" to name,
        ))
        request("_touchStart", mapOf("_height" to 1000.0, "_tFl" to 0, "_width" to 1000.0))
        request("_sessionStart", mapOf("_srvT" to "com.apple.tvremoteservices", "_sid" to (random.nextInt().toLong() and 0xFFFFFFFFL)))
        runCatching { request("TVRCSessionStart", mapOf("ProtocolVersionKey" to "1.2")) }
            .onFailure { log("TVRCSessionStart not supported: ${it.message}") }
    }

    fun press(button: Button) {
        request("_hidC", mapOf("_hBtS" to 1, "_hidC" to button.code))
        request("_hidC", mapOf("_hBtS" to 2, "_hidC" to button.code))
    }

    /** Sends a request (`_t` = 2) and waits for its response (`_t` = 3, matched by `_x`). */
    fun request(id: String, content: Map<String, Any?>): Map<*, *> {
        val x = synchronized(this) { xid++ }
        val reply = expect(x)
        send(E_OPACK, Opack.pack(mapOf("_i" to id, "_t" to 2, "_c" to content, "_x" to x)))
        val resp = reply.await(id)
        resp["_em"]?.let { throw CompanionException("$id failed: $it") }
        return resp
    }

    override fun close() {
        socket.close()
        reader.join(1000)
    }

    // --- framing ---

    private fun auth(type: Int, replyType: Int, tlv: ByteArray, vararg extra: Pair<String, Any>): Map<Int, ByteArray> {
        val reply = expect("auth$replyType") // auth replies carry no _x
        send(type, Opack.pack(mapOf("_pd" to tlv, *extra)))
        val data = reply.await("pairing step")["_pd"] as? ByteArray ?: throw CompanionException("no pairing data in reply")
        val decoded = Tlv8.decode(data)
        decoded[Tlv8.ERROR]?.let { throw CompanionException(pairingError(it, decoded)) }
        return decoded
    }

    private fun expect(key: Any) = CompletableFuture<Map<*, *>>().also { pending[key] = it }

    private fun CompletableFuture<Map<*, *>>.await(what: String): Map<*, *> = try {
        get(10, TimeUnit.SECONDS)
    } catch (e: java.util.concurrent.TimeoutException) {
        throw CompanionException("no reply to $what")
    } catch (e: java.util.concurrent.ExecutionException) {
        throw e.cause ?: e
    }

    @Synchronized
    private fun send(type: Int, payload: ByteArray) {
        val key = outKey
        val len = payload.size + if (key != null && payload.isNotEmpty()) 16 else 0
        val header = byteArrayOf(type.toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte())
        val body = if (key != null && payload.isNotEmpty()) ChaCha.seal(key, counterNonce(outCounter++), payload, header) else payload
        output.write(header + body)
        output.flush()
    }

    private fun readLoop() {
        try {
            while (true) {
                val header = ByteArray(4).also(input::readFully)
                val len = ((header[1].toInt() and 0xFF) shl 16) or ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
                var payload = ByteArray(len).also(input::readFully)
                val key = inKey
                if (key != null && payload.isNotEmpty()) payload = ChaCha.open(key, counterNonce(inCounter++), payload, header)
                val type = header[0].toInt() and 0xFF
                runCatching { dispatch(type, payload) }.onFailure { log("bad frame type $type: $it") }
                if (type == PV_NEXT) nextInKey?.let { inKey = it; nextInKey = null }
            }
        } catch (e: Exception) {
            val err = if (e is IOException) e else CompanionException("connection failed: $e")
            pending.values.forEach { it.completeExceptionally(err) }
            pending.clear()
        }
    }

    private fun dispatch(type: Int, payload: ByteArray) {
        if (type !in listOf(PS_NEXT, PV_NEXT, U_OPACK, E_OPACK, P_OPACK)) return log("ignoring frame type $type")
        val msg = Opack.unpack(payload) as? Map<*, *> ?: return
        when {
            type == PS_NEXT || type == PV_NEXT -> pending.remove("auth$type")?.complete(msg)
            msg["_t"] == 1L -> onEvent(msg["_i"] as? String ?: "", msg["_c"] as? Map<*, *> ?: emptyMap<Any, Any>())
            msg["_t"] == 3L -> (msg["_x"] as? Long)?.let { pending.remove(it.toInt()) }?.complete(msg)
            else -> log("unhandled message: $msg")
        }
    }

    companion object {
        private const val PS_START = 3
        private const val PS_NEXT = 4
        private const val PV_START = 5
        private const val PV_NEXT = 6
        private const val U_OPACK = 7
        private const val E_OPACK = 8
        private const val P_OPACK = 9

        /** Frame nonces are the message counter, little-endian, in a 12-byte nonce. */
        private fun counterNonce(n: Long) = ByteArray(12).also { for (i in 0 until 8) it[i] = (n ushr (8 * i)).toByte() }

        private fun pairingError(code: ByteArray, tlv: Map<Int, ByteArray>): String = when (code[0].toInt()) {
            2 -> "wrong PIN"
            3 -> "too many attempts; try again in ${tlv[Tlv8.BACK_OFF]?.let { leInt(it) } ?: "a few"} seconds"
            4 -> "the Apple TV has too many paired devices"
            5 -> "too many failed attempts"
            6, 7 -> "the Apple TV is busy pairing with another device"
            else -> "pairing error ${code[0]}"
        }

        private fun leInt(b: ByteArray) = b.foldIndexed(0) { i, acc, x -> acc or ((x.toInt() and 0xFF) shl (8 * i)) }
    }
}

private fun Map<Int, ByteArray>.need(tag: Int) = this[tag] ?: throw CompanionException("pairing reply missing TLV $tag")

internal fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
internal fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
