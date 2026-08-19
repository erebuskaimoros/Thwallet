package io.horizontalsystems.walletkit.chain.xrp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import io.horizontalsystems.hdwalletkit.Mnemonic
import io.horizontalsystems.walletkit.chain.xrp.submission.XrpSignedTransaction
import java.math.BigDecimal

class XrpCodecGoldenTest {
    @Test
    fun derivesBip44AccountZeroFromMnemonic() {
        val seed = Mnemonic().toSeed(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "),
            "",
        )

        assertArrayEquals(
            hex("90802A50AA84EFB6CDB225F17C27616EA94048C179142FECF03F4712A07EA7A4"),
            XrpKeyDerivation.privateKey(seed),
        )
        assertEquals("rHsMGQEkVNJmpGWs8XUBoTBiAAbwxZN5v3", XrpKeyDerivation.address(seed))
    }

    @Test
    fun derivesClassicAddressFromSecp256k1PrivateKey() {
        val privateKey = hex("9c3d42d0515f0406ed350ab2abf3eaf761f8907802469b64052ac17e2250ae13")

        assertEquals(
            "r36yxStAh7qgTQNHTzjZvXybCTzUFhrfav",
            XrpAddressCodec.classicAddress(privateKey),
        )
    }

    @Test
    fun parsesOfficialMainnetXAddressVectorsWithoutLosingTagPresence() {
        assertEquals(
            XrpDestination("rnBFvgZphmN39GWzUJeUitaP22Fr9be75H", 12_345u),
            XrpAddressCodec.parse("X76UnYEMbQfEs3mUqgtjp4zFy9exgThRj7XVZ6UxsdrBptF"),
        )
        assertEquals(
            XrpDestination("rnBFvgZphmN39GWzUJeUitaP22Fr9be75H", null),
            XrpAddressCodec.parse("X76UnYEMbQfEs3mUqgtjp4zFy9exgSxWAqcQwu9z2r5d7Tm"),
        )

        val zeroTag = XrpAddressCodec.classicToXAddress(
            "rnBFvgZphmN39GWzUJeUitaP22Fr9be75H",
            0u,
        )
        assertEquals(0u, XrpAddressCodec.parse(zeroTag).destinationTag)
        assertEquals(
            UInt.MAX_VALUE,
            XrpAddressCodec.parse(
                XrpAddressCodec.classicToXAddress(
                    "rnBFvgZphmN39GWzUJeUitaP22Fr9be75H",
                    UInt.MAX_VALUE,
                )
            ).destinationTag,
        )
    }

    @Test
    fun rejectsTestnetAndConflictingDestinationTags() {
        assertThrows(IllegalArgumentException::class.java) {
            XrpAddressCodec.parse("T7QDemmxnuN7a52A62nx2fxGPWcRahLCf3qaswfrsNW9Lps")
        }
        assertThrows(IllegalArgumentException::class.java) {
            XrpAddressCodec.resolveDestination(
                "X76UnYEMbQfEs3mUqgtjp4zFy9exgThRj7XVZ6UxsdrBptF",
                12_346u,
            )
        }
    }

