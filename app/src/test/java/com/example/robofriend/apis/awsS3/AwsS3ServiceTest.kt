import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import java.net.URL
import com.example.robofriend.BuildConfig

class AwsS3ServiceTest {

    private val identityPoolId = BuildConfig.AWS_IDENTITY_POOL_ID
    private val bucketName = BuildConfig.AWS_BUCKET_NAME
//    private val awsS3Service = AwsS3Service(identityPoolId, bucketName)

    @Test
    fun testGetPresignedUrl() = runBlocking {
//        val presignedUrl = awsS3Service.uploadFile("test.mp3",
//            "/Users/amiramer/Downloads/ElevenLabs_2023-06-28T09_16_01.000Z_Amir Amer_dU5hET2GdeMVHQzaNIzW.mp3")
//
//
//        Assert.assertNotNull(presignedUrl)
//
//        // check that the URL is a well-formed URL
//        URL(presignedUrl)
//        println("Presigned URL: $presignedUrl")
    }
}
