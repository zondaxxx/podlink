package dev.podlink.ble

/** Known Apple / Beats model identifiers carried in the Proximity Pairing advertisement (bytes 3..4, big endian). */
enum class PodsModel(
    val id: Int,
    val label: String,
    val kind: Kind,
    val supportsAnc: Boolean = false,
    val supportsAdaptive: Boolean = false,
    val supportsConversationAwareness: Boolean = false,
) {
    AIRPODS_1(0x0220, "AirPods", Kind.EARBUDS),
    AIRPODS_2(0x0F20, "AirPods 2", Kind.EARBUDS),
    AIRPODS_3(0x1320, "AirPods 3", Kind.EARBUDS),
    AIRPODS_4(0x1920, "AirPods 4", Kind.EARBUDS),
    AIRPODS_4_ANC(0x1B20, "AirPods 4 (ANC)", Kind.EARBUDS, supportsAnc = true, supportsConversationAwareness = true),
    AIRPODS_PRO(0x0E20, "AirPods Pro", Kind.EARBUDS, supportsAnc = true),
    AIRPODS_PRO_2(0x1420, "AirPods Pro 2", Kind.EARBUDS, supportsAnc = true, supportsAdaptive = true, supportsConversationAwareness = true),
    AIRPODS_PRO_2_USBC(0x2420, "AirPods Pro 2 (USB‑C)", Kind.EARBUDS, supportsAnc = true, supportsAdaptive = true, supportsConversationAwareness = true),
    AIRPODS_PRO_3(0x2720, "AirPods Pro 3", Kind.EARBUDS, supportsAnc = true, supportsAdaptive = true, supportsConversationAwareness = true),
    AIRPODS_MAX(0x0A20, "AirPods Max", Kind.HEADPHONES, supportsAnc = true),
    AIRPODS_MAX_USBC(0x1F20, "AirPods Max (USB‑C)", Kind.HEADPHONES, supportsAnc = true),
    AIRPODS_MAX_2(0x2D20, "AirPods Max 2", Kind.HEADPHONES, supportsAnc = true),
    POWERBEATS_3(0x0320, "Powerbeats 3", Kind.EARBUDS),
    POWERBEATS_4(0x0D20, "Powerbeats 4", Kind.EARBUDS),
    POWERBEATS_PRO(0x0B20, "Powerbeats Pro", Kind.EARBUDS),
    POWERBEATS_PRO_2(0x1D20, "Powerbeats Pro 2", Kind.EARBUDS, supportsAnc = true),
    BEATS_X(0x0520, "BeatsX", Kind.EARBUDS),
    BEATS_FLEX(0x1020, "Beats Flex", Kind.EARBUDS),
    BEATS_SOLO_3(0x0620, "Beats Solo 3", Kind.HEADPHONES),
    BEATS_SOLO_PRO(0x0C20, "Beats Solo Pro", Kind.HEADPHONES, supportsAnc = true),
    BEATS_STUDIO_3(0x0920, "Beats Studio 3", Kind.HEADPHONES, supportsAnc = true),
    BEATS_STUDIO_BUDS(0x1120, "Beats Studio Buds", Kind.EARBUDS, supportsAnc = true),
    BEATS_STUDIO_BUDS_PLUS(0x1620, "Beats Studio Buds+", Kind.EARBUDS, supportsAnc = true),
    BEATS_FIT_PRO(0x1220, "Beats Fit Pro", Kind.EARBUDS, supportsAnc = true),
    BEATS_STUDIO_PRO(0x1720, "Beats Studio Pro", Kind.HEADPHONES, supportsAnc = true),
    BEATS_SOLO_4(0x2520, "Beats Solo 4", Kind.HEADPHONES),
    BEATS_SOLO_BUDS(0x2620, "Beats Solo Buds", Kind.EARBUDS),
    UNKNOWN(0, "AirPods", Kind.EARBUDS);

    enum class Kind { EARBUDS, HEADPHONES }

    /** Visual / matching family: the BLE model id is more specific than the Classic name, so we match on family. */
    enum class Family { AIRPODS_CLASSIC, AIRPODS_3, AIRPODS_PRO, AIRPODS_MAX, BEATS_BUDS, BEATS_OVER, BEATS_NECK, GENERIC }

    val family: Family get() = when (this) {
        AIRPODS_1, AIRPODS_2 -> Family.AIRPODS_CLASSIC
        AIRPODS_3, AIRPODS_4, AIRPODS_4_ANC -> Family.AIRPODS_3
        AIRPODS_PRO, AIRPODS_PRO_2, AIRPODS_PRO_2_USBC, AIRPODS_PRO_3 -> Family.AIRPODS_PRO
        AIRPODS_MAX, AIRPODS_MAX_USBC, AIRPODS_MAX_2 -> Family.AIRPODS_MAX
        POWERBEATS_PRO, POWERBEATS_PRO_2, BEATS_STUDIO_BUDS, BEATS_STUDIO_BUDS_PLUS, BEATS_FIT_PRO, BEATS_SOLO_BUDS -> Family.BEATS_BUDS
        BEATS_SOLO_3, BEATS_SOLO_PRO, BEATS_STUDIO_3, BEATS_STUDIO_PRO, BEATS_SOLO_4 -> Family.BEATS_OVER
        POWERBEATS_3, POWERBEATS_4, BEATS_X, BEATS_FLEX -> Family.BEATS_NECK
        UNKNOWN -> Family.GENERIC
    }

    /** Loose compatibility check used when deciding whether a beacon belongs to the connected headset. */
    fun sameFamily(other: PodsModel): Boolean =
        this == UNKNOWN || other == UNKNOWN || family == Family.GENERIC || other.family == Family.GENERIC || family == other.family

    companion object {
        fun fromId(id: Int): PodsModel = entries.firstOrNull { it.id == id && it != UNKNOWN } ?: UNKNOWN

        /** Best-effort guess from the Bluetooth Classic device name when we have no BLE packet yet. */
        fun guessFromName(name: String?): PodsModel {
            val n = name?.lowercase() ?: return UNKNOWN
            return when {
                "pro 3" in n -> AIRPODS_PRO_3
                "pro" in n && "airpods" in n -> AIRPODS_PRO_2
                "max" in n && "airpods" in n -> AIRPODS_MAX
                "airpods 4" in n -> AIRPODS_4
                "airpods 3" in n -> AIRPODS_3
                "airpods" in n -> AIRPODS_2
                "studio buds" in n -> BEATS_STUDIO_BUDS
                "fit pro" in n -> BEATS_FIT_PRO
                "powerbeats pro" in n -> POWERBEATS_PRO
                "beats" in n -> BEATS_STUDIO_BUDS
                else -> UNKNOWN
            }
        }
    }
}
