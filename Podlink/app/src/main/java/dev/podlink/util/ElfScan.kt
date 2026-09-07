package dev.podlink.util

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal ELF64 reader used to look inside the ROM's Bluetooth stack without root.
 *
 * The AirPods L2CAP failure (Google issue 371713238) happens inside `l2c_fcr_chk_chan_modes`: the stack
 * refuses the channel configuration AirPods offer, so `connect()` never completes. librepods patches that
 * function at runtime with an Xposed native hook. We cannot patch anything without root, but the library
 * is world-readable on most ROMs, so we can at least prove whether the function is still the old one and
 * hand out the exact offset and opcodes for a Magisk module later.
 */
object ElfScan {

    data class Symbol(val name: String, val vaddr: Long, val size: Long, val fileOffset: Long)

    data class Result(
        val path: String,
        val fileSize: Long,
        val symbol: Symbol?,
        val bytes: ByteArray?,
        val verdict: String,
        val error: String? = null,
    ) {
        val hex: String get() = bytes?.joinToString(" ") { "%02X".format(it) } ?: ""
        /** First eight instructions, enough to see the prologue on screen. */
        val hexShort: String get() = bytes?.take(32)?.joinToString(" ") { "%02X".format(it) } ?: ""
    }

    /**
     * Parses an in-memory ELF (the mini debug info) and returns the symbol. Its `st_value` is a virtual
     * address of the *original* library, so the caller resolves the file offset against the real file.
     */
    private fun findInBuffer(elf: ByteArray, needle: String): Symbol? = runCatching {
        val buf = ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN)
        if (elf.size < 64 || elf[0] != 0x7F.toByte()) return null
        val shoff = buf.getLong(0x28)
        val shentsize = buf.getShort(0x3A).toInt() and 0xFFFF
        val shnum = buf.getShort(0x3C).toInt() and 0xFFFF
        for (i in 0 until shnum) {
            val b = (shoff + i * shentsize).toInt()
            val type = buf.getInt(b + 0x04)
            if (type != 2 && type != 11) continue
            val off = buf.getLong(b + 0x18).toInt()
            val size = buf.getLong(b + 0x20).toInt()
            val link = buf.getInt(b + 0x28)
            val entsize = buf.getLong(b + 0x38).toInt()
            if (entsize <= 0 || size <= 0) continue
            val sb2 = (shoff + link * shentsize).toInt()
            val strOff = buf.getLong(sb2 + 0x18).toInt()
            val strSize = buf.getLong(sb2 + 0x20).toInt()
            for (k in 0 until size / entsize) {
                val sym = off + k * entsize
                val nameOff = buf.getInt(sym)
                if (nameOff <= 0 || strOff + nameOff >= strOff + strSize) continue
                val sbn = StringBuilder()
                var p = strOff + nameOff
                while (p < elf.size) { val c = elf[p].toInt(); if (c == 0) break; sbn.append(c.toChar()); p++ }
                val name = sbn.toString()
                if (!name.contains(needle)) continue
                val value = buf.getLong(sym + 0x08)
                if (value == 0L) continue
                return Symbol(name, value, buf.getLong(sym + 0x10), value)
            }
        }
        null
    }.getOrNull()

    /**
     * Fingerprints of the stock (buggy) `l2c_fcr_chk_chan_modes` on ARM64:
     *   cmp w8, #3      1F 0D 00 71   — comparing the preferred mode against L2CAP_FCR_ERTM_MODE
     *   mov w0, wzr     E0 03 1F 2A   — the `return false` that aborts the AirPods connection
     */
    private val CMP_MODE_3 = byteArrayOf(0x1F, 0x0D, 0x00, 0x71)
    private val RET_FALSE = byteArrayOf(0xE0.toByte(), 0x03, 0x1F, 0x2A)

    private fun ByteArray.contains(pattern: ByteArray): Boolean {
        outer@ for (i in 0..size - pattern.size) {
            for (j in pattern.indices) if (this[i + j] != pattern[j]) continue@outer
            return true
        }
        return false
    }

    /** ARM64: `mov w0, #1; ret` is what a patched (always-allow) function looks like. */
    private val ALWAYS_TRUE = byteArrayOf(0x20, 0x00, 0x80.toByte(), 0x52, 0xC0.toByte(), 0x03, 0x5F, 0xD6.toByte())

    /** Section names we care about; the mini debug info is an XZ-compressed ELF with a full .symtab. */
    private const val GNU_DEBUGDATA = ".gnu_debugdata"

    fun inspect(path: String, symbolSubstring: String = "l2c_fcr_chk_chan_modes", byteCount: Int = 256): Result {
        val f = File(path)
        if (!f.exists()) return Result(path, 0, null, null, "missing", "file not found")
        if (!f.canRead()) return Result(path, f.length(), null, null, "unreadable", "needs root to read")
        return runCatching {
            RandomAccessFile(f, "r").use { raf ->
                val ident = ByteArray(16).also { raf.readFully(it) }
                if (ident[0] != 0x7F.toByte() || ident[1] != 'E'.code.toByte()) error("not an ELF")
                if (ident[4].toInt() != 2) error("not 64-bit")
                val le = ident[5].toInt() == 1

                fun read(off: Long, len: Int): ByteBuffer {
                    raf.seek(off)
                    val b = ByteArray(len).also { raf.readFully(it) }
                    return ByteBuffer.wrap(b).order(if (le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
                }

                val hdr = read(0, 64)
                val shoff = hdr.getLong(0x28)
                val shentsize = hdr.getShort(0x3A).toInt() and 0xFFFF
                val shnum = hdr.getShort(0x3C).toInt() and 0xFFFF
                if (shoff <= 0 || shnum == 0) error("no section headers (stripped)")

                // section table: [name, type, flags, addr, offset, size, link, info, align, entsize]
                data class Sec(val type: Int, val addr: Long, val off: Long, val size: Long, val link: Int, val entsize: Long)
                val secs = ArrayList<Sec>(shnum)
                val table = read(shoff, shentsize * shnum)
                for (i in 0 until shnum) {
                    val b = i * shentsize
                    secs += Sec(
                        type = table.getInt(b + 0x04),
                        addr = table.getLong(b + 0x10),
                        off = table.getLong(b + 0x18),
                        size = table.getLong(b + 0x20),
                        link = table.getInt(b + 0x28),
                        entsize = table.getLong(b + 0x38),
                    )
                }

                // 2 = SYMTAB, 11 = DYNSYM
                var found: Symbol? = null
                for (sec in secs.filter { it.type == 2 || it.type == 11 }) {
                    if (sec.entsize <= 0 || sec.size <= 0) continue
                    val strs = secs.getOrNull(sec.link) ?: continue
                    val strBuf = read(strs.off, strs.size.toInt().coerceAtMost(4 shl 20))
                    val count = (sec.size / sec.entsize).toInt()
                    val symBuf = read(sec.off, sec.size.toInt().coerceAtMost(16 shl 20))
                    for (i in 0 until count) {
                        val b = i * sec.entsize.toInt()
                        val nameOff = symBuf.getInt(b)
                        if (nameOff <= 0 || nameOff >= strBuf.capacity()) continue
                        val sb = StringBuilder()
                        var p = nameOff
                        while (p < strBuf.capacity()) {
                            val c = strBuf.get(p).toInt()
                            if (c == 0) break
                            sb.append(c.toChar()); p++
                            if (sb.length > 200) break
                        }
                        val name = sb.toString()
                        if (!name.contains(symbolSubstring)) continue
                        val value = symBuf.getLong(b + 0x08)
                        val size = symBuf.getLong(b + 0x10)
                        if (value == 0L) continue
                        // vaddr -> file offset through the section that contains it
                        val host = secs.firstOrNull { it.addr != 0L && value >= it.addr && value < it.addr + it.size }
                        val fileOff = host?.let { it.off + (value - it.addr) } ?: value
                        found = Symbol(name, value, size, fileOff)
                        break
                    }
                    if (found != null) break
                }

                if (found == null) {
                    // Release builds strip .symtab but keep a compressed copy of it in .gnu_debugdata.
                    val names = secs.getOrNull(hdr.getShort(0x3E).toInt() and 0xFFFF)
                    if (names != null) {
                        val nameBuf = read(names.off, names.size.toInt().coerceAtMost(1 shl 20))
                        for (i in 0 until shnum) {
                            val nameOff = table.getInt(i * shentsize)
                            if (nameOff <= 0 || nameOff >= nameBuf.capacity()) continue
                            val sb = StringBuilder()
                            var p2 = nameOff
                            while (p2 < nameBuf.capacity()) {
                                val c = nameBuf.get(p2).toInt(); if (c == 0) break; sb.append(c.toChar()); p2++
                            }
                            if (sb.toString() != GNU_DEBUGDATA) continue
                            val sec = secs[i]
                            val packed = ByteArray(sec.size.toInt()).also { raf.seek(sec.off); raf.readFully(it) }
                            val mini = runCatching {
                                org.tukaani.xz.XZInputStream(java.io.ByteArrayInputStream(packed)).use { it.readBytes() }
                            }.getOrNull() ?: break
                            found = findInBuffer(mini, symbolSubstring)
                            break
                        }
                    }
                }
                if (found == null) return@use Result(path, f.length(), null, null, "not found", "symbol $symbolSubstring absent (stripped)")
                val host = secs.firstOrNull { it.addr != 0L && found!!.vaddr >= it.addr && found!!.vaddr < it.addr + it.size }
                if (host != null) found = found!!.copy(fileOffset = host.off + (found!!.vaddr - host.addr))
                val n = byteCount.coerceAtMost(if (found!!.size > 0) found!!.size.toInt() else 64)
                val code = ByteArray(n)
                raf.seek(found!!.fileOffset)
                raf.readFully(code)
                val patched = code.size >= 8 && code.copyOfRange(0, 8).contentEquals(ALWAYS_TRUE)
                val buggy = code.contains(CMP_MODE_3) && code.contains(RET_FALSE)
                val verdict = when {
                    patched -> "patched"
                    buggy -> "stock, bug present"
                    else -> "stock, unrecognised"
                }
                Result(path, f.length(), found, code, verdict)
            }
        }.getOrElse { Result(path, f.length(), null, null, "error", it.message ?: it.toString()) }
    }
}
