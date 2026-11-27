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
- Скан (ориентация → азимут) — `HeadingTracker` (`core/.../orientation/HeadingTracker.kt`), общий для всех IMU/камерных условий:
  переводит кватернион устройство→мир (`GAME_ROTATION_VECTOR`) в относительный азимут в `[-π, π)` (положительный — по часовой стрелке),
  поддерживает калибровку нуля (`calibrateZero`, совместим с `CalibrationProfile.zeroHeadingRotation`) и устойчив к наклону телефона
  (проекция `+Y` на горизонтальную плоскость с плавным переходом на `∓Z` вблизи вертикали).
- `CommandArbiter` реализует состояния из [SPEC.md](SPEC.md#3-жизненный-цикл-команды): окно отмены, рефрактерный период.
- `GameEngine` (`core/.../game/GameEngine.kt`, `GameModels.kt`) — детерминированная логика раунда:
  спавн духов по фиксированному сиду (`SpiritSpawner`), видимость только в конусе сканера
  (±15°, при тумане ±7.5°), проверка команд и откат по `commandId`, жизни/мана/ловушки/атаки
  и их отключение в `experimentMode`.
- Экран игры и тач-управление (`app/.../ui/game/GameScreen.kt`, `RadarArcCanvas.kt`, `game/GameController.kt`,
  `game/TouchCommandArbiter.kt`, `ui/theme/Theme.kt`): тема Material 3 Expressive (`ShamanTheme`),
  радар-дуга с отображением только духов в конусе и поворотом свайпом, кнопки «Поймать» / «Изгнать» / «Отмена»,
  маршрутизация тача через `TouchRecognizer` и локальный `TouchCommandArbiter` (временно до слияния `#3`).
- Обратная связь (`core/.../feedback/FeedbackPlanner.kt`, `app/.../feedback/AndroidFeedbackPlayer.kt`):
  расчёт интервала, амплитуды и тона ∝ близости луча к духу, паттерны откликов `CANDIDATE` / `CONFIRMED` /
  `CANCELLED` / `ERROR` / `ATTACK`, воспроизведение через `Vibrator` и синтез тонов в `AudioTrack`.
- Тренажёр жестов (`core/.../training/GestureTrainer.kt`, `app/.../training/TrainingController.kt`,
  `app/.../ui/training/TrainingScreen.kt`): 3 успешных повтора на каждый жест с подсказками и подсчётом попыток.
- Режим эксперимента (`core/.../experiment/ExperimentProtocol.kt`, `app/.../experiment/ExperimentController.kt`,
  `app/.../ui/experiment/ExperimentScreen.kt`): сбалансированный латинский квадрат Уильямса 4×4, сиды по позиции,
  отключение доп. событий и единый `ExperimentLogSink` (для подключения `SessionRecorder` из `#8`).
- Калибровка (`core/.../calibration/CalibrationManager.kt`, `app/.../calibration/CalibrationController.kt`,
  `FileCalibrationStore`, `ImuSampler`, `app/.../ui/calibration/CalibrationScreen.kt`): нулевое направление,
  запись по 5 эталонов каждого IMU-жеста для DTW, проверка камеры и освещения, сохранение профиля в `filesDir`.

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
  кончика. Открытая ладонь подготавливает распознавание на 700 мс. Сгибание всех четырёх пальцев
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

## Вход с фронтальной камеры

`HandCamera` привязывает CameraX ImageAnalysis к жизненному циклу Android и отправляет
кадры в MediaPipe Hand Landmarker в режиме LIVE_STREAM. Один запрос находится в обработке
одновременно. Если результат не приходит за секунду, следующий кадр заменяет ожидающий.
Запоздалый ответ не сопоставляется с новым кадром. Каждый принятый результат преобразуется в `HandFrame` с 21 точкой либо
с признаком отсутствия кисти. Наносекунды кадра сохраняются отдельно от метки MediaPipe
в миллисекундах. При Camera2 REALTIME время совпадает со шкалой `elapsedRealtimeNanos`.
Для UNKNOWN используется оценка смещения по первому кадру. Интерфейс помечает такой
поток как непригодный для синхронного сбора IMU и камеры.

Экран калибровки показывает зеркальный предпросмотр, скелет и частоту обработанных кадров
за последнюю секунду. Предпросмотр хранит отдельную копию кадра в памяти. Модель Hand Landmarker Full FP16
поставляется в APK. SHA-256 `fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1`.
[Модель и лицензия Apache-2.0](https://huggingface.co/litert-community/MediaPipe-HandLandmarker-Full)

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
