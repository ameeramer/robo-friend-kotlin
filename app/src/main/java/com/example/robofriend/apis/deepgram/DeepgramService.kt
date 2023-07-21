import com.example.robofriend.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.IOException
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull

class DeepgramService {
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()

    suspend fun postAudioUrlForTranscription(audioUrl: String): String? {
        val requestBody = RequestBody.create(jsonMediaType, "{\"url\":\"$audioUrl\"}")
        val request = Request.Builder()
            .url("https://api.deepgram.com/v1/listen?model=nova&punctuate=true")
            .post(requestBody)
            .addHeader("Authorization", "Token ${BuildConfig.DEEPGRAM_TOKEN}")
            .build()

        return withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val jsonResponse = JSONObject(response.body?.string())
                    // Extracting the transcript from the nested JSON structure
                    val results = jsonResponse.optJSONObject("results")
                    val channels = results?.optJSONArray("channels")
                    val channel = channels?.optJSONObject(0)
                    val alternatives = channel?.optJSONArray("alternatives")
                    val alternative = alternatives?.optJSONObject(0)
                    alternative?.optString("transcript")
                } else {
                    null
                }
            } catch (e: IOException) {
                // Handle the error
                null
            }
        }
    }
}