    @Test
    fun convertsXrpAndDropsExactly() {
        assertEquals(1_000_001L, XrpAmount.toDropsExact(BigDecimal("1.000001")))
        assertEquals(BigDecimal("1.000001"), XrpAmount.fromDrops(1_000_001L))
        assertThrows(IllegalArgumentException::class.java) {
            XrpAmount.toDropsExact(BigDecimal("0.0000001"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            XrpAmount.toDropsExact(BigDecimal("-1"))
        }
    }

    @Test
    fun signsCanonicalNativePaymentByteForByte() {
        val vectors = listOf(
            SigningVector(
                tag = null,
                memo = null,
                blob = "12000022800000002400000001201B000000646140000000000F424068400000000000000C" +
                    "7321031D68BC1A142E6766B2BDFB006CCFE135EF2E0E2E94ABB5CF5C9AB6104776FBAE" +
                    "74473045022100F7F5169EA94C474C8970E4604960F3A8D1DCD7FE26BBE7E3F51FE5D139ED894B" +
                    "02206ED2A6638E6C58CC15E4EF6A4D957A030114E1E25B5F41D9E601F50F9C7BFE0D" +
                    "8114AFF3C2E33458B30714CA16FFEE19952DD35C17C883145720939C1336A7356A70ED861D5934345C6B6360",
                hash = "08B15FE1FCA80E2042ECC6189732C998187267C62B2D1543BAE5CDB8B56B5270",
            ),
            SigningVector(
                tag = 0u,
                memo = null,
                blob = "120000228000000024000000012E00000000201B000000646140000000000F424068400000000000000C" +
                    "7321031D68BC1A142E6766B2BDFB006CCFE135EF2E0E2E94ABB5CF5C9AB6104776FBAE" +
                    "74473045022100F61C68E017402AB6FF54F23E76FB4B93B37C74E597B424E85EB06BC6A05CBCDA" +
                    "022073D3F3AFED1BF3E386A8B8E2DEA7414B17E90F6E5F459D740340C86A27F96A28" +
                    "8114AFF3C2E33458B30714CA16FFEE19952DD35C17C883145720939C1336A7356A70ED861D5934345C6B6360",
                hash = "F2AB1A28EBDD58942CDD44276CF17F1B9365786EF7B286CDF93A7DD8C14BB5D1",
            ),
            SigningVector(
                tag = UInt.MAX_VALUE,
                memo = null,
                blob = "120000228000000024000000012EFFFFFFFF201B000000646140000000000F424068400000000000000C" +
                    "7321031D68BC1A142E6766B2BDFB006CCFE135EF2E0E2E94ABB5CF5C9AB6104776FBAE" +
                    "7446304402202F31AAB22EF647068D78451CDCD120415C2A79C93154E0AB3D092CDCE9FD5E9A" +
                    "02200A8D854624DFB6BBC64F0C2F2A66BEC892873E1CAA5E7382957C50837394769E" +
                    "8114AFF3C2E33458B30714CA16FFEE19952DD35C17C883145720939C1336A7356A70ED861D5934345C6B6360",
                hash = "949B545BC2363DA5947ABE046441E9392A20D79EE29C494AEAE0E2527DF616B0",
            ),
            SigningVector(
                tag = null,
                memo = "THORCHAIN-TEST-MEMO",
                blob = "12000022800000002400000001201B000000646140000000000F424068400000000000000C" +
                    "7321031D68BC1A142E6766B2BDFB006CCFE135EF2E0E2E94ABB5CF5C9AB6104776FBAE" +
                    "744730450221009FC65645A0486070E528CD2E09A12365CF0092812169ADA28CE52B5FF6BC9DED" +
                    "02201FCD7B07E64F19D3698976A2690290DD5F49DB0986C993C564ECF44C01E2E1EB" +
                    "8114AFF3C2E33458B30714CA16FFEE19952DD35C17C883145720939C1336A7356A70ED861D5934345C6B6360" +
                    "F9EA7D1354484F52434841494E2D544553542D4D454D4FE1F1",
                hash = "C672CBA68B75FFB52B09BEE80CBE84AB28946A116ED849C62BC2F99D09B0EE91",
            ),
        )

        vectors.forEach { vector ->
            val result = XrpPaymentSigner.sign(
                XrpPaymentInput(
                    privateKey = hex("90802A50AA84EFB6CDB225F17C27616EA94048C179142FECF03F4712A07EA7A4"),
                    account = "rHsMGQEkVNJmpGWs8XUBoTBiAAbwxZN5v3",
                    destination = "r3AgF9mMBFtaLhKcg96weMhbbEFLZ3mx17",
                    destinationTag = vector.tag,
                    amountDrops = 1_000_000,
                    feeDrops = 12,
                    sequence = 1u,
                    lastLedgerSequence = 100u,
                    memo = vector.memo,
                )
            )

            assertArrayEquals(hex(vector.blob), result.signedBlob)
            assertEquals(vector.hash, result.hash)
            assertEquals(result, XrpPaymentSigner.decodeAndVerifyCommitments(result.signedBlob, result.hash))
        }
    }

    @Test
    fun rejectsNegativeIntegerEncodingOfAnOtherwiseValidSignature() {
        val canonical = XrpPaymentSigner.sign(
            XrpPaymentInput(
                privateKey = hex("90802A50AA84EFB6CDB225F17C27616EA94048C179142FECF03F4712A07EA7A4"),
                account = "rHsMGQEkVNJmpGWs8XUBoTBiAAbwxZN5v3",
                destination = "r3AgF9mMBFtaLhKcg96weMhbbEFLZ3mx17",
                destinationTag = null,
                amountDrops = 1_000_000,
                feeDrops = 12,
                sequence = 1u,
                lastLedgerSequence = 100u,
                memo = null,
            )
        ).signedBlob
        val signaturePrefix = hex("74473045022100F7")
        val fieldOffset = canonical.asList().windowed(signaturePrefix.size).indexOf(signaturePrefix.asList())
        require(fieldOffset >= 0) { "Golden signature no longer exercises a high-bit r scalar" }
        val mutated = canonical.toMutableList().apply {
            this[fieldOffset + 1] = 0x46.toByte()
            this[fieldOffset + 3] = 0x44.toByte()
            this[fieldOffset + 5] = 0x20.toByte()
            removeAt(fieldOffset + 6)
        }.toByteArray()
        val mutatedHash = XrpSignedTransaction.transactionHash(mutated.toHex())

        assertThrows(IllegalArgumentException::class.java) {
            XrpPaymentSigner.decodeAndVerifyCommitments(mutated, mutatedHash)
        }
    }

    private data class SigningVector(val tag: UInt?, val memo: String?, val blob: String, val hash: String)

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it.toInt() and 0xff) }
}
