package ru.nsu.vrmp.shaman.bench

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BenchCliTest {

    @Test
    fun `parses cli arguments and rejects unknown recognizer`(@TempDir tempDir: Path) {
        val parsed = BenchCli.parseArgs(
            arrayOf(
                "--input", tempDir.toString(),
                "--recognizer", "touch",
                "--out", tempDir.resolve("out").toString(),
                "--mode", "loso",
                "--tolerance-ms", "750",
            ),
        )
        assertEquals(tempDir, parsed.inputDir)
        assertEquals("touch", parsed.recognizerId)
        assertEquals(EvaluationMode.LOSO, parsed.mode)
        assertEquals(750L, parsed.toleranceMs)

        val registry = RecognizerRegistry.default()
        assertTrue("touch" in registry.availableIds())
        assertThrows(IllegalArgumentException::class.java) {
            registry.create("nonexistent")
        }
    }

    @Test
    fun `runs end to end and writes valid Locale US csv reports`(@TempDir tempDir: Path) {
        val sessionDir = tempDir.resolve("sessions/s01")
        Files.createDirectories(sessionDir)
        Files.writeString(sessionDir.resolve("meta.json"), """{"session_id":"s01","participant_id":"P01","condition":"dataset"}""")
        Files.writeString(
            sessionDir.resolve("imu.csv"),
            """
            t_ns,ax,ay,az,gx,gy,gz,qw,qx,qy,qz
            1000000000,0.0,0.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0
            2000000000,0.0,1.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0
            """.trimIndent(),
        )
        Files.writeString(
            sessionDir.resolve("labels.csv"),
            """
            t_start_ns,t_end_ns,gesture,speed
            1000000000,1500000000,CATCH,normal
            """.trimIndent(),
        )

        val outDir = tempDir.resolve("out")
        val report = BenchCli.run(
            arrayOf(
                "--input", tempDir.resolve("sessions").toString(),
                "--recognizer", "touch",
                "--out", outDir.toString(),
                "--mode", "standard",
                "--tolerance-ms", "500",
            ),
        )

        assertEquals("touch", report.recognizerId)
        assertEquals(2L, report.cpu.totalFrames)

        val metricsCsv = outDir.resolve("metrics.csv")
        val cmCsv = outDir.resolve("confusion_matrix.csv")
        val speedCsv = outDir.resolve("speed_f1.csv")

        assertTrue(Files.exists(metricsCsv))
        assertTrue(Files.exists(cmCsv))
        assertTrue(Files.exists(speedCsv))

        val metricsLines = Files.readAllLines(metricsCsv)
        assertEquals(5, metricsLines.size) // header + CATCH, BANISH, CANCEL, MACRO
        assertTrue(metricsLines[0].startsWith("recognizer,mode,class,tp,fp,fn,precision,recall,f1"))
        assertTrue(metricsLines.any { it.startsWith("touch,standard,MACRO,") })
        // Locale.US dot decimal check (no stray comma-decimals breaking column count)
        val expectedCols = metricsLines[0].split(',').size
        assertTrue(metricsLines.drop(1).all { it.split(',').size == expectedCols })

        val cmLines = Files.readAllLines(cmCsv)
        assertEquals(5, cmLines.size) // header + CATCH, BANISH, CANCEL, NONE
        assertEquals("true_class,CATCH,BANISH,CANCEL,NONE", cmLines[0])

        val speedLines = Files.readAllLines(speedCsv)
        assertEquals(4, speedLines.size) // header + SLOW, NORMAL, FAST
        assertTrue(speedLines[0].startsWith("recognizer,mode,speed,support,tp,fp,fn,precision,recall,f1"))
    }
}
