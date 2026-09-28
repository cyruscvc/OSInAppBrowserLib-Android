package com.outsystems.plugins.inappbrowser.osinappbrowserlib.routeradapters

import android.app.Activity
import android.content.ContextWrapper
import android.view.WindowManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class OSIABWindowPrivacyTests {
    @Test fun inheritsCurrentPolicyThroughContextWrappers() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val wrapped = ContextWrapper(activity)
        assertFalse(OSIABWebViewRouterAdapter.hasSecureWindow(wrapped))
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        assertTrue(OSIABWebViewRouterAdapter.hasSecureWindow(wrapped))
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        assertFalse(OSIABWebViewRouterAdapter.hasSecureWindow(wrapped))
        assertFalse(OSIABWebViewRouterAdapter.hasSecureWindow(activity.applicationContext))
        controller.pause().stop().destroy()
    }
}
