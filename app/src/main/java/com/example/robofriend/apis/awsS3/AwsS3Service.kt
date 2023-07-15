import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.cognitoidentity.model.GetIdRequest
import software.amazon.awssdk.services.cognitoidentity.model.GetCredentialsForIdentityRequest
import software.amazon.awssdk.services.cognitoidentity.CognitoIdentityClient
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import java.nio.file.Paths
import java.time.Duration

class AwsS3Service(private val identityPoolId: String, private val bucketName: String) {

    private val cognitoIdentity = CognitoIdentityClient.builder()
        .region(Region.EU_NORTH_1)
        .build()

    fun uploadFile(key: String, filePath: String): String {

        // Fetch Cognito identity
        val idRes = cognitoIdentity.getId(
            GetIdRequest.builder()
                .identityPoolId(identityPoolId)
                .build()
        )

        // Fetch temporary credentials for that identity
        val credentialsRes = cognitoIdentity.getCredentialsForIdentity(
            GetCredentialsForIdentityRequest.builder()
                .identityId(idRes.identityId())
                .build()
        )

        val sessionCredentials = AwsSessionCredentials.create(
            credentialsRes.credentials().accessKeyId(),
            credentialsRes.credentials().secretKey(),
            credentialsRes.credentials().sessionToken()
        )

        val s3Client = S3Client.builder()
            .region(Region.EU_NORTH_1)
            .credentialsProvider(StaticCredentialsProvider.create(sessionCredentials))
            .build()

        // Upload file to S3
        s3Client.putObject(PutObjectRequest.builder()
            .bucket(bucketName)
            .key(key)
            .build(),
            RequestBody.fromFile(Paths.get(filePath))
        )

        // Create a presigner with the same region and credentials
        val presigner = S3Presigner.builder()
            .region(Region.EU_NORTH_1)
            .credentialsProvider(StaticCredentialsProvider.create(sessionCredentials))
            .build()

        // Create a presign request
        val getObjectRequest = GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofHours(24))
            .getObjectRequest(
                software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build()
            )
            .build()

        // Generate the presigned URL
        val presignedGetObjectRequest = presigner.presignGetObject(getObjectRequest)

        // Return the presigned URL
        return presignedGetObjectRequest.url().toString()
    }
}
