package ru.nsu.vrmp.shaman.recording

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Exports a recorded session ZIP via Android's standard `ACTION_SEND` share sheet. */
object SessionShareHelper {

    const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"

    fun createShareIntent(
        context: Context,
        zipFile: File,
        chooserTitle: CharSequence = "Экспорт сессии",
    ): Intent {
        val authority = "${context.packageName}$FILE_PROVIDER_AUTHORITY_SUFFIX"
        val uri = FileProvider.getUriForFile(context, authority, zipFile)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, zipFile.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(sendIntent, chooserTitle)
    }

    fun shareSession(
        context: Context,
        storage: AndroidSessionStorage,
        sessionDir: File,
        chooserTitle: CharSequence = "Экспорт сессии",
    ): File {
        val zipFile = storage.exportZip(sessionDir)
        context.startActivity(createShareIntent(context, zipFile, chooserTitle))
        return zipFile
    }
}
