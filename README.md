📱 FlipToFocus

Flip your phone to start focusing.

FlipToFocus is a modern focus productivity app built entirely with Jetpack Compose. It combines Timer, Pomodoro, and Stopwatch modes with sensor-based interaction and smooth animated UI to create a distraction-free and immersive focus experience.

✨ Features

⏱ Timer Mode – Customizable countdown sessions

🍅 Pomodoro Mode – Focus and break cycles with round tracking

⏳ Stopwatch Mode – Track elapsed focus time

📲 Flip-to-Start Detection – Uses accelerometer to start sessions naturally

🔕 Do Not Disturb Toggle – Minimize interruptions during focus

🎵 Ambient Noise Selection – Background sound support

🎨 Animated UI – 3D pager transitions and smooth state-based animations

📊 Session Tracking – View focus statistics

🌙 Distraction-Free Design – UI adapts to focus state

🛠 Tech Stack

Kotlin

Jetpack Compose

Coroutines & LifecycleScope

SensorManager (Accelerometer)

State-driven UI architecture

Material 3 Components

🧠 Architecture Overview

The app follows a state-driven architecture, where UI reacts to TimerState and AppMode.

Core concepts:

Single source of truth for timer state

Lifecycle-aware coroutines for time tracking

Sensor-based interaction logic

Animated state transitions using Compose APIs

Modular composables for each UI section

🚀 How It Works

Select a mode (Timer / Pomodoro / Stopwatch)

Adjust settings (duration, sound, DND)

Flip your phone face down to start focusing

The UI transitions into a minimal, distraction-free state

On completion or interruption, state updates dynamically


🔮 Future Improvements

Pause / Resume functionality

Daily / Weekly analytics charts

Wear OS support

Cloud sync
