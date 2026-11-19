package ru.nsu.vrmp.shaman.core.recognition

/** Discrete game commands. Scanning is continuous and handled separately (heading tracking). */
enum class Gesture {
    /** Catch a wandering spirit: IMU — upward flick; camera — palm closes into a fist. */
    CATCH,

    /** Banish an evil spirit: IMU — circle with the phone; camera — circle with the index fingertip. */
    BANISH,

    /** Undo the last command within the undo window: IMU — shake; camera — wave. */
    CANCEL,
}
