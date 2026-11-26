package ru.nsu.vrmp.shaman.bench

import ru.nsu.vrmp.shaman.core.recognition.GestureRecognizer
import ru.nsu.vrmp.shaman.core.recognition.TouchRecognizer

/** Creates a fresh [GestureRecognizer], optionally initialized with DTW templates. */
fun interface RecognizerFactory {
    fun create(templates: List<TemplateSegment>): GestureRecognizer
}

/** Registry mapping CLI recognizer IDs to [RecognizerFactory] instances. */
class RecognizerRegistry {
    private val factories = linkedMapOf<String, RecognizerFactory>()

    fun register(id: String, factory: RecognizerFactory) {
        val normalized = id.trim().lowercase()
        require(normalized.isNotEmpty()) { "Recognizer id must not be blank" }
        factories[normalized] = factory
    }

    fun factoryFor(id: String): RecognizerFactory {
        val normalized = id.trim().lowercase()
        return factories[normalized]
            ?: throw IllegalArgumentException(
                "Unknown recognizer '$id'. Available: ${availableIds().sorted().joinToString()}",
            )
    }

    fun create(id: String, templates: List<TemplateSegment> = emptyList()): GestureRecognizer =
        factoryFor(id).create(templates)

    fun availableIds(): Set<String> = factories.keys.toSet()

    companion object {
        fun default(): RecognizerRegistry = RecognizerRegistry().apply {
            register("touch") { TouchRecognizer() }
        }
    }
}
