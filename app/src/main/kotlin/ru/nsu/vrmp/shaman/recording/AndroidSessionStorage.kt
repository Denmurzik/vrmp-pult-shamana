package ru.nsu.vrmp.shaman.recording

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import ru.nsu.vrmp.shaman.core.recording.SessionArchive
import ru.nsu.vrmp.shaman.core.recording.SessionKind
import ru.nsu.vrmp.shaman.core.recording.SessionMeta
import ru.nsu.vrmp.shaman.core.recording.SessionRecorder
import java.io.File

/**
 * Thin Android storage adapter over `:core` [SessionRecorder] and [SessionArchive].
 *
 * Sessions are stored in app-private `filesDir/sessions/data/<kind>/<session_id>/`
 * and can be exported via Android Share Sheet ([SessionShareHelper]) or pulled on debug
 * builds via `adb exec-out run-as <package> tar -cf - -C files/sessions data > sessions.tar`.
 */
class AndroidSessionStorage(private val context: Context) {

    val rootDir: File = File(context.filesDir, "sessions")
    val exportDir: File = File(context.cacheDir, "exports")

    fun openRecorder(
        meta: SessionMeta,
        writeLabelsFile: Boolean = (meta.kind == SessionKind.DATASET),
    ): SessionRecorder {
        rootDir.mkdirs()
        return SessionRecorder(rootDir = rootDir, meta = meta, writeLabelsFile = writeLabelsFile)
    }

    fun listSessionDirs(): List<File> {
        val dataDir = File(rootDir, "data")
        if (!dataDir.isDirectory) return emptyList()
        return SessionKind.entries
            .flatMap { kind ->
                File(dataDir, kind.dirName)
                    .listFiles { file -> file.isDirectory && File(file, SessionRecorder.META_FILE).isFile }
                    ?.toList()
                    .orEmpty()
            }
            .sortedBy { it.name }
    }

    fun exportZip(sessionDir: File): File {
        exportDir.mkdirs()
        val zipFile = File(exportDir, "${sessionDir.name}.zip")
        return SessionArchive.zipSession(rootDir = rootDir, sessionDir = sessionDir, outputZip = zipFile)
    }

    fun adbPullCommand(packageName: String = context.packageName): String =
        "adb exec-out run-as $packageName tar -cf - -C files/sessions data > sessions.tar"

    companion object {
        fun defaultDeviceMeta(
            context: Context,
            sessionId: String,
            kind: SessionKind,
            participantId: String? = null,
            condition: String? = null,
            seed: Long? = null,
            gitSha: String = "unknown",
            recognizerParams: Map<String, String> = emptyMap(),
        ): SessionMeta {
            val manufacturer = Build.MANUFACTURER.orEmpty().trim()
            val model = Build.MODEL.orEmpty().trim()
            val deviceName = listOf(manufacturer, model)
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(" ")
                .ifEmpty { "Android" }

            val versionName = runCatching {
                val pm = context.packageManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(context.packageName, 0).versionName
                }
            }.getOrNull().orEmpty().ifEmpty { "0.1.0" }

            return SessionMeta(
                sessionId = sessionId,
                kind = kind,
                participantId = participantId,
                condition = condition,
                seed = seed,
                device = deviceName,
                appVersion = versionName,
                gitSha = gitSha,
                recognizerParams = recognizerParams,
            )
        }
    }
}
