import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import java.net.URL

class AwsS3ServiceTest {

    private val identityPoolId = "eu-north-1:086ae218-1d35-47ac-b19c-f2af94bcc47a"
    private val bucketName = "test-bucket-amir4"
    private val awsS3Service = AwsS3Service(identityPoolId, bucketName)

    @Test
    fun testGetPresignedUrl() = runBlocking {
        val presignedUrl = awsS3Service.uploadFile("test.mp3",
            "/Users/amiramer/Downloads/ElevenLabs_2023-06-28T09_16_01.000Z_Amir Amer_dU5hET2GdeMVHQzaNIzW.mp3")


        Assert.assertNotNull(presignedUrl)

        // check that the URL is a well-formed URL
        URL(presignedUrl)
        println("Presigned URL: $presignedUrl")
    }
}
