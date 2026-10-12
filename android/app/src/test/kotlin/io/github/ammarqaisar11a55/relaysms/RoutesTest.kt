package io.github.ammarqaisar11a55.relaysms

import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.core.prefs.AppSettings
import io.github.ammarqaisar11a55.relaysms.ui.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesTest {
    @Test fun `first launch starts at welcome`() {
        assertEquals(Routes.WELCOME, Routes.startDestination(AppSettings()))
    }

    @Test fun `each role starts at its own home`() {
        assertEquals(Routes.SOURCE_HOME, Routes.startDestination(AppSettings(role = DeviceRole.SOURCE)))
        assertEquals(Routes.COMPANION_HOME, Routes.startDestination(AppSettings(role = DeviceRole.COMPANION)))
    }

    @Test fun `role screens are not reachable from the other role or before onboarding`() {
        assertTrue(Routes.isAllowed(Routes.SOURCE_HOME, DeviceRole.SOURCE))
        assertFalse(Routes.isAllowed(Routes.SOURCE_HOME, DeviceRole.COMPANION))
        assertFalse(Routes.isAllowed(Routes.COMPANION_HOME, DeviceRole.SOURCE))
        assertFalse(Routes.isAllowed(Routes.SOURCE_HOME, null))
        assertFalse(Routes.isAllowed(Routes.SETTINGS, null))
        assertTrue(Routes.isAllowed(Routes.SETTINGS, DeviceRole.COMPANION))
        assertTrue(Routes.isAllowed(Routes.ROLE, null))
        assertFalse(Routes.isAllowed(Routes.ROLE, DeviceRole.SOURCE))
    }
}
