import android.content.Context
import android.util.Log
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest
import com.example.robofriend.BuildConfig
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.net.URL
import java.util.*
import java.util.concurrent.CompletableFuture

class AwsS3Service(context: Context) {
    private val s3Client: AmazonS3Client
    private val transferUtility: TransferUtility

    init {
        // Initialize the Amazon Cognito credentials provider
        val credentialsProvider = CognitoCachingCredentialsProvider(
            context,
            BuildConfig.AWS_IDENTITY_POOL_ID, // Identity pool ID
            Regions.EU_NORTH_1 // Region
        )

        // Create an S3 client
        s3Client = AmazonS3Client(credentialsProvider)

        // Create a transfer utility
        transferUtility = TransferUtility.builder()
            .context(context)
            .s3Client(s3Client)
            .build()
    }

    fun serializeConversationHistory(conversationHistory: List<String>): String {
        val gson = GsonBuilder().setLenient().create()
        return gson.toJson(conversationHistory)
    }

    fun uploadConversationHistory(context: Context, bucketName: String, objectKey: String, tempFileName: String, conversationHistory: List<String>): CompletableFuture<URL> {
        val future = CompletableFuture<URL>()
        // Serialize the conversation history
        val serializedConversation = serializeConversationHistory(conversationHistory)

        // Write the serialized data to a temporary file
        val tempFile = File(context.cacheDir, tempFileName)
        tempFile.writeText(serializedConversation)

        val uploadObserver = transferUtility.upload(
            bucketName,
            objectKey,
            tempFile
        )

        uploadObserver.setTransferListener(object : TransferListener {
            override fun onStateChanged(id: Int, state: TransferState) {
                if (TransferState.COMPLETED == state) {
                    tempFile.delete()
                    val expiration = Date()
                    var msec = expiration.time
                    msec += 1000 * 60 * 60 // Add 1 hour
                    expiration.time = msec

                    val generatePresignedUrlRequest = GeneratePresignedUrlRequest(bucketName, objectKey)
                    generatePresignedUrlRequest.method = com.amazonaws.HttpMethod.GET
                    generatePresignedUrlRequest.expiration = expiration

                    val url = s3Client.generatePresignedUrl(generatePresignedUrlRequest)
                    future.complete(url)
                } else if (state == TransferState.FAILED || state == TransferState.CANCELED) {
                    future.completeExceptionally(RuntimeException("Upload failed with state: $state"))
                }
            }

            override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
                val percentDonef = bytesCurrent.toFloat() / bytesTotal.toFloat() * 100
                val percentDone = percentDonef.toInt()
                println("ID:$id bytesCurrent: $bytesCurrent bytesTotal: $bytesTotal $percentDone%")
            }

            override fun onError(id: Int, ex: Exception) {
                future.completeExceptionally(ex)
            }
        })

        return future
    }



    fun uploadFile(bucketName: String, file: File, objectKey: String): CompletableFuture<URL> {
        val future = CompletableFuture<URL>()

        val uploadObserver = transferUtility.upload(bucketName, objectKey, file)

        uploadObserver.setTransferListener(object : TransferListener {
            override fun onStateChanged(id: Int, state: TransferState) {
                if (TransferState.COMPLETED == state) {
                    val expiration = Date()
                    var msec = expiration.time
                    msec += 1000 * 60 * 60 // Add 1 hour
                    expiration.time = msec

                    val generatePresignedUrlRequest = GeneratePresignedUrlRequest(bucketName, objectKey)
                    generatePresignedUrlRequest.method = com.amazonaws.HttpMethod.GET
                    generatePresignedUrlRequest.expiration = expiration

                    val url = s3Client.generatePresignedUrl(generatePresignedUrlRequest)
                    future.complete(url)
                } else if (state == TransferState.FAILED || state == TransferState.CANCELED) {
                    future.completeExceptionally(RuntimeException("Upload failed with state: $state"))
                }
            }

            override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
                val percentDonef = bytesCurrent.toFloat() / bytesTotal.toFloat() * 100
                val percentDone = percentDonef.toInt()
                println("ID:$id bytesCurrent: $bytesCurrent bytesTotal: $bytesTotal $percentDone%")
            }

            override fun onError(id: Int, ex: Exception) {
                future.completeExceptionally(ex)
            }
        })

        return future
    }

    fun downloadConversationHistory(context: Context, bucketName: String, objectKey: String): CompletableFuture<List<String>> {
        val future = CompletableFuture<List<String>>()

        val file = File(context.cacheDir, objectKey)

        val downloadObserver = transferUtility.download(
            bucketName,
            objectKey,
            file
        )

        downloadObserver.setTransferListener(object : TransferListener {
            override fun onStateChanged(id: Int, state: TransferState) {
                if (TransferState.COMPLETED == state) {
                    // Read the file and deserialize the JSON
                    var conversationHistory: List<String>? = null
                    try {
                        val jsonContent = file.readText()
                        logLongMessage("AWSS3", "JSON Content: $jsonContent")
                        conversationHistory = deserializeConversationHistory(file.readText())
                        file.delete()
                    }
                    catch (e: java.lang.Exception){
                        future.completeExceptionally(e)
                    }

                    future.complete(conversationHistory)
                } else if (state == TransferState.FAILED || state == TransferState.CANCELED) {
                    future.completeExceptionally(RuntimeException("Download failed with state: $state"))
                }
            }

            override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
                val percentDonef = bytesCurrent.toFloat() / bytesTotal.toFloat() * 100
                val percentDone = percentDonef.toInt()
                println("ID:$id bytesCurrent: $bytesCurrent bytesTotal: $bytesTotal $percentDone%")
            }

            override fun onError(id: Int, ex: Exception) {
                future.completeExceptionally(ex)
            }
        })

        return future
    }

    fun logLongMessage(tag: String, content: String) {
        val maxLogSize = 1000
        for (i in 0..content.length / maxLogSize) {
            val start = i * maxLogSize
            var end = (i + 1) * maxLogSize
            end = if (end > content.length) content.length else end
            Log.d(tag, content.substring(start, end))
        }
    }


    fun deserializeConversationHistory(json: String): List<String> {
        return GsonBuilder().setLenient().create().fromJson(json, object : TypeToken<List<String>>() {}.type)
    }


}
