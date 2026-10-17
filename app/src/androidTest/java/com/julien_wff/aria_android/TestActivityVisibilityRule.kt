package com.julien_wff.aria_android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** Allow our test Activity to return above a secure keyguard without changing device security. */
class TestActivityVisibilityRule : ExternalResource() {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (activity is MainActivity) {
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
            }
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
    override fun before() {
        instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(callbacks) }
    }
    override fun after() {
        instrumentation.runOnMainSync { application.unregisterActivityLifecycleCallbacks(callbacks) }
    }
}
