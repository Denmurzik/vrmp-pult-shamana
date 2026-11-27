package ru.nsu.vrmp.shaman.bench

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.nsu.vrmp.shaman.core.input.HandFrame
import ru.nsu.vrmp.shaman.core.input.ImuFrame
import ru.nsu.vrmp.shaman.core.math.Quat
import ru.nsu.vrmp.shaman.core.math.Vec3
import ru.nsu.vrmp.shaman.core.recognition.Gesture
import java.nio.file.Files
import java.nio.file.Path

class RecordingLoaderTest {

    @Test
    fun `loads meta imu hand labels and merges frames chronologically`(@TempDir tempDir: Path) {
        val sessionDir = tempDir.resolve("session_01")
        Files.createDirectories(sessionDir)

        Files.writeString(
            sessionDir.resolve("meta.json"),
            """
            {
              "session_id": "s01",
              "participant_id": "P03",
              "condition": "dataset",
              "seed": 101,
              "device": "Pixel 8"
            }
            """.trimIndent(),
        )

        Files.writeString(
            sessionDir.resolve("imu.csv"),
            """
            t_ns,ax,ay,az,gx,gy,gz,qw,qx,qy,qz
            1000,1.5,-2.0,3.25,0.1,0.2,0.3,1.0,0.0,0.0,0.0
            3000,0.0,0.0,0.0,0.0,0.0,0.0,0.7071,0.0,0.0,0.7071
            """.trimIndent(),
        )

        val handHeader = buildString {
            append("t_ns,present")
            for (i in 0 until 21) append(",x$i,y$i,z$i")
        }
        val presentCoords = (0 until 21).joinToString(",") { i -> "${i * 0.01},${i * 0.02},${-i * 0.005}" }
        val absentEmpty = ",".repeat(63)
        Files.writeString(
            sessionDir.resolve("hand.csv"),
            """
            $handHeader
            2000,true,$presentCoords
            2500,false$absentEmpty
            """.trimIndent(),
        )

        Files.writeString(
            sessionDir.resolve("labels.csv"),
            """
            t_start_ns,t_end_ns,gesture,speed
            1000,2500,CATCH,fast
            2600,3000,null,normal
            """.trimIndent(),
        )

        val session = RecordingLoader.loadSession(sessionDir)

        assertEquals("s01", session.sessionId)
        assertEquals("P03", session.meta.participantId)
        assertEquals("dataset", session.meta.condition)
        assertEquals(101L, session.meta.seed)
        assertEquals("Pixel 8", session.meta.extras["device"])
        assertFalse(session.isNullStream)

        // Merged frames in chronological order: 1000 (IMU), 2000 (Hand present), 2500 (Hand absent), 3000 (IMU)
        assertEquals(4, session.frames.size)
        assertEquals(listOf(1000L, 2000L, 2500L, 3000L), session.frames.map { it.tNanos })

        val imu0 = session.frames[0] as ImuFrame
        assertEquals(Vec3(1.5, -2.0, 3.25), imu0.linAcc)
        assertEquals(Vec3(0.1, 0.2, 0.3), imu0.gyro)
        assertEquals(Quat(1.0, 0.0, 0.0, 0.0), imu0.rotation)

        val handPresent = session.frames[1] as HandFrame
        assertTrue(handPresent.present)
        assertEquals(21, handPresent.landmarks.size)
        assertEquals(Vec3(0.1, 0.2, -0.05), handPresent.landmarks[10])

        val handAbsent = session.frames[2] as HandFrame
        assertFalse(handAbsent.present)
        assertTrue(handAbsent.landmarks.isEmpty())

        // "null" row is ignored, only CATCH remains
        assertEquals(1, session.labels.size)
        assertEquals(LabelInterval(1000L, 2500L, Gesture.CATCH, GestureSpeed.FAST), session.labels[0])
    }

    @Test
    fun `treats missing or empty labels as null stream and discovers nested sessions`(@TempDir tempDir: Path) {
        val s1 = tempDir.resolve("null_sessions/n01")
        val s2 = tempDir.resolve("labeled/l01")
        Files.createDirectories(s1)
        Files.createDirectories(s2)

        Files.writeString(s1.resolve("meta.json"), """{"participant_id":"P01","condition":"null"}""")
        Files.writeString(
            s1.resolve("imu.csv"),
            """
            t_ns,ax,ay,az,gx,gy,gz,qw,qx,qy,qz
            0,0,0,0,0,0,0,1,0,0,0
            60000000000,0,0,0,0,0,0,1,0,0,0
            """.trimIndent(),
        )

        Files.writeString(s2.resolve("meta.json"), """{"participant_id":"P02","condition":"dataset"}""")
        Files.writeString(
            s2.resolve("imu.csv"),
            """
            t_ns,ax,ay,az,gx,gy,gz,qw,qx,qy,qz
            1000,0,0,0,0,0,0,1,0,0,0
            """.trimIndent(),
        )
        Files.writeString(
            s2.resolve("labels.csv"),
            """
            t_start_ns,t_end_ns,gesture,speed
            1000,2000,BANISH,slow
            """.trimIndent(),
        )

        val sessions = RecordingLoader.loadDataset(tempDir)
        assertEquals(2, sessions.size)

        val labeled = sessions.first { it.meta.participantId == "P02" }
        val nullSession = sessions.first { it.meta.participantId == "P01" }

        assertFalse(labeled.isNullStream)
        assertTrue(nullSession.isNullStream)
        assertEquals(60_000_000_000L, nullSession.durationNanos)
    }
}
