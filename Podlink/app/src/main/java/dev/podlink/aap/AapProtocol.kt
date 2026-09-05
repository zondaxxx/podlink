package dev.podlink.aap

/**
 * Apple Accessory Protocol (AAP) frames as reverse-engineered by the community (librepods / AirPods-like-normal).
 * All frames share the 4-byte prefix 04 00 04 00 followed by a 2-byte little-endian opcode.
 */
object AapProtocol {
    const val PSM = 0x1001

    val HANDSHAKE = hex("00 00 04 00 01 00 02 00 00 00 00 00 00 00 00 00")
    val SET_FEATURES = hex("04 00 04 00 4D 00 FF 00 00 00 00 00 00 00")
    val REQUEST_NOTIFICATIONS = hex("04 00 04 00 0F 00 FF FF FE FF")

    const val OP_BATTERY = 0x04
    const val OP_EAR_DETECTION = 0x06
    const val OP_CONTROL = 0x09
    const val OP_METADATA = 0x1D
    const val OP_CONVERSATION_LEVEL = 0x4B

    /** Control identifiers used with OP_CONTROL. */
    object Ctl {
        const val NOISE_CONTROL = 0x0D          // 1 off, 2 anc, 3 transparency, 4 adaptive
        const val EAR_DETECTION = 0x0A          // 1 on, 2 off
        const val CONVERSATION_AWARENESS = 0x28 // 1 on, 2 off
        const val ADAPTIVE_NOISE_LEVEL = 0x2E   // 0..100
        const val ALLOW_OFF_OPTION = 0x34       // 1 on, 2 off (experimental)
        const val VOLUME_SWIPE = 0x25           // 1 on, 2 off (experimental)
    }

    enum class NoiseMode(val code: Int) {
        OFF(1), ANC(2), TRANSPARENCY(3), ADAPTIVE(4);
        companion object { fun of(code: Int) = entries.firstOrNull { it.code == code } }
    }

    fun control(id: Int, value: Int): ByteArray =
        byteArrayOf(0x04, 0x00, 0x04, 0x00, OP_CONTROL.toByte(), 0x00, id.toByte(),
            (value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte(), ((value shr 16) and 0xFF).toByte(), ((value shr 24) and 0xFF).toByte())

    fun rename(name: String): ByteArray {
        val bytes = name.toByteArray(Charsets.UTF_8).take(32).toByteArray()
        return byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x1A, 0x00, 0x01, bytes.size.toByte(), 0x00) + bytes
    }

    fun hex(s: String): ByteArray = s.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
    fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

    // ---- inbound ---------------------------------------------------------------------------

    sealed class Event {
        data class Battery(val left: Int?, val right: Int?, val case: Int?,
                           val leftCharging: Boolean, val rightCharging: Boolean, val caseCharging: Boolean) : Event()
        /** 0 = in ear, 1 = out of ear, 2 = in case, for primary and secondary pod. */
        data class EarDetection(val primary: Int, val secondary: Int) : Event()
        data class NoiseControl(val mode: NoiseMode) : Event()
        data class ControlValue(val id: Int, val value: Int) : Event()
        data class ConversationLevel(val level: Int) : Event()
        data class Unknown(val opcode: Int, val raw: ByteArray) : Event()
    }

    fun parse(frame: ByteArray): Event? {
        if (frame.size < 6) return null
        if (frame[0] != 0x04.toByte() || frame[2] != 0x04.toByte()) return null
        val op = (frame[4].toInt() and 0xFF) or ((frame[5].toInt() and 0xFF) shl 8)
        val b = IntArray(frame.size) { frame[it].toInt() and 0xFF }
        return when (op) {
            OP_BATTERY -> {
                if (b.size < 7) return null
                val count = b[6]
                var l: Int? = null; var r: Int? = null; var c: Int? = null
                var lc = false; var rc = false; var cc = false
                var i = 7
                repeat(count) {
                    if (i + 4 < b.size) {
                        val type = b[i]; val level = b[i + 2]; val status = b[i + 3]
                        val charging = status == 1
                        val lvl = if (status == 4) null else level
                        when (type) {
                            0x04 -> { l = lvl; lc = charging }
                            0x02 -> { r = lvl; rc = charging }
                            0x08 -> { c = lvl; cc = charging }
                        }
                    }
                    i += 5
                }
                Event.Battery(l, r, c, lc, rc, cc)
            }
            OP_EAR_DETECTION -> if (b.size >= 8) Event.EarDetection(b[6], b[7]) else null
            OP_CONTROL -> {
                if (b.size < 8) return null
                val id = b[6]
                val value = b[7] or (b.getOrElse(8) { 0 } shl 8)
                if (id == Ctl.NOISE_CONTROL) NoiseMode.of(b[7])?.let { Event.NoiseControl(it) } ?: Event.ControlValue(id, value)
                else Event.ControlValue(id, value)
            }
            OP_CONVERSATION_LEVEL -> if (b.size >= 9) Event.ConversationLevel(b[8]) else null
            else -> Event.Unknown(op, frame)
        }
    }
}
