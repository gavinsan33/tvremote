package tvremote

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * OPACK, Apple's compact binary serialization used by the Companion protocol.
 * Format as documented by pyatv (support/opack.py, MIT).
 *
 * Kotlin types: null, Boolean, Int/Long (decoded as Long), Double (Float decodes to Double),
 * String, ByteArray, UUID, List<*>, Map<*, *>. Packing never emits back-references, which
 * is a valid (just less compact) encoding; unpacking resolves them.
 */
object Opack {
    fun pack(value: Any?): ByteArray = ByteArrayOutputStream().also { write(it, value) }.toByteArray()

    fun unpack(data: ByteArray): Any? = Reader(data).read()

    private fun write(out: ByteArrayOutputStream, value: Any?) {
        when (value) {
            null -> out.write(0x04)
            is Boolean -> out.write(if (value) 0x01 else 0x02)
            is UUID -> {
                out.write(0x05)
                out.write(ByteBuffer.allocate(16).putLong(value.mostSignificantBits).putLong(value.leastSignificantBits).array())
            }
            is Int -> writeInt(out, value.toLong())
            is Long -> writeInt(out, value)
            is Double -> { out.write(0x36); out.write(le(8).putDouble(value).array()) }
            is Float -> { out.write(0x36); out.write(le(8).putDouble(value.toDouble()).array()) }
            is String -> writeSized(out, value.toByteArray(Charsets.UTF_8), small = 0x40, sized = 0x60)
            is ByteArray -> writeSized(out, value, small = 0x70, sized = 0x90)
            is List<*> -> {
                out.write(0xD0 + minOf(value.size, 0xF))
                value.forEach { write(out, it) }
                if (value.size >= 0xF) out.write(0x03)
            }
            is Map<*, *> -> {
                out.write(0xE0 + minOf(value.size, 0xF))
                value.forEach { (k, v) -> write(out, k); write(out, v) }
                if (value.size >= 0xF) out.write(0x03)
            }
            else -> throw IllegalArgumentException("OPACK can't encode ${value::class}")
        }
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Long) {
        require(v >= 0) { "OPACK negative integers are not supported" }
        when {
            v < 0x28 -> out.write((v + 8).toInt())
            v <= 0xFF -> { out.write(0x30); out.write(v.toInt()) }
            v <= 0xFFFF -> { out.write(0x31); out.write(le(2).putShort(v.toShort()).array()) }
            v <= 0xFFFFFFFFL -> { out.write(0x32); out.write(le(4).putInt(v.toInt()).array()) }
            else -> { out.write(0x33); out.write(le(8).putLong(v).array()) }
        }
    }

    // Strings and data: lengths up to 0x20 are in the tag, longer ones get a 1/2/3/4-byte length.
    private fun writeSized(out: ByteArrayOutputStream, b: ByteArray, small: Int, sized: Int) {
        val n = b.size
        if (n <= 0x20) {
            out.write(small + n)
        } else {
            val lenBytes = when {
                n <= 0xFF -> 1
                n <= 0xFFFF -> 2
                small == 0x40 && n <= 0xFFFFFF -> 3 // strings have a 3-byte form, data doesn't
                else -> 4
            }
            // Data tags count length bytes as 1,2,4,8 -> 0x91..0x94; strings 1..4 -> 0x61..0x64.
            out.write(sized + if (small == 0x70) Integer.numberOfTrailingZeros(lenBytes) + 1 else lenBytes)
            for (i in 0 until lenBytes) out.write((n ushr (8 * i)) and 0xFF)
        }
        out.write(b)
    }

    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    private class Reader(val d: ByteArray) {
        var pos = 0
        val objects = mutableListOf<Any?>() // targets for back-references

        fun read(): Any? {
            val tag = u8()
            var keep = true
            val value: Any? = when {
                tag == 0x01 -> { keep = false; true }
                tag == 0x02 -> { keep = false; false }
                tag == 0x04 -> { keep = false; null }
                tag == 0x05 -> { val b = ByteBuffer.wrap(take(16)); UUID(b.long, b.long) }
                tag == 0x06 -> leLong(8) // absolute time, kept as raw integer
                tag in 0x08..0x2F -> { keep = false; (tag - 8).toLong() }
                tag == 0x35 -> le(take(4)).float.toDouble()
                tag == 0x36 -> le(take(8)).double
                (tag and 0xF0) == 0x30 -> leLong(1 shl (tag and 0xF))
                tag in 0x40..0x60 -> String(take(tag - 0x40), Charsets.UTF_8)
                tag in 0x61..0x64 -> String(take(leLong(tag and 0xF).toInt()), Charsets.UTF_8)
                tag in 0x70..0x90 -> take(tag - 0x70)
                tag in 0x91..0x94 -> take(leLong(1 shl ((tag and 0xF) - 1)).toInt())
                (tag and 0xF0) == 0xD0 -> { keep = false; readList(tag and 0xF) }
                (tag and 0xF0) == 0xE0 -> { keep = false; readMap(tag and 0xF) }
                tag in 0xA0..0xC0 -> { keep = false; objects[tag - 0xA0] }
                tag in 0xC1..0xC4 -> { keep = false; objects[leLong(tag - 0xC0).toInt()] }
                else -> throw IllegalArgumentException("OPACK: unknown tag 0x%02x".format(tag))
            }
            if (keep && objects.none { same(it, value) }) objects.add(value)
            return value
        }

        private fun readList(count: Int): List<Any?> {
            val out = mutableListOf<Any?>()
            if (count == 0xF) { while (peek() != 0x03) out.add(read()); pos++ } else repeat(count) { out.add(read()) }
            return out
        }

        private fun readMap(count: Int): Map<Any?, Any?> {
            val out = LinkedHashMap<Any?, Any?>()
            if (count == 0xF) {
                while (peek() != 0x03) { val k = read(); out[k] = read() }
                pos++
            } else repeat(count) { val k = read(); out[k] = read() }
            return out
        }

        private fun same(a: Any?, b: Any?) =
            if (a is ByteArray && b is ByteArray) a.contentEquals(b) else a == b

        private fun peek() = d[pos].toInt() and 0xFF
        private fun u8() = d[pos++].toInt() and 0xFF
        private fun take(n: Int) = d.copyOfRange(pos, pos + n).also { pos += n }
        private fun leLong(n: Int): Long {
            var v = 0L
            for (i in 0 until n) v = v or ((d[pos + i].toLong() and 0xFF) shl (8 * i))
            pos += n
            return v
        }
        private fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    }
}
