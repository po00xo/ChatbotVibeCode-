package org.po00xo.chatbothelper

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test

class MyPluginTest : BasePlatformTestCase() {

    @Test
    fun testPluginLoads() {
        assertNotNull(project)
    }
}
