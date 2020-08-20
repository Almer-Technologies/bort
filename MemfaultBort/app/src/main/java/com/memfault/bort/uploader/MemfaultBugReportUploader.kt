package com.memfault.bort.uploader

import com.memfault.bort.FileUploader
import com.memfault.bort.Logger
import com.memfault.bort.TaskResult
import com.memfault.bort.asResult
import retrofit2.HttpException
import java.io.File

internal class MemfaultBugReportUploader(
    private val preparedUploader: PreparedUploader
): FileUploader {

    override suspend fun upload(file: File): TaskResult {
        Logger.v("uploading $file")

        val prepareResponse = try {
            preparedUploader.prepare()
        } catch (e: HttpException) {
            Logger.e("prepare", e)
            return TaskResult.RETRY
        } catch (e: Exception) {
            Logger.e("prepare", e)
            return TaskResult.RETRY
        }

        when (val result = prepareResponse.asResult()) {
            TaskResult.RETRY -> return result
            TaskResult.FAILURE -> return result
        }

        // Re-try for unexpected server-side response
        val prepareData = prepareResponse.body()?.data ?: return TaskResult.RETRY

        try {
            when (val result = preparedUploader.upload(file, prepareData.upload_url).asResult()) {
                TaskResult.RETRY -> return result
                TaskResult.FAILURE -> return result
            }
        } catch (e: HttpException) {
            Logger.e("upload", e)
            return TaskResult.RETRY
        } catch (e: Exception) {
            Logger.e("upload", e)
            return TaskResult.RETRY
        }

        try {
            when (val result = preparedUploader.commitBugreport(prepareData.token).asResult()) {
                TaskResult.RETRY -> return result
                TaskResult.FAILURE -> return result
            }
        } catch (e: HttpException) {
            Logger.e("commit", e)
            return TaskResult.RETRY
        } catch (e: Exception) {
            Logger.e("commit", e)
            return TaskResult.RETRY
        }

        return TaskResult.SUCCESS
    }

}