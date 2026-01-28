package com.kotkit.basic.data.remote.api.models

import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for PayoutModels, especially sensitive data masking.
 */
class PayoutModelsTest {

    @Test
    fun `card number is masked correctly in toString`() {
        val request = PayoutRequestCreate(
            amountUsd = 100f,
            method = PayoutMethod.CARD,
            walletAddress = null,
            cardNumber = "4111111111111111",
            phoneNumber = null
        )

        val str = request.toString()

        assertFalse("Full card number should not appear", str.contains("4111111111111111"))
        assertTrue("Last 4 digits should appear", str.contains("****1111"))
    }

    @Test
    fun `wallet address is masked correctly in toString`() {
        val request = PayoutRequestCreate(
            amountUsd = 50f,
            method = PayoutMethod.CRYPTO,
            walletAddress = "0x1234567890abcdef1234567890abcdef12345678",
            cardNumber = null,
            phoneNumber = null
        )

        val str = request.toString()

        assertFalse("Full wallet should not appear", str.contains("0x1234567890abcdef1234567890abcdef12345678"))
        assertTrue("Masked wallet should appear", str.contains("0x1***678"))
    }

    @Test
    fun `phone number is masked correctly in toString`() {
        val request = PayoutRequestCreate(
            amountUsd = 25f,
            method = PayoutMethod.SBP,
            walletAddress = null,
            cardNumber = null,
            phoneNumber = "+79001234567"
        )

        val str = request.toString()

        assertFalse("Full phone should not appear", str.contains("+79001234567"))
        assertTrue("Masked phone should appear", str.contains("+79***567"))
    }

    @Test
    fun `short values are fully masked`() {
        val request = PayoutRequestCreate(
            amountUsd = 10f,
            method = PayoutMethod.CARD,
            walletAddress = null,
            cardNumber = "1234", // Short card
            phoneNumber = null
        )

        val str = request.toString()

        // Short card should show only last 4
        assertTrue("Short card should be masked", str.contains("****1234"))
    }

    @Test
    fun `null values are handled gracefully`() {
        val request = PayoutRequestCreate(
            amountUsd = 100f,
            method = PayoutMethod.CRYPTO,
            walletAddress = null,
            cardNumber = null,
            phoneNumber = null
        )

        val str = request.toString()

        assertTrue("Should contain amount", str.contains("100"))
        assertTrue("Should contain method", str.contains("crypto"))
        assertTrue("Should handle null wallet", str.contains("wallet=null"))
    }

    @Test
    fun `payout statuses are correctly defined`() {
        assertEquals("pending", PayoutStatus.PENDING)
        assertEquals("processing", PayoutStatus.PROCESSING)
        assertEquals("completed", PayoutStatus.COMPLETED)
        assertEquals("failed", PayoutStatus.FAILED)
        assertEquals("cancelled", PayoutStatus.CANCELLED)
    }

    @Test
    fun `payout methods are correctly defined`() {
        assertEquals("crypto", PayoutMethod.CRYPTO)
        assertEquals("card", PayoutMethod.CARD)
        assertEquals("sbp", PayoutMethod.SBP)
    }

    @Test
    fun `earning statuses are correctly defined`() {
        assertEquals("pending", EarningStatus.PENDING)
        assertEquals("confirmed", EarningStatus.CONFIRMED)
        assertEquals("paid", EarningStatus.PAID)
    }
}
