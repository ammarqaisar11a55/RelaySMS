package io.github.ammarqaisar11a55.relaysms

import io.github.ammarqaisar11a55.relaysms.otp.OtpDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** All messages are synthetic. */
class OtpDetectorTest {
    private fun code(text: String) = OtpDetector.detect(text).best?.code

    @Test fun `typical verification messages`() {
        assertEquals("123456", code("Your OTP is 123456. Do not share it with anyone."))
        assertEquals("123456", code("123456 is your verification code for ExampleApp."))
        assertEquals("4821", code("Use code 4821 to log in. Valid for 10 minutes."))
        assertEquals("5839", code("Enter 5839 to verify your number. Expires in 300 seconds."))
        assertEquals("90817263", code("Security code: 90817263"))
        assertEquals("778899", code("Dear customer, 778899 is your One Time Password for login."))
    }

    @Test fun `codes written in groups or with a prefix are normalised`() {
        assertEquals("123456", code("Code: 123 456"))
        assertEquals("123456", code("Your passcode is 123-456."))
        assertEquals("482913", code("G-482913 is your Google verification code."))
    }

    @Test fun `alphanumeric codes are found only right after a code word`() {
        assertEquals("K7M2QX", code("Your login code is K7M2QX"))
        assertNull(code("Flight PK304 departs from gate B12, your booking code will be emailed."))
    }

    @Test fun `the highlighted range points at the code in the original text`() {
        val text = "Your OTP is 123 456 ok"
        val best = OtpDetector.detect(text).best!!
        assertEquals("123 456", text.substring(best.start, best.end))
        assertEquals("123456", best.code)
    }

    @Test fun `a message without any code word has no code`() {
        assertNull(code("Meeting at 1430 in room 2045"))
        assertNull(code("PKR 5,000.00 debited from A/C ***1234 on 12-10-2026. Balance PKR 20,500.00"))
        assertNull(code("Your parcel 48291734 is out for delivery"))
        assertNull(code(""))
        assertFalse(OtpDetector.looksLikeOtp("Call me at 03001234567"))
    }

    @Test fun `amounts are not codes even next to a code word`() {
        assertEquals("778899", code("Your OTP for transaction of PKR 15000 at STORE is 778899. Never share your OTP."))
        assertEquals("246810", code("Rs. 2500 will be charged. Confirm with code 246810."))
        assertNull(code("Your PIN purchase of Rs 5000 was successful."))
        assertNull(code("Verification complete. Amount 12500 PKR credited."))
    }

    @Test fun `account card and reference numbers are not codes`() {
        assertEquals("9911", code("Your account 12345678 was credited. Use OTP 9911 to confirm."))
        assertNull(code("Txn ID 99887766 successful. Your OTP was verified."))
        assertNull(code("Card ending 4321 was used. If this was not you, change your PIN."))
        assertNull(code("Password reset requested. For help call 042111222 or ref 558877."))
    }

    @Test fun `dates times and durations are not codes`() {
        assertNull(code("Your PIN was changed on 2026-10-12."))
        assertNull(code("Your password expires in 1800 seconds."))
        assertNull(code("Verification appointment at 1430 hrs tomorrow."))
        assertEquals("602214", code("Code 602214 is valid until 12:30 on 10/12/2026."))
    }

    @Test fun `phone numbers are not codes`() {
        assertNull(code("To verify your identity call 03001234567."))
        assertNull(code("Verification helpline +923001234567"))
    }

    @Test fun `several plausible codes are reported as ambiguous`() {
        val r = OtpDetector.detect("Your codes are 111222 and 333444")
        assertEquals(listOf("111222", "333444"), r.candidates.map { it.code })
        assertTrue(r.ambiguous)
        val clear = OtpDetector.detect("Your OTP is 123456. Order total 2 items.")
        assertFalse(clear.ambiguous)
    }

    @Test fun `urdu and arabic-indic digits`() {
        assertEquals("654321", code("آپ کا کوڈ 654321 ہے"))
        assertEquals("654321", code("آپ کا کوڈ ۶۵۴۳۲۱ ہے"))
        assertEquals("123456", code("رمز التحقق: ١٢٣٤٥٦"))
    }

    @Test fun `very long input is bounded and does not crash`() {
        val long = "x".repeat(50_000) + " OTP 123456"
        assertNull(code(long))
        assertEquals("123456", code("OTP 123456 " + "x".repeat(50_000)))
    }
}
