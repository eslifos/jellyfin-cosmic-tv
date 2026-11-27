package org.jellyfin.mobile.ui.utils

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The rows of a matrix as 0 and 1, joined by commas. */
private fun Array<BooleanArray>.rows() = joinToString(",") { row -> row.joinToString("") { if (it) "1" else "0" } }

class QrCodeTest : FunSpec({
    test("multiplies in GF(256)") {
        QrCode.gfMultiply(0, 7) shouldBe 0
        QrCode.gfMultiply(1, 0x53) shouldBe 0x53
        QrCode.gfMultiply(2, 0x80) shouldBe 0x1D
    }

    test("computes the error correction of the standard example (HELLO WORLD, 1-M)") {
        val data = listOf(32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236, 17, 236, 17)
        QrCode.rsRemainder(data, 10) shouldBe listOf(196, 35, 39, 119, 235, 215, 231, 226, 93, 23)
    }

    // The same modules as the encoder of the web app (src/utils/qrCode.ts), version 1 and version 2
    test("draws the same code as the web app") {
        QrCode.encode("hello").rows() shouldBe "111111100110001111111,100000101100001000001,101110100101101011101," +
            "101110100011001011101,101110101100101011101,100000100000101000001,111111101010101111111," +
            "000000000011100000000,101010100101000010010,001011000010001000011,010100101110100011111," +
            "110010000000001000010,011010110010101010000,000000001111010100111,111111100011011100111," +
            "100000100011110110000,101110101011011100011,101110100100001100110,101110101110100010101," +
            "100000100100001010010,111111101110101100011"
        QrCode.encode("http://192.168.15.88:8099/").rows() shouldBe "1111111000001100001111111,1000001001001111101000001," +
            "1011101010001001101011101,1011101011101101101011101,1011101010111110001011101,1000001010011001101000001," +
            "1111111010101010101111111,0000000010110001000000000,1011111000101101101111100,0100000010100110010000010," +
            "1100001011001001001101011,0000000001001000100010001,1111001100101100001110111,1001000111101110000001010," +
            "1001011001000101010101011,1001010110110001000001001,1000101101111100111110100,0000000011011110100011100," +
            "1111111000000000101011111,1000001011001001100011000,1011101011110101111111100,1011101011111011101110111," +
            "1011101010000000010000101,1000001000101000101111001,1111111010010101011111111"
    }

    test("picks a larger version for a longer text, and refuses one too long") {
        QrCode.encode("https://jellyfin.example.com/web/#/quickconnect?code=123456").size shouldBe 33
        shouldThrow<IllegalArgumentException> { QrCode.encode("x".repeat(300)) }
    }
})
