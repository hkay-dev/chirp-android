package dev.chirpboard.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Keeps instrumentation deterministic by stopping background model prewarm before app startup. */
class ChirpTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        classLoader: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application {
        System.setProperty(DISABLE_STARTUP_RECOGNIZER_PREWARM_PROPERTY, "true")
        return super.newApplication(classLoader, className, context)
    }
}
