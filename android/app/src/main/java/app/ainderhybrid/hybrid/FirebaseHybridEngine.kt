package app.ainderhybrid.hybrid

import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.InferenceSource
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.OnDeviceModelStatus
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The only place the app performs inference: Firebase AI Logic with hybrid on-device support.
 * Cloud = Gemini Developer API backend; on-device = Gemini Nano through the firebase-ai-ondevice
 * extension (ML Kit / AICore). One GenerativeModel per (route, json) because OnDeviceConfig is fixed
 * for the lifetime of a model instance.
 */
@OptIn(PublicPreviewAPI::class)
class FirebaseHybridEngine(
    override val configured: Boolean,
    private val cloudModelName: String,
) : HybridEngine {

    private val models = HashMap<Pair<Route, Boolean>, GenerativeModel>()

    private fun model(route: Route, json: Boolean): GenerativeModel = synchronized(models) {
        models.getOrPut(route to json) {
            Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                modelName = cloudModelName,
                generationConfig = generationConfig {
                    if (json) responseMimeType = "application/json"
                    temperature = 0.7f
                },
                onDeviceConfig = OnDeviceConfig(mode = route.toMode(), maxOutputTokens = ON_DEVICE_MAX_OUTPUT),
            )
        }
    }

    override suspend fun onDeviceState(): OnDeviceState {
        val ext = model(Route.ONLY_ON_DEVICE, json = true).onDeviceExtension ?: return OnDeviceState.UNAVAILABLE
        return when (ext.checkStatus()) {
            OnDeviceModelStatus.AVAILABLE -> OnDeviceState.AVAILABLE
            OnDeviceModelStatus.DOWNLOADABLE -> OnDeviceState.DOWNLOADABLE
            OnDeviceModelStatus.DOWNLOADING -> OnDeviceState.DOWNLOADING
            OnDeviceModelStatus.UNAVAILABLE -> OnDeviceState.UNAVAILABLE
            else -> OnDeviceState.UNKNOWN
        }
    }

    /** The SDK never downloads Gemini Nano by itself; the user starts it from the status card. */
    fun download(): Flow<String>? =
        model(Route.ONLY_ON_DEVICE, json = true).onDeviceExtension?.download()?.map { it.javaClass.simpleName }

    suspend fun warmUp() {
        runCatching { model(Route.ONLY_ON_DEVICE, json = true).onDeviceExtension?.warmUp() }
    }

    override suspend fun generate(route: Route, prompt: String, json: Boolean): RawResult {
        val response = model(route, json).generateContent(prompt)
        val source = when (response.inferenceSource) {
            InferenceSource.ON_DEVICE -> Source.ON_DEVICE
            else -> Source.IN_CLOUD
        }
        return RawResult(response.text.orEmpty(), source, response.modelVersion)
    }

    private fun Route.toMode(): InferenceMode = when (this) {
        Route.PREFER_ON_DEVICE -> InferenceMode.PREFER_ON_DEVICE
        Route.ONLY_ON_DEVICE -> InferenceMode.ONLY_ON_DEVICE
        Route.PREFER_IN_CLOUD -> InferenceMode.PREFER_IN_CLOUD
    }

    companion object {
        /** Gemini Nano: keep on-device outputs short (docs advise against > 256 output tokens). */
        const val ON_DEVICE_MAX_OUTPUT = 256
    }
}
