package app.ainderhybrid

import android.app.Application
import android.util.Log
import app.ainderhybrid.ainder.AinderClient
import app.ainderhybrid.hybrid.FirebaseHybridEngine
import app.ainderhybrid.hybrid.HybridAi
import app.ainderhybrid.hybrid.HybridLog
import app.ainderhybrid.hybrid.Prompts
import app.ainderhybrid.vault.PrivateVault
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.auth.auth
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Wires the app together. Firebase is optional at build time (see README). */
class AinderHybridApp : Application() {
    lateinit var vault: PrivateVault
        private set
    lateinit var ainder: AinderClient
        private set
    lateinit var ai: HybridAi
        private set
    lateinit var engine: FirebaseHybridEngine
        private set
    lateinit var prompts: Prompts
        private set
    var firebaseReady = false
        private set

    override fun onCreate() {
        super.onCreate()
        vault = PrivateVault(this)
        firebaseReady = BuildConfig.FIREBASE_CONFIGURED && initFirebase()
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        ainder = AinderClient(
            baseUrl = BuildConfig.AINDER_BASE_URL,
            http = http,
            userAgent = "AinderHybrid/${BuildConfig.VERSION_NAME} (android)",
            apiKey = { vault.active()?.apiKey },
        )
        engine = FirebaseHybridEngine(configured = firebaseReady, cloudModelName = BuildConfig.CLOUD_MODEL)
        ai = HybridAi(engine, HybridLog())
        prompts = Prompts { path -> assets.open(path).bufferedReader().use { it.readText() } }
    }

    private fun initFirebase(): Boolean = try {
        FirebaseApp.initializeApp(this) ?: error("no FirebaseApp")
        // App Check is required by Firebase AI Logic. Sideloaded/debug builds use the debug provider
        // (its token is registered in the Firebase console, never in this repository).
        val factory = if (BuildConfig.DEBUG || BuildConfig.APP_CHECK_DEBUG) DebugAppCheckProviderFactory.getInstance()
        else PlayIntegrityAppCheckProviderFactory.getInstance()
        Firebase.appCheck.installAppCheckProviderFactory(factory)
        // Anonymous Auth only identifies the install for per-user AI Logic limits. No profile data.
        Firebase.auth.signInAnonymously().addOnFailureListener { Log.w(TAG, "anonymous auth failed: ${it.javaClass.simpleName}") }
        true
    } catch (e: Exception) {
        Log.w(TAG, "Firebase init failed: ${e.javaClass.simpleName}")
        false
    }

    private companion object {
        const val TAG = "AinderHybrid"
    }
}
