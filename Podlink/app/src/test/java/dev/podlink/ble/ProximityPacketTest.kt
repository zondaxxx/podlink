package dev.podlink.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bit-layout regression tests. Vectors come from CAPod's DualApplePodsTest and the OpenPods parser,
 * so a green run means Podlink decodes exactly like the reference implementations.
 */
class ProximityPacketTest {

    private fun frame(status: Int, pods: Int, flagsCase: Int, lid: Int = 0x31, model: Int = 0x0E20, prefix: Int = 0x01, conn: Int = 0x05): ByteArray {
        val head = intArrayOf(0x07, 0x19, prefix, model shr 8, model and 0xFF, status, pods, flagsCase, lid, 0x00, conn)
        return (head + IntArray(16) { 0xAA }).map { it.toByte() }.toByteArray()
    }

    private fun parse(status: Int, pods: Int = 0x98, flagsCase: Int = 0x0F, lid: Int = 0x31, prefix: Int = 0x01) =
        ProximityPacket.parse(frame(status, pods, flagsCase, lid, prefix = prefix), -50, "AA:BB:CC:DD:EE:FF")!!

    @Test fun `battery mapping follows the flip bit`() {
        // CAPod: status 0x0B, pods 0x98 -> left 90 / right 80 (flipped: left = high nibble)
        parse(0x0B, pods = 0x98).let { assertEquals(90, it.left); assertEquals(80, it.right) }
        // CAPod: status 0x2B, pods 0x89 -> left 90 / right 80 (not flipped: left = low nibble)
        parse(0x2B, pods = 0x89).let { assertEquals(90, it.left); assertEquals(80, it.right) }
    }

    @Test fun `battery nibble semantics`() {
        parse(0x2B, pods = 0xFA).let { assertNull(it.right); assertEquals(100, it.left) }   // 15 = unknown, 10 = 100
        parse(0x2B, pods = 0x0C).let { assertEquals(100, it.left) }                          // 11..14 -> 100 like CAPod/librepods
        assertEquals(50, parse(0x2B, flagsCase = 0x05).case)
        assertNull(parse(0x2B, flagsCase = 0x0F).case)
    }

    @Test fun `charging bits are per pod and follow the flip`() {
        // CAPod vector: 07 19 01 0E 20 51 89 94 … -> left NOT charging, right charging
        parse(0x51, pods = 0x89, flagsCase = 0x94).let { assertFalse(it.leftCharging); assertTrue(it.rightCharging) }
        // not flipped: bit0 (0x10) = left
        parse(0x2B, flagsCase = 0x15).let { assertTrue(it.leftCharging); assertFalse(it.rightCharging) }
        parse(0x2B, flagsCase = 0x25).let { assertFalse(it.leftCharging); assertTrue(it.rightCharging) }
        // case charging = 0x40, never flipped
        assertTrue(parse(0x0B, flagsCase = 0x45).caseCharging)
        assertFalse(parse(0x0B, flagsCase = 0x05).caseCharging)
    }

    @Test fun `in-ear bits use flip XOR this-pod-in-case`() {
        parse(0x73).let { assertFalse(it.leftInEar); assertTrue(it.rightInEar) }   // CAPod: right in ear
        parse(0x53).let { assertTrue(it.leftInEar); assertFalse(it.rightInEar) }   // CAPod: left in ear
        parse(0x2B).let { assertTrue(it.leftInEar); assertTrue(it.rightInEar) }    // both
        parse(0x13).let { assertFalse(it.leftInEar); assertTrue(it.rightInEar) }   // right only
        parse(0x04).let { assertFalse(it.leftInEar); assertFalse(it.rightInEar) }  // both in case
    }

    @Test fun `lid is only trusted with case context`() {
        assertEquals(ProximityPacket.LidState.OPEN, parse(0x73, lid = 0x31).lidState)
        assertEquals(ProximityPacket.LidState.CLOSED, parse(0x73, lid = 0x39).lidState)
        assertEquals(ProximityPacket.LidState.CLOSED, parse(0x04, lid = 0x5A).lidState)   // both in case
        assertEquals(ProximityPacket.LidState.UNKNOWN, parse(0x10, lid = 0x51).lidState)  // bit4-only frame: stale lid byte
        assertEquals(ProximityPacket.LidState.UNKNOWN, parse(0x2B, lid = 0x31).lidState)  // out of case
        assertEquals(1, parse(0x73, lid = 0x31).lidOpenCounter)
    }

    @Test fun `status flags, microphone and connection state`() {
        parse(0x2B).let { assertTrue(it.primaryIsLeft); assertTrue(it.leftIsMicrophone); assertFalse(it.thisPodInCase) }
        parse(0x0B).let { assertFalse(it.primaryIsLeft); assertFalse(it.leftIsMicrophone) }
        parse(0x73).let { assertTrue(it.thisPodInCase); assertTrue(it.onePodInCase); assertFalse(it.leftIsMicrophone) } // flipped again in case
        assertEquals(ProximityPacket.ConnectionState.MUSIC, parse(0x2B).connectionState)
        assertEquals(PodsModel.AIRPODS_PRO, parse(0x2B).model)
    }

    @Test fun `non-status frames are rejected`() {
        assertNull(ProximityPacket.parse(frame(0x2B, 0x98, 0x0F, prefix = 0x07), -50, "x"))       // Gen4 identity frame (CAPod #603)
        assertNull(ProximityPacket.parse(frame(0x2B, 0x98, 0x0F, prefix = 0x00), -50, "x"))       // pairing mode
        val short = byteArrayOf(0x07, 0x11, 0x06) + ByteArray(16) { 0x6B }                          // lid-closed encrypted variant
        assertNull(ProximityPacket.parse(short, -37, "x"))
        assertNull(ProximityPacket.parse(byteArrayOf(0x07, 0x19), -50, "x"))
    }

    @Test fun `fingerprint ignores order of pods`() {
        val a = parse(0x2B, pods = 0x89)
        val b = parse(0x0B, pods = 0x98)
        assertEquals(a.fingerprint, b.fingerprint)
    }
}
