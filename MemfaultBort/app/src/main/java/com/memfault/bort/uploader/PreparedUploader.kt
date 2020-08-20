package com.memfault.bort.uploader

import com.memfault.bort.Logger
import com.memfault.bort.http.ProjectKeyAuthenticated
import kotlinx.serialization.Serializable
import okhttp3.MediaType
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*
import java.io.File
import kotlin.String

@Serializable
data class PrepareResponseData(
    val upload_url: String,
    val token: String
)

@Serializable
data class PrepareResult(
    val data: PrepareResponseData
)

@Serializable
data class CommitFileToken(
    val token: String
)

@Serializable
data class CommitRequest(
    val file: CommitFileToken
)

interface PreparedUploadService {
    @POST("/api/v0/upload")
    @ProjectKeyAuthenticated
    suspend fun prepare(): Response<PrepareResult>

    @PUT
    suspend fun upload(@Url url: String, @Body file: RequestBody): Response<Unit>

    @POST("/api/v0/upload/{upload_type}")
    @ProjectKeyAuthenticated
    suspend fun commit(
        @Path(value = "upload_type") uploadType: String,
        @Body commitRequest: CommitRequest
    ): Response<Unit>
}

// Work around since vararg's can't be used in lambdas
internal interface UploadEventLogger {
    fun log(vararg strings: String) = Logger.logEvent(*strings)
}

/**
 * A client to upload files to Memfault's ("prepared") file upload API.
 */
internal class PreparedUploader(
    private val preparedUploadService: PreparedUploadService,
    private val eventLogger: UploadEventLogger = object : UploadEventLogger {}
) {

    /**
     * Prepare a file upload.
     */
    suspend fun prepare(): Response<PrepareResult> = preparedUploadService.prepare().also {
            eventLogger.log("prepare")
    }

    /**
     * Upload a prepared file.
     */
    suspend fun upload(file: File, uploadUrl: String): Response<Unit> {
        val requestBody = RequestBody.create(MediaType.get("application/octet-stream"), file)
        return preparedUploadService.upload(
            uploadUrl,
            requestBody
        ).also {
            eventLogger.log("upload", "done")
        }
    }

    /**
     * Commit an uploaded bug report.
     */
    suspend fun commitBugreport(token: String): Response<Unit> =
        preparedUploadService.commit(
            uploadType = "bugreport",
            commitRequest = CommitRequest(
                CommitFileToken(
                    token
                )
            )
        ).also {
            eventLogger.log("commit", "done")
        }
}
