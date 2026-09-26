package com.tylerabitbol.libra

import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformTest {
    @Test
    fun platform_reports_a_name() {
        assertTrue(platformName().isNotBlank())
    }
}
