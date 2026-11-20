# Архитектура

## Модули

```
vrmp/
├── core/        Чистый Kotlin (JVM), без Android. Обработка сигналов, распознаватели,
│                CommandArbiter, игровая логика (правила, духи, сиды), форматы записей.
├── app/         Android-приложение: Compose UI, датчики, CameraX + MediaPipe,
│                вибрация/звук, запись сессий, режимы игры/эксперимента/датасета.
├── bench/       JVM CLI: прогоняет распознаватели из :core по записям, пишет метрики в CSV.
├── analysis/    Python: статистика, графики, ноутбуки.
├── data/        Записи (в .gitignore, кроме маленьких сэмплов в data/samples/).
└── docs/        Спецификация, исследование, архитектура, план, решения.
```

Главное правило: **всё, что влияет на результат распознавания, живёт в `:core`.**
Тогда офлайн-бенчмарк проверяет ровно тот код, что работает на телефоне.

## Поток данных

```
Android sensors ─┐
CameraX→MediaPipe┼─► InputFrame ─► GestureRecognizer ─► RecognizerEvent ─► CommandArbiter ─► GameCommand ─► GameEngine
Touch UI ────────┘        │                                                       │
                          └──────────────► SessionRecorder ◄──────────────────────┘
```

## Ключевые интерфейсы (`:core`)

Ниже приведён существующий контракт ввода и распознавания. Операции проектируемых
арбитра и движка описаны в [ТЗ](stage2-3/TZ.md#42-api-ядра).

```kotlin
sealed interface InputFrame { val tNanos: Long }
data class ImuFrame(
    override val tNanos: Long,
    val linAcc: Vec3,      // m/s², без гравитации, система координат устройства
    val gyro: Vec3,        // rad/s
    val rotation: Quat,    // GAME_ROTATION_VECTOR
) : InputFrame
data class HandFrame(
    override val tNanos: Long,
    val present: Boolean,
    val landmarks: List<Vec3>, // 21 точка MediaPipe при наличии руки, иначе пустой список
) : InputFrame
data class TouchFrame(override val tNanos: Long, val action: TouchAction) : InputFrame

enum class Gesture { CATCH, BANISH, CANCEL }

sealed interface RecognizerEvent {
    val tNanos: Long
    data class Candidate(val gesture: Gesture, override val tNanos: Long) : RecognizerEvent
    data class Detected(
        val gesture: Gesture, val confidence: Double,
        val tStart: Long, val tEnd: Long, val tEmit: Long,
    ) : RecognizerEvent {
        override val tNanos: Long get() = tEmit
    }
    data class Rejected(override val tNanos: Long, val reason: String) : RecognizerEvent
}

interface GestureRecognizer {
    val id: String                  // "threshold", "dtw", "camera", "touch"
    fun reset()
    fun onFrame(frame: InputFrame): List<RecognizerEvent>
}
```

- Распознаватели **детерминированы**: одинаковый поток кадров → одинаковые события.
  Никаких `System.currentTimeMillis()` внутри — только `tNanos` из кадров.
- Скан (ориентация → азимут) — отдельный `HeadingTracker`, общий для всех IMU/камерных условий.
- `CommandArbiter` реализует состояния из [SPEC.md](SPEC.md#3-жизненный-цикл-команды): окно отмены, рефрактерный период.

## Распознаватели

**Пороги** (`ThresholdRecognizer`): сглаживание (EMA), пороги начала/конца с гистерезисом,
минимальная/максимальная длительность. Рывок — пик `linAcc` вдоль оси вверх; встряхивание —
≥ 3 смены знака сильного ускорения за 600 мс; круг — накопленный угол гироскопа вокруг нормали экрана ≥ 300° за ≤ 1.5 с.

**DTW** (`DtwRecognizer`): сегментация по энергии движения с гистерезисом → признаки
(`linAcc` + `gyro`, z-нормализация) → DTW с полосой Сакое–Чиба до каждого эталона → 1-NN,
отказ (null) при расстоянии выше порога. Реализация своя, в `:core`.

**Камера** (`CameraRecognizer`): вход — `HandFrame` из MediaPipe. Кулак — коэффициент сгибания
пальцев с гистерезисом (переход ладонь → кулак); круг — накопленный угол траектории кончика
указательного вокруг её центроида; взмах — ≥ 2 смены направления ладони по X за 800 мс.
Потеря руки > 300 мс сбрасывает кандидата.

**Тач** (`TouchRecognizer`): преобразует кнопки/свайп в те же события — чтобы логирование и арбитр были общими.

## Формат записей

Одна сессия = одна папка `data/<kind>/<session_id>/`:

| Файл | Содержимое |
|---|---|
| `meta.json` | ID участника (`P01`), условие, сид, устройство, версия приложения, git SHA, параметры распознавателей |
| `imu.csv` | `t_ns,ax,ay,az,gx,gy,gz,qw,qx,qy,qz` |
| `hand.csv` | `t_ns,present,x0,y0,z0,…,x20,y20,z20` |
| `labels.csv` | (датасет) `t_start_ns,t_end_ns,gesture,speed` |
| `events.jsonl` | события распознавателя, арбитра и игры с `t_ns` |

Время — `SensorEvent.timestamp` / таймстемп кадра камеры в одной шкале (`elapsedRealtimeNanos`).
Персональных данных в записях нет: только анонимный ID.

## Сборка и проверки

- Gradle (Kotlin DSL), version catalog `gradle/libs.versions.toml`.
- `./gradlew :core:test` — юнит-тесты (JUnit 6), обязательны для распознавателей и арбитра.
- `./gradlew :bench:run --args="..."` — офлайн-прогон.
- `./gradlew :app:assembleDebug` — APK.
- `analysis/` — Python 3.12, зависимости в `analysis/requirements.txt`.
