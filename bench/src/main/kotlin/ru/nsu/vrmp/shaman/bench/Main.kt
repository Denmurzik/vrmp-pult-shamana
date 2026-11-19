package ru.nsu.vrmp.shaman.bench

import ru.nsu.vrmp.shaman.core.recognition.Gesture

/** Offline benchmark entry point. The real pipeline over recordings comes in #15. */
fun main(args: Array<String>) {
    println("vrmp bench: offline recognizer evaluation (not implemented yet, see issue #15)")
    println("gestures: ${Gesture.entries.joinToString()}")
    if (args.isNotEmpty()) println("args: ${args.joinToString(" ")}")
}
