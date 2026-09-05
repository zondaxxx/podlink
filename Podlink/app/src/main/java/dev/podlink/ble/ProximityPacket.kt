package dev.podlink.ble

/**
 * Decoded Apple "Proximity Pairing" BLE advertisement (company 0x004C, type 0x07, length 0x19).
 *
 * Layout of the manufacturer-specific payload (after the 2-byte company id), cross-checked against
 * OpenPods, CAPod, librepods and the furiousMAC dissector:
 *  [0]  0x07 type
 *  [1]  0x19 length (25)
 *  [2]  0x01 prefix for every plaintext status frame (0x00 = pairing mode; anything else = not a status frame)
 *  [3..4] model id (big endian)
 *  [5]  status: bit5 = left pod is primary ("not flipped"), bit6 = the broadcasting pod is in the case,
 *       bit4 = one pod in case, bit2 = both pods in case, bits 1/3 = in-ear flags of the two pods
 *  [6]  pods battery nibbles (0..10 => x10 %, 11..14 => 100 %, 15 = unknown)
 *  [7]  high nibble: charging flags (bit0/bit1 = the two pods, bit2 = case); low nibble: case battery
 *  [8]  bit3 clear = lid open, low 3 bits = lid-open counter — only trustworthy from a pod that is in the case
 *  [9]  colour
 *  [10] connection state (0x00 disconnected, 0x04 idle, 0x05 music, 0x06 call, 0x07 ringing, 0x09 hanging up)
 *  [11..26] 16 bytes AES-encrypted (1 % battery; needs the per-device key we cannot get without AAP)
 */
data class ProximityPacket(
    val model: PodsModel,
    val rawModelId: Int,
    val left: Int?,      // 0..100, null = unknown
    val right: Int?,
    val case: Int?,
    val leftCharging: Boolean,
    val rightCharging: Boolean,
    val caseCharging: Boolean,
    val leftInEar: Boolean,
    val rightInEar: Boolean,
    val lidState: LidState,
    val lidOpenCounter: Int,
    val primaryIsLeft: Boolean,
    val thisPodInCase: Boolean,
    val onePodInCase: Boolean,
    val bothInCase: Boolean,
    val leftIsMicrophone: Boolean,
    val connectionState: ConnectionState,
    val color: Int,
    val rssi: Int,
    val address: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    enum class LidState { OPEN, CLOSED, UNKNOWN }

    enum class ConnectionState(val raw: Int) {
        DISCONNECTED(0x00), IDLE(0x04), MUSIC(0x05), CALL(0x06), RINGING(0x07), HANGING_UP(0x09), UNKNOWN(-1);
        companion object { fun of(raw: Int) = entries.firstOrNull { it.raw == raw } ?: UNKNOWN }
    }

    val lidOpen get() = lidState == LidState.OPEN
    val bothInEar get() = leftInEar && rightInEar
    val anyInEar get() = leftInEar || rightInEar
    /** Frames broadcast from inside the case carry authoritative case/lid information. */
    val hasCaseContext get() = thisPodInCase || onePodInCase || bothInCase

    /** For headphones (Max / Studio) Apple reports the single battery in the "left" slot. */
    val single: Int? get() = left ?: right

    /**
     * Loose identity that survives the random-address rotation: model + colour + unordered battery pair + case.
     * Two frames a few seconds apart from the same headset almost always share it; strangers rarely do.
     */
    val fingerprint: String get() = "$rawModelId/$color/${listOfNotNull(left, right).sorted()}/$case"

    companion object {
        const val APPLE_COMPANY_ID = 0x004C
        const val TYPE_PROXIMITY_PAIRING = 0x07
        const val STATUS_FRAME_LENGTH = 0x19

        fun parse(data: ByteArray, rssi: Int, address: String): ProximityPacket? {
            if (data.size < 27) return null
            val b = IntArray(27) { data[it].toInt() and 0xFF }
            if (b[0] != TYPE_PROXIMITY_PAIRING || b[1] != STATUS_FRAME_LENGTH) return null
            if (b[2] != 0x01) return null   // pairing-mode (0x00) and the Gen4 identity-address frame (0x07) carry garbage

            val modelId = (b[3] shl 8) or b[4]
            val status = b[5]
            val pods = b[6]
            val flagsCase = b[7]

            val primaryIsLeft = (status and 0x20) != 0
            val flipped = !primaryIsLeft
            val thisPodInCase = (status and 0x40) != 0
            val onePodInCase = (status and 0x10) != 0
            val bothInCase = (status and 0x04) != 0

            fun lvl(n: Int): Int? = when { n == 15 -> null; n > 10 -> 100; else -> n * 10 }
            val hi = pods shr 4
            val lo = pods and 0x0F
            // Battery: when flipped the LEFT pod is the high nibble.
            val (l, r) = if (flipped) hi to lo else lo to hi

            // Charging flags nibble: bit0 / bit1 are the two pods; NOT flipped -> left = bit0.
            val chgBit0 = (flagsCase and 0x10) != 0
            val chgBit1 = (flagsCase and 0x20) != 0
            val (lc, rc) = if (flipped) chgBit1 to chgBit0 else chgBit0 to chgBit1
            val caseCharging = (flagsCase and 0x40) != 0
            val caseLvl = flagsCase and 0x0F

            // In-ear bits swap with the flip bit XOR "this pod is in the case" (CAPod / librepods).
            val earBit3 = (status and 0x08) != 0
            val earBit1 = (status and 0x02) != 0
            val swapEar = flipped xor thisPodInCase
            val (lIn, rIn) = if (swapEar) earBit3 to earBit1 else earBit1 to earBit3

            val lidReliable = thisPodInCase || bothInCase
            val lidState = when {
                !lidReliable -> LidState.UNKNOWN
                (b[8] and 0x08) == 0 -> LidState.OPEN
                else -> LidState.CLOSED
            }

            return ProximityPacket(
                model = PodsModel.fromId(modelId),
                rawModelId = modelId,
                left = lvl(l), right = lvl(r), case = lvl(caseLvl),
                leftCharging = lc && l != 15, rightCharging = rc && r != 15, caseCharging = caseCharging && caseLvl != 15,
                leftInEar = lIn && !bothInCase, rightInEar = rIn && !bothInCase,
                lidState = lidState,
                lidOpenCounter = b[8] and 0x07,
                primaryIsLeft = primaryIsLeft,
                thisPodInCase = thisPodInCase,
                onePodInCase = onePodInCase,
                bothInCase = bothInCase,
                leftIsMicrophone = primaryIsLeft xor thisPodInCase,
                connectionState = ConnectionState.of(b[10]),
                color = b[9],
                rssi = rssi,
                address = address,
            )
        }
    }
}
