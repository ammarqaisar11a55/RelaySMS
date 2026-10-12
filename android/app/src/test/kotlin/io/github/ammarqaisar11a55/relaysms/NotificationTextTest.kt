package io.github.ammarqaisar11a55.relaysms

import io.github.ammarqaisar11a55.relaysms.notifications.NotificationText
import io.github.ammarqaisar11a55.relaysms.otp.OtpDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTextTest {
    private val sender = "TESTBANK"
    private val body = "Your OTP is 135246. Do not share it."

    private fun NotificationText.all() = listOf(title, text, lockScreenTitle, lockScreenText).joinToString("|")

    @Test fun `by default nothing about the message is revealed`() {
        val n = NotificationText.of(sender, body, showPreview = false, unseenCount = 1)
        assertEquals("RelaySMS", n.title)
        assertEquals("New message received", n.text)
        assertFalse(n.expandable)
        for (secret in listOf("TESTBANK", "135246", "OTP", "share")) assertFalse(n.all().contains(secret))
    }

    @Test fun `several messages are summarised as a count only`() {
        val n = NotificationText.of(sender, body, showPreview = false, unseenCount = 3)
        assertEquals("3 new messages", n.text)
        assertFalse(n.all().contains("135246"))
    }

    @Test fun `with preview the shade shows content but the lock screen version never does`() {
        val n = NotificationText.of(sender, body, showPreview = true, unseenCount = 2)
        assertEquals(sender, n.title)
        assertEquals(body, n.text)
        assertTrue(n.expandable)
        assertEquals("RelaySMS", n.lockScreenTitle)
        assertEquals("2 new messages", n.lockScreenText)
        for (secret in listOf("TESTBANK", "135246")) assertFalse((n.lockScreenTitle + n.lockScreenText).contains(secret))
    }

    @Test fun `more otp edge cases`() {
        fun code(t: String) = OtpDetector.detect(t).best?.code
        // Code at the very start and end of a message.
        assertEquals("482910", code("482910 is your OTP"))
        assertEquals("482910", code("Your OTP: 482910"))
        // Surrounded by punctuation and brackets.
        assertEquals("482910", code("Use verification code (482910)."))
        assertEquals("482910", code("OTP=482910; valid 5 min"))
        // The app name contains digits but is not the code.
        assertEquals("771203", code("App24 login code is 771203"))
        // Two numbers, only one is a code: the amount is excluded.
        assertEquals("990011", code("Pay PKR 12000 to MERCHANT. OTP 990011."))
        // A warning about codes, with no code in it.
        assertNull(code("Never share your OTP or PIN with anyone, including bank staff."))
        // A balance alert that mentions a PIN-less service.
        assertNull(code("Your balance is Rs 3500. Dial *123# for PIN reset."))
        // Not confused by a long tracking number.
        assertNull(code("Your verification documents were shipped. Tracking 1Z999AA10123456784."))
    }
}
