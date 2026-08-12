package org.opentrafficmap.shared

import kotlin.test.Test
import kotlin.test.assertTrue

class SharedFacadeTest {
    @Test
    fun helloContainsVersion() {
        assertTrue(SharedFacade.hello().contains(SharedFacade.VERSION))
    }
}
