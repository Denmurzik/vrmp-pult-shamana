package ru.nsu.vrmp.shaman.core.recording

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Packages a recorded session directory into a ZIP preserving `data/<kind>/<sessionId>/...`. */
object SessionArchive {

    fun zipSession(rootDir: File, sessionDir: File, outputZip: File): File =
        zipSession(rootDir.toPath(), sessionDir.toPath(), outputZip.toPath()).toFile()

    fun zipSession(rootDir: Path, sessionDir: Path, outputZip: Path): Path {
        val normalizedRoot = rootDir.toAbsolutePath().normalize()
        val normalizedSession = sessionDir.toAbsolutePath().normalize()
        require(Files.isDirectory(normalizedSession)) {
            "Session directory does not exist: $normalizedSession"
        }
        require(normalizedSession.startsWith(normalizedRoot)) {
            "Session directory ($normalizedSession) must be inside rootDir ($normalizedRoot)"
        }

        outputZip.parent?.let { Files.createDirectories(it) }

        val files = Files.walk(normalizedSession).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .sorted()
                .toList()
        }

        ZipOutputStream(Files.newOutputStream(outputZip)).use { zos ->
            for (file in files) {
                val relativePath = normalizedRoot.relativize(file)
                    .toString()
                    .replace(File.separatorChar, '/')
                zos.putNextEntry(ZipEntry(relativePath))
                Files.copy(file, zos)
                zos.closeEntry()
            }
        }
        return outputZip
    }
}
