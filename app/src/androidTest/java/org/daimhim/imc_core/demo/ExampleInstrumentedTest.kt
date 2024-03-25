package org.daimhim.imc_core.demo

import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tencent.mars.Mars
import com.tencent.mars.sdt.SdtLogic

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext



//        Thread.sleep(9000)
        assertEquals("org.daimhim.imc_core.demo", appContext.packageName)
    }
}