package ru.nsu.vrmp.shaman.calibration

import android.content.Context
import ru.nsu.vrmp.shaman.core.calibration.CalibrationProfile
import ru.nsu.vrmp.shaman.core.calibration.CalibrationProfileCodec
import java.io.File

interface CalibrationStore {
    fun save(profile: CalibrationProfile)
    fun load(): CalibrationProfile?
}

class InMemoryCalibrationStore : CalibrationStore {
    private var stored: CalibrationProfile? = null
    override fun save(profile: CalibrationProfile) {
        stored = profile
    }

    override fun load(): CalibrationProfile? = stored
}

/** Persists the active [CalibrationProfile] in app-private storage for DTW and HeadingTracker. */
class FileCalibrationStore(context: Context) : CalibrationStore {
    private val file = File(context.filesDir, "calibration_profile.dat")

    override fun save(profile: CalibrationProfile) {
        file.writeText(CalibrationProfileCodec.encode(profile))
    }

    override fun load(): CalibrationProfile? {
        if (!file.exists()) return null
        return runCatching { CalibrationProfileCodec.decode(file.readText()) }.getOrNull()
    }
}
