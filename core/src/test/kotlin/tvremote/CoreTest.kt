package tvremote

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
private fun s(str: String) = str.toByteArray()

class OpackTest {
    // Vectors from pyatv's tests/support/test_opack.py.
    @Test fun packScalars() {
        assertContentEquals(b(0x01), Opack.pack(true))
        assertContentEquals(b(0x04), Opack.pack(null))
        assertContentEquals(b(0x2f), Opack.pack(0x27))
        assertContentEquals(b(0x30, 0x28), Opack.pack(0x28))
        assertContentEquals(b(0x31, 0xff, 0x01), Opack.pack(0x1FF))
        assertContentEquals(b(0x32, 0xff, 0xff, 0xff, 0x01), Opack.pack(0x1FFFFFF))
        assertContentEquals(b(0x33, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01), Opack.pack(0x1FFFFFFFFFFFFFFL))
        assertContentEquals(b(0x36, 0, 0, 0, 0, 0, 0, 0xf0, 0x3f), Opack.pack(1.0))
        assertContentEquals(b(0x43) + s("abc"), Opack.pack("abc"))
        assertContentEquals(b(0x60) + s("a".repeat(32)), Opack.pack("a".repeat(32)))
        assertContentEquals(b(0x61, 0x21) + s("a".repeat(33)), Opack.pack("a".repeat(33)))
        assertContentEquals(b(0x62, 0x00, 0x01) + s("a".repeat(256)), Opack.pack("a".repeat(256)))
        assertContentEquals(b(0x73, 0x12, 0x34, 0x56), Opack.pack(b(0x12, 0x34, 0x56)))
        assertContentEquals(b(0x91, 0x21) + ByteArray(33) { 0x61 }, Opack.pack(ByteArray(33) { 0x61 }))
        assertContentEquals(b(0x93, 0x00, 0x00, 0x01, 0x00) + ByteArray(65536) { 0x61 }, Opack.pack(ByteArray(65536) { 0x61 }))
    }

    @Test fun packCollections() {
        assertContentEquals(b(0xd3, 0x09, 0x44) + s("test") + b(0x02), Opack.pack(listOf(1, "test", false)))
        assertContentEquals(b(0xe2, 0x41, 0x61, 0x14, 0x02, 0x04), Opack.pack(mapOf("a" to 12, false to null)))
        val fifteen = Opack.pack(List(15) { "a" }) // no back-references when packing
        assertEquals(0xdf, fifteen[0].toInt() and 0xFF)
        assertEquals(0x03, fifteen.last().toInt())
    }

    @Test fun unpack() {
        assertEquals(listOf(1L, "test", false), Opack.unpack(b(0xd3, 0x09, 0x44) + s("test") + b(0x02)))
        assertEquals(mapOf("a" to 12L, false to null), Opack.unpack(b(0xe2, 0x41, 0x61, 0x14, 0x02, 0x04)))
        assertEquals(0x1FFL, Opack.unpack(b(0x31, 0xff, 0x01)))
        assertEquals(1.0, Opack.unpack(b(0x35, 0x00, 0x00, 0x80, 0x3f)))
        assertEquals("a".repeat(33), Opack.unpack(b(0x61, 0x21) + s("a".repeat(33))))
        assertEquals(List(16) { "a" }, Opack.unpack(b(0xdf, 0x41, 0x61) + ByteArray(15) { 0xa0.toByte() } + b(0x03)))
    }

    @Test fun unpackBackReferences() {
        assertEquals(listOf("foo", "bar", "foo", "bar"), Opack.unpack(b(0xd4, 0x43) + s("foo") + b(0x43) + s("bar") + b(0xa0, 0xa1)))
        assertEquals(
            mapOf("a" to "b", "c" to mapOf("d" to "a"), "d" to true),
            Opack.unpack(b(0xe3, 0x41, 0x61, 0x41, 0x62, 0x41, 0x63, 0xe1, 0x41, 0x64, 0xa0, 0xa3, 0x01)),
        )
    }

    @Test fun roundTripMessage() {
        val msg = mapOf("_i" to "_hidC", "_t" to 2, "_c" to mapOf("_hBtS" to 1, "_hidC" to 6), "_x" to 40000)
        val back = Opack.unpack(Opack.pack(msg)) as Map<*, *>
        assertEquals("_hidC", back["_i"]); assertEquals(40000L, back["_x"])
        assertEquals(mapOf("_hBtS" to 1L, "_hidC" to 6L), back["_c"])
    }
}

class Tlv8Test {
    @Test fun splitsLongValues() {
        val long = ByteArray(300) { it.toByte() }
        val enc = Tlv8.encode(Tlv8.SEQ_NO to b(1), Tlv8.PUBLIC_KEY to long)
        assertEquals(2 + 1 + 2 + 255 + 2 + 45, enc.size)
        val dec = Tlv8.decode(enc)
        assertContentEquals(b(1), dec[Tlv8.SEQ_NO]); assertContentEquals(long, dec[Tlv8.PUBLIC_KEY])
    }
}

class CryptoTest {
    // RFC 8439 section 2.8.2 AEAD test vector.
    @Test fun chachaRfc8439() {
        val key = ByteArray(32) { (0x80 + it).toByte() }
        val nonce = b(0x07, 0, 0, 0, 0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47)
        val aad = b(0x50, 0x51, 0x52, 0x53, 0xc0, 0xc1, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7)
        val plain = s("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.")
        val sealed = ChaCha.seal(key, nonce, plain, aad)
        assertEquals("d31a8d34648e60db7b86afbc53ef7ec2", sealed.copyOf(16).hex())
        assertEquals("1ae10b594f09e26a7e902ecbd0600691", sealed.copyOfRange(sealed.size - 16, sealed.size).hex())
        assertContentEquals(plain, ChaCha.open(key, nonce, sealed, aad))
    }

    @Test fun ed25519AndX25519() {
        val seed = randomBytes(32)
        val sig = Ed.sign(seed, s("hello"))
        assertTrue(Ed.verify(Ed.publicKey(seed), s("hello"), sig))
        val a = randomBytes(32); val c = randomBytes(32)
        assertContentEquals(Curve.shared(a, Curve.publicKey(c)), Curve.shared(c, Curve.publicKey(a)))
    }

    /** Plays the accessory side of SRP-6a and checks both sides agree on key and proofs. */
    @Test fun srpAgreesWithServer() {
        val n = SrpClient.N; val g = SrpClient.G
        val salt = randomBytes(16); val pin = "1234"
        val x = BigInteger(1, sha512(salt, sha512(s("Pair-Setup"), s(":"), s(pin))))
        val v = g.modPow(x, n)
        val k = BigInteger(1, sha512(SrpClient.pad(n), SrpClient.pad(g)))
        val bPriv = BigInteger(1, randomBytes(32))
        val bPub = k.multiply(v).add(g.modPow(bPriv, n)).mod(n)

        val client = SrpClient(pin, salt, SrpClient.bytes(bPub))
        val aPub = BigInteger(1, client.publicKey)
        val u = BigInteger(1, sha512(SrpClient.pad(aPub), SrpClient.pad(bPub)))
        val serverS = aPub.multiply(v.modPow(u, n)).mod(n).modPow(bPriv, n)
        val serverK = sha512(SrpClient.bytes(serverS))
        assertContentEquals(serverK, client.sessionKey)
        assertTrue(client.verifyServer(sha512(SrpClient.bytes(aPub), client.proof, serverK)))
    }
}
