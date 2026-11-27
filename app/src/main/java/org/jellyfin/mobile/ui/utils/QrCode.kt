package org.jellyfin.mobile.ui.utils

/**
 * A small QR code encoder (ISO/IEC 18004): byte mode, error correction level M, versions 1 to 10 (up to 213 bytes),
 * enough for an address to open with a phone. The reference algorithm of the standard, as in the web app
 * (jellyfin-web src/utils/qrCode.ts): data and Reed-Solomon codewords over GF(256) interleaved in blocks, the
 * function patterns, the data in zigzag and the mask with the lowest penalty.
 */
@Suppress("MagicNumber")
object QrCode {
    /** Error correction codewords per block, level M, versions 1 to 10. */
    private val ECC_PER_BLOCK = intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26)

    /** Error correction blocks, level M, versions 1 to 10. */
    private val BLOCKS = intArrayOf(1, 1, 1, 2, 2, 4, 4, 4, 5, 5)
    private const val MAX_VERSION = 10

    /** Level M in the format information. */
    private const val FORMAT_LEVEL_M = 0

    /** Product in GF(256) with the polynomial of QR codes (0x11D). */
    fun gfMultiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z and 0xFF
    }

    private fun rsDivisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        repeat(degree) {
            for (j in 0 until degree) {
                result[j] = gfMultiply(result[j], root)
                if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
            }
            root = gfMultiply(root, 0x02)
        }
        return result
    }

    /** The Reed-Solomon error correction codewords of [data]. */
    fun rsRemainder(data: List<Int>, degree: Int): List<Int> {
        val divisor = rsDivisor(degree)
        val result = ArrayDeque(List(degree) { 0 })
        for (b in data) {
            val factor = b xor result.removeFirst()
            result.addLast(0)
            for (i in 0 until degree) result[i] = result[i] xor gfMultiply(divisor[i], factor)
        }
        return result.toList()
    }

    private fun rawDataModules(ver: Int): Int {
        var result = (16 * ver + 128) * ver + 64
        if (ver >= 2) {
            val numAlign = ver / 7 + 2
            result -= (25 * numAlign - 10) * numAlign - 55
            if (ver >= 7) result -= 36
        }
        return result
    }

    private fun dataCodewords(ver: Int) = rawDataModules(ver) / 8 - ECC_PER_BLOCK[ver - 1] * BLOCKS[ver - 1]

    private fun alignmentPositions(ver: Int, size: Int): List<Int> {
        if (ver == 1) return emptyList()
        val numAlign = ver / 7 + 2
        val step = ((ver * 4 + 4 + (numAlign * 2 - 2) - 1) / (numAlign * 2 - 2)) * 2
        val result = mutableListOf(6)
        var pos = size - 7
        while (result.size < numAlign) {
            result.add(1, pos)
            pos -= step
        }
        return result
    }

    private fun bit(x: Int, i: Int) = ((x ushr i) and 1) != 0

    /** The data codewords of [bytes] in byte mode, with terminator and padding, for [ver]. */
    private fun encodeData(bytes: ByteArray, ver: Int): List<Int> {
        val bits = mutableListOf<Int>()
        fun put(value: Int, length: Int) {
            for (i in length - 1 downTo 0) bits.add((value ushr i) and 1)
        }
        put(0b0100, 4)
        put(bytes.size, if (ver <= 9) 8 else 16)
        for (b in bytes) put(b.toInt() and 0xFF, 8)
        val capacity = dataCodewords(ver) * 8
        put(0, minOf(4, capacity - bits.size))
        put(0, (8 - bits.size % 8) % 8)
        val result = bits.chunked(8).map { byte -> byte.fold(0) { a, b -> (a shl 1) or b } }.toMutableList()
        var pad = 0xEC
        while (result.size < dataCodewords(ver)) {
            result.add(pad)
            pad = pad xor (0xEC xor 0x11)
        }
        return result
    }

    /** Data and error correction codewords, split in blocks and interleaved. */
    private fun withErrorCorrection(data: List<Int>, ver: Int): List<Int> {
        val numBlocks = BLOCKS[ver - 1]
        val eccLen = ECC_PER_BLOCK[ver - 1]
        val raw = rawDataModules(ver) / 8
        val numShort = numBlocks - raw % numBlocks
        val shortLen = raw / numBlocks
        val blocks = mutableListOf<List<Int>>()
        var k = 0
        for (i in 0 until numBlocks) {
            val dat = data.subList(k, k + shortLen - eccLen + if (i < numShort) 0 else 1).toMutableList()
            k += dat.size
            val ecc = rsRemainder(dat, eccLen)
            if (i < numShort) dat.add(0)
            blocks.add(dat + ecc)
        }
        val result = mutableListOf<Int>()
        for (i in blocks[0].indices) {
            for (j in blocks.indices) {
                if (i != shortLen - eccLen || j >= numShort) result.add(blocks[j][i])
            }
        }
        return result
    }

    private val MASKS: List<(Int, Int) -> Boolean> = listOf(
        { x, y -> (x + y) % 2 == 0 },
        { _, y -> y % 2 == 0 },
        { x, _ -> x % 3 == 0 },
        { x, y -> (x + y) % 3 == 0 },
        { x, y -> (x / 3 + y / 2) % 2 == 0 },
        { x, y -> x * y % 2 + x * y % 3 == 0 },
        { x, y -> (x * y % 2 + x * y % 3) % 2 == 0 },
        { x, y -> ((x + y) % 2 + x * y % 3) % 2 == 0 },
    )

    /* ---------- Penalty of a masked matrix (rules N1 to N4) ---------- */

    /** N1: runs of five or more modules of one color in a line. */
    private fun runPenalty(line: BooleanArray): Int {
        var score = 0
        var run = 1
        for (i in 1..line.size) {
            if (i < line.size && line[i] == line[i - 1]) {
                run++
                continue
            }
            if (run >= 5) score += 3 + (run - 5)
            run = 1
        }
        return score
    }

    private val FINDER_LIKE = booleanArrayOf(true, false, true, true, true, false, true)
    private fun matchesAt(line: BooleanArray, at: Int) = FINDER_LIKE.indices.all { line[at + it] == FINDER_LIKE[it] }
    private fun lightAt(line: BooleanArray, from: Int, count: Int) = (from until from + count).all { !line[it] }

    /** N3: a finder-like pattern with four light modules on one side. */
    private fun finderPenalty(line: BooleanArray): Int {
        var score = 0
        var i = 0
        while (i + 10 < line.size) {
            if ((matchesAt(line, i + 4) && lightAt(line, i, 4)) || (matchesAt(line, i) && lightAt(line, i + 7, 4))) score += 40
            i++
        }
        return score
    }

    /** N2: 2x2 blocks of one color. */
    private fun boxPenalty(m: Array<BooleanArray>): Int {
        var score = 0
        for (y in 0 until m.size - 1) {
            for (x in 0 until m.size - 1) {
                val c = m[y][x]
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) score += 3
            }
        }
        return score
    }

    /** N4: how far the dark modules are from half. */
    private fun balancePenalty(m: Array<BooleanArray>): Int {
        val total = m.size * m.size
        val dark = m.sumOf { row -> row.count { it } }
        return Math.abs(dark * 20 - total * 10) / total * 10
    }

    private fun penalty(m: Array<BooleanArray>): Int {
        val columns = Array(m.size) { x -> BooleanArray(m.size) { y -> m[y][x] } }
        return (m.asList() + columns.asList()).sumOf { runPenalty(it) + finderPenalty(it) } + boxPenalty(m) + balancePenalty(m)
    }

    /* ---------- The matrix ---------- */

    private class Matrix(val ver: Int) {
        val size = ver * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        fun set(x: Int, y: Int, dark: Boolean) {
            modules[y][x] = dark
            isFunction[y][x] = true
        }

        /** Timing, finder and alignment patterns, the format (reserved) and the version information. */
        fun drawFunctionPatterns() {
            for (i in 0 until size) {
                set(6, i, i % 2 == 0)
                set(i, 6, i % 2 == 0)
            }
            drawFinder(3, 3)
            drawFinder(size - 4, 3)
            drawFinder(3, size - 4)
            val align = alignmentPositions(ver, size)
            val last = align.size - 1
            for (i in 0..last) {
                for (j in 0..last) {
                    val corner = (i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0)
                    if (!corner) drawAlignment(align[j], align[i])
                }
            }
            drawFormat(0)
            drawVersion()
        }

        fun drawFinder(cx: Int, cy: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val x = cx + dx
                    val y = cy + dy
                    val dist = maxOf(Math.abs(dx), Math.abs(dy))
                    if (x in 0 until size && y in 0 until size) set(x, y, dist != 2 && dist != 4)
                }
            }
        }

        fun drawAlignment(cx: Int, cy: Int) {
            for (dy in -2..2) {
                for (dx in -2..2) set(cx + dx, cy + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
            }
        }

        fun drawFormat(mask: Int) {
            val data = (FORMAT_LEVEL_M shl 3) or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412
            for (i in 0..5) set(8, i, bit(bits, i))
            set(8, 7, bit(bits, 6))
            set(8, 8, bit(bits, 7))
            set(7, 8, bit(bits, 8))
            for (i in 9 until 15) set(14 - i, 8, bit(bits, i))
            for (i in 0 until 8) set(size - 1 - i, 8, bit(bits, i))
            for (i in 8 until 15) set(8, size - 15 + i, bit(bits, i))
            set(8, size - 8, true)
        }

        fun drawVersion() {
            if (ver < 7) return
            var rem = ver
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = (ver shl 12) or rem
            for (i in 0 until 18) {
                val a = size - 11 + i % 3
                val b = i / 3
                set(a, b, bit(bits, i))
                set(b, a, bit(bits, i))
            }
        }

        /** The codewords in zigzag, from the bottom right corner (the timing column is skipped). */
        fun drawCodewords(codewords: List<Int>) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                val column = if (right <= 6) right - 1 else right
                val upward = ((column + 1) and 2) == 0
                for (vert in 0 until size) {
                    val y = if (upward) size - 1 - vert else vert
                    for (x in intArrayOf(column, column - 1)) {
                        if (!isFunction[y][x] && i < codewords.size * 8) {
                            modules[y][x] = bit(codewords[i ushr 3], 7 - (i and 7))
                            i++
                        }
                    }
                }
                right -= 2
            }
        }

        /** The modules with [mask] applied to the data (and its format information). */
        fun masked(mask: Int): Array<BooleanArray> {
            drawFormat(mask)
            return Array(size) { y -> BooleanArray(size) { x -> if (isFunction[y][x]) modules[y][x] else modules[y][x] != MASKS[mask](x, y) } }
        }
    }

    private fun versionFor(length: Int): Int {
        for (ver in 1..MAX_VERSION) {
            if (4 + (if (ver <= 9) 8 else 16) + length * 8 <= dataCodewords(ver) * 8) return ver
        }
        throw IllegalArgumentException("Text too long for a QR code")
    }

    /**
     * The modules of the QR code of [text] (true: dark), without the quiet zone. Throws when it does not fit in
     * version 10 at level M.
     */
    fun encode(text: String): Array<BooleanArray> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val ver = versionFor(bytes.size)
        val matrix = Matrix(ver)
        matrix.drawFunctionPatterns()
        matrix.drawCodewords(withErrorCorrection(encodeData(bytes, ver), ver))
        return (0 until MASKS.size).map { matrix.masked(it) }.minBy { penalty(it) }
    }
}
