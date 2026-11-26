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
    val landmarks: List<Vec3>, // 21 точка MediaPipe, нормированные координаты
) : InputFrame
data class TouchFrame(override val tNanos: Long, val action: TouchAction) : InputFrame

enum class Gesture { CATCH, BANISH, CANCEL }

sealed interface RecognizerEvent {
    data class Candidate(val gesture: Gesture, val tNanos: Long) : RecognizerEvent
    data class Detected(
        val gesture: Gesture, val confidence: Double,
        val tStart: Long, val tEnd: Long, val tEmit: Long,
    ) : RecognizerEvent
    data class Rejected(val tNanos: Long, val reason: String) : RecognizerEvent
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

**Тач** (`TouchRecognizer`): преобразует нажатия на кнопки в события арбитра. Свайп
меняет направление сканирования через отдельный трекер.

### Камерный распознаватель

`CameraRecognizer` обрабатывает `HandFrame` с 21 точкой MediaPipe. Длина от запястья до основания
среднего пальца задаёт масштаб движения. Распознаватель передаёт события через общий интерфейс
и использует время отсчётов.

- **Кулак.** Для каждого из четырёх пальцев сравниваются прямая и ломаная от основания до
  кончика. Открытая ладонь подготавливает распознавание. Сгибание всех четырёх пальцев
  сначала даёт кандидата, затем подтверждает поимку на более высоком пороге. Повторная
  поимка возможна после раскрытия ладони. Незавершённое сгибание отклоняется
  через 700 мс. Подтверждённый взмах или круг также снимает подготовку поимки.
- **Круг.** Алгоритм накапливает угол движения кончика указательного вокруг центроида
  траектории в плоскости кадра. Изгнание подтверждается при повороте не менее 300° и
  сближении концов траектории в окне до 1,5 с. Учитываются оба направления вращения.
- **Взмах.** Движение центра ладони преимущественно по горизонтали даёт отмену после
  двух значимых смен направления за 800 мс. Мелкие перемещения отфильтровываются
  по масштабу ладони.

При потере руки более чем на 300 мс распознаватель отклоняет активного кандидата, очищает
историю движения и ждёт свежих кадров. Пороги заданы для первой реализации, их работу на
реальных движениях проверяет офлайн-бенчмарк. [Модель точек MediaPipe](https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker)

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
