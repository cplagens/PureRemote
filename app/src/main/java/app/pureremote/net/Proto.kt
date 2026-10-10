package app.pureremote.net

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** Minimaler Protobuf-Encoder (Varint + length-delimited reicht für das Remote-Protokoll). */
class PbWriter {
    private val out = ByteArrayOutputStream()
    fun int(field: Int, v: Int) = apply { tag(field, 0); varint(v.toLong()) }
    fun bytes(field: Int, b: ByteArray) = apply { tag(field, 2); varint(b.size.toLong()); out.write(b) }
    fun string(field: Int, s: String) = bytes(field, s.toByteArray())
    fun msg(field: Int, w: PbWriter) = bytes(field, w.toByteArray())
    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(f: Int, wireType: Int) = varint(((f shl 3) or wireType).toLong())
    private fun varint(v: Long) {
        var x = v
        while ((x and 0x7FL.inv()) != 0L) {
            out.write(((x and 0x7FL) or 0x80L).toInt())
            x = x ushr 7
        }
        out.write(x.toInt())
    }
}

/** Minimaler Protobuf-Decoder: Feldnummer -> Werte. */
class PbMsg private constructor(private val f: Map<Int, List<Any>>) {
    fun has(n: Int) = f.containsKey(n)
    fun int(n: Int): Int? = (f[n]?.firstOrNull() as? Long)?.toInt()
    fun bytes(n: Int): ByteArray? = f[n]?.firstOrNull() as? ByteArray
    fun msg(n: Int): PbMsg? = bytes(n)?.let { parse(it) }

    companion object {
        fun parse(d: ByteArray): PbMsg {
            val m = mutableMapOf<Int, MutableList<Any>>()
            var p = 0
            fun varint(): Long {
                var r = 0L; var s = 0
                while (true) {
                    val b = d[p++].toInt()
                    r = r or ((b and 0x7F).toLong() shl s)
                    if ((b and 0x80) == 0) break
                    s += 7
                }
                return r
            }
            while (p < d.size) {
                val t = varint().toInt()
                val v: Any = when (t and 7) {
                    0 -> varint()
                    2 -> { val l = varint().toInt(); d.copyOfRange(p, p + l).also { p += l } }
                    1 -> { p += 8; 0L }
                    5 -> { p += 4; 0L }
                    else -> error("Unbekannter Wire-Type")
                }
                m.getOrPut(t shr 3) { mutableListOf() }.add(v)
            }
            return PbMsg(m)
        }
    }
}

/** Nachrichten sind mit einem Varint-Längenpräfix gerahmt. */
fun OutputStream.writeFrame(b: ByteArray) {
    val h = ByteArrayOutputStream()
    var x = b.size
    while (x > 0x7F) { h.write((x and 0x7F) or 0x80); x = x ushr 7 }
    h.write(x)
    write(h.toByteArray() + b)
    flush()
}

fun InputStream.readFrame(): ByteArray {
    var len = 0; var s = 0
    while (true) {
        val b = read()
        if (b < 0) throw EOFException()
        len = len or ((b and 0x7F) shl s)
        if ((b and 0x80) == 0) break
        s += 7
    }
    val buf = ByteArray(len)
    var o = 0
    while (o < len) {
        val n = read(buf, o, len - o)
        if (n < 0) throw EOFException()
        o += n
    }
    return buf
}
