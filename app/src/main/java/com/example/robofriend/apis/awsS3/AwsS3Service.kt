import android.content.Context
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.S3ClientOptions
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest
import com.example.robofriend.BuildConfig
import java.io.File
import java.net.URL
import java.util.*

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

    fun uploadFile(bucketName: String, file: File, objectKey: String): URL {
        val uploadObserver = transferUtility.upload(bucketName, objectKey, file)

        uploadObserver.setTransferListener(object : TransferListener {
            override fun onStateChanged(id: Int, state: TransferState) {
                if (TransferState.COMPLETED == state) {
                    // The upload is complete
                }
            }

            override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
                val percentDonef = bytesCurrent.toFloat() / bytesTotal.toFloat() * 100
                val percentDone = percentDonef.toInt()
                println("ID:$id bytesCurrent: $bytesCurrent bytesTotal: $bytesTotal $percentDone%")
            }

            override fun onError(id: Int, ex: Exception) {
                // Handle errors
            }
        })

        val expiration = Date()
        var msec = expiration.time
        msec += 1000 * 60 * 60 // Add 1 hour
        expiration.time = msec

        val generatePresignedUrlRequest = GeneratePresignedUrlRequest(bucketName, objectKey)
        generatePresignedUrlRequest.method = com.amazonaws.HttpMethod.GET
        generatePresignedUrlRequest.expiration = expiration

        return s3Client.generatePresignedUrl(generatePresignedUrlRequest)
    }
}
