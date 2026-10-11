package tvremote

import java.io.ByteArrayOutputStream

/** HomeKit TLV8: one-byte tag, one-byte length; values over 255 bytes are split across repeated tags. */
object Tlv8 {
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val SEQ_NO = 0x06
    const val ERROR = 0x07
    const val BACK_OFF = 0x08
    const val SIGNATURE = 0x0A
    const val NAME = 0x11

    fun encode(vararg items: Pair<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((tag, value) in items) {
            var pos = 0
            do {
                val n = minOf(255, value.size - pos)
                out.write(tag); out.write(n); out.write(value, pos, n)
                pos += n
            } while (pos < value.size)
        }
        return out.toByteArray()
    }

    fun decode(data: ByteArray): Map<Int, ByteArray> {
        val out = LinkedHashMap<Int, ByteArray>()
        var pos = 0
        while (pos + 2 <= data.size) {
            val tag = data[pos].toInt() and 0xFF
            val n = data[pos + 1].toInt() and 0xFF
            val value = data.copyOfRange(pos + 2, pos + 2 + n)
            out[tag] = out[tag]?.plus(value) ?: value
            pos += 2 + n
        }
        return out
    }
}
