package pl.bargor.thesaurus

import android.Manifest
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Grants Android 17's local-network permission to emulator integration tests. */
class LocalNetworkPermissionRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            if (Build.VERSION.SDK_INT >= 37) {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.uiAutomation.grantRuntimePermission(
                    instrumentation.targetContext.packageName,
                    Manifest.permission.ACCESS_LOCAL_NETWORK,
                )
            }
            base.evaluate()
        }
    }
}
