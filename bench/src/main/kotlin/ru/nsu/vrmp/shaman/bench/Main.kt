package ru.nsu.vrmp.shaman.bench

import kotlin.system.exitProcess

/** Offline benchmark CLI entry point (`:bench`). */
fun main(args: Array<String>) {
    if (args.isEmpty() || args.any { it == "--help" || it == "-h" }) {
        println(BenchCli.USAGE)
        println("Available recognizers: ${RecognizerRegistry.default().availableIds().sorted().joinToString()}")
        return
    }
    try {
        val report = BenchCli.run(args)
        println(BenchCli.formatSummary(report))
    } catch (e: IllegalArgumentException) {
        System.err.println("Error: ${e.message}")
        exitProcess(1)
    }
}
