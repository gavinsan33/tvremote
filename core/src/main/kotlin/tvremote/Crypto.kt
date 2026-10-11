package tvremote

import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

internal val random = SecureRandom()

internal fun randomBytes(n: Int) = ByteArray(n).also(random::nextBytes)

internal fun sha512(vararg parts: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-512").run { parts.forEach(::update); digest() }

/** HKDF-SHA512 with a 32-byte output, as used throughout HomeKit pairing. */
internal fun hkdf(salt: String, info: String, secret: ByteArray): ByteArray {
    val gen = HKDFBytesGenerator(SHA512Digest())
    gen.init(HKDFParameters(secret, salt.toByteArray(), info.toByteArray()))
    return ByteArray(32).also { gen.generateBytes(it, 0, 32) }
}

/** ChaCha20-Poly1305 with a 12-byte nonce. Shorter nonces are left-padded with zeros. */
internal object ChaCha {
    fun seal(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray? = null) =
        run(true, key, nonce, plain, aad)

    /** Throws [org.bouncycastle.crypto.InvalidCipherTextException] if authentication fails. */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray? = null) =
        run(false, key, nonce, sealed, aad)

    private fun run(encrypt: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray, aad: ByteArray?): ByteArray {
        val n = ByteArray(12 - nonce.size) + nonce
        val c = ChaCha20Poly1305()
        c.init(encrypt, AEADParameters(KeyParameter(key), 128, n, aad))
        val out = ByteArray(c.getOutputSize(input.size))
        val len = c.processBytes(input, 0, input.size, out, 0)
        c.doFinal(out, len)
        return out
    }
}

internal object Ed {
    fun publicKey(seed: ByteArray) = ByteArray(32).also { Ed25519.generatePublicKey(seed, 0, it, 0) }
    fun sign(seed: ByteArray, msg: ByteArray) =
        ByteArray(64).also { Ed25519.sign(seed, 0, msg, 0, msg.size, it, 0) }
    fun verify(pub: ByteArray, msg: ByteArray, sig: ByteArray) =
        sig.size == 64 && Ed25519.verify(sig, 0, pub, 0, msg, 0, msg.size)
}

internal object Curve {
    fun publicKey(priv: ByteArray) = ByteArray(32).also { X25519.scalarMultBase(priv, 0, it, 0) }
    fun shared(priv: ByteArray, peer: ByteArray): ByteArray {
        val out = ByteArray(32)
        require(X25519.calculateAgreement(priv, 0, peer, 0, out, 0)) { "invalid X25519 peer key" }
        return out
    }
}

/**
 * SRP-6a client for HomeKit pair-setup: 3072-bit RFC 5054 group, g = 5, SHA-512, user "Pair-Setup".
 * Byte layout (which values are padded) matches doubletake's AirPlay implementation, which is
 * known to work against Apple TVs: k and u hash padded values, K and the proofs use unpadded ones.
 */
internal class SrpClient(pin: String, salt: ByteArray, serverPublic: ByteArray, a: BigInteger = BigInteger(1, randomBytes(32))) {
    val publicKey: ByteArray
    val proof: ByteArray
    val sessionKey: ByteArray
    private val expectedServerProof: ByteArray

    init {
        val user = "Pair-Setup".toByteArray()
        val x = int(sha512(salt, sha512(user, ":".toByteArray(), pin.toByteArray())))
        val k = int(sha512(pad(N), pad(G)))
        val aPub = G.modPow(a, N)
        val b = int(serverPublic)
        require(b.signum() > 0 && b < N) { "invalid SRP server public key" }
        val u = int(sha512(pad(aPub), pad(b)))
        val s = b.subtract(k.multiply(G.modPow(x, N)).mod(N)).mod(N).modPow(a.add(u.multiply(x)), N)

        sessionKey = sha512(bytes(s))
        val hN = sha512(bytes(N))
        val hG = sha512(bytes(G))
        val hxor = ByteArray(64) { (hN[it].toInt() xor hG[it].toInt()).toByte() }
        proof = sha512(hxor, sha512(user), salt, bytes(aPub), bytes(b), sessionKey)
        expectedServerProof = sha512(bytes(aPub), proof, sessionKey)
        publicKey = pad(aPub)
    }

    fun verifyServer(serverProof: ByteArray) = MessageDigest.isEqual(serverProof, expectedServerProof)

    companion object {
        val N = BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
                "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
                "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
                "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
                "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
                "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
            16,
        )
        val G: BigInteger = BigInteger.valueOf(5)

        private fun int(b: ByteArray) = BigInteger(1, b)

        /** Big-endian magnitude without the sign byte BigInteger sometimes adds. */
        fun bytes(v: BigInteger): ByteArray = v.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }

        fun pad(v: BigInteger): ByteArray = bytes(v).let { ByteArray(384 - it.size) + it }
    }
}
