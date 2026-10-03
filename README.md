# Gemini Live Client — Ultra Low-Latency Full-Duplex AI Voice Assistant

An ultra-optimized, native Android client engineered to replicate the exact client-side architecture and performance of the official **Gemini Live** conversational experience.

Unlike traditional voice bots that record audio, wait for a transcription (ASR), send text to an LLM, and finally generate speech (TTS) through sequential HTTP REST requests, this app establishes a continuous, bidirectional, full-duplex WebSocket stream directly to Google's **Gemini Live API** (`models/gemini-3.8-live`).

---

## ⚡ The 7 Gemini Live Client-Side Architecture Tasks

This client implements all 7 core hardware & DSP tasks that run on the phone:

| Task | Component | Implementation Details |
|------|-----------|------------------------|
| **1. Hardware Echo Canceler (AEC)** | `GeminiAudioEngine` | Uses Android's native hardware `AcousticEchoCanceler` attached to the `VOICE_COMMUNICATION` session. The phone's baseband/telephony DSP subtracts speaker audio from the microphone in hardware before samples reach CPU. |
| **2. Multi-Mic Noise Suppression & AGC** | `GeminiAudioEngine` | Attaches Android's hardware `NoiseSuppressor` (beamforming + spectral subtraction) and `AutomaticGainControl` (AGC), combined with a software RMS normalizer so whispers and loud speech arrive at consistent levels. |
| **3. Ultra Low-Latency Audio Pipeline** | `GeminiAudioEngine` | Bypasses standard JVM audio latency by using `VOICE_COMMUNICATION` source with minimal buffer sizes (10ms frames = 160 samples @ 16kHz) and `AudioTrack` on the `PERFORMANCE_MODE_LOW_LATENCY` + `USAGE_ASSISTANT` fast-mixer path on real-time urgent threads (`THREAD_PRIORITY_URGENT_AUDIO`). |
| **4. Full-Duplex Bi-Directional Streaming** | `GeminiLiveWebSocketClient` | Simultaneous upload of 16kHz 16-bit PCM and reception of 24kHz PCM chunks over persistent WebSockets. Audio frames are streamed every 10ms with zero file buffering. |
| **5. Adaptive Jitter Buffer & DAC Drain Sync** | `GeminiAudioEngine` | Incoming server PCM chunks enter a thread-safe jitter buffer. Playback monitors the hardware `playbackHeadPosition` to ensure the exact last sample is emitted before ending turns. |
| **6. Zero-Latency Local Barge-In Kill-Switch** | `GeminiAudioEngine` + `GeminiLiveViewModel` | Real-time RMS VAD threshold runs continuously on the capture thread. If the user begins speaking while Gemini is talking, playback is killed locally in **<1ms** with zero network round-trip delay, followed by an immediate server interrupt signal. |
| **7. Real-Time Waveform & Haptics** | `WaveformVisualizer` + `GeminiLiveViewModel` | Computes 64 perceptual energy bands for every 10ms frame using a lightweight RMS + zero-crossing filterbank (deliberately *not* an FFT — see `computeEnergyBands`) and renders a radial glowing orb visualizer on the GPU via Compose Canvas. Dispatches tactile haptic pulses on connection, speech onset, and interrupts. |

---

## 🚀 Getting Started

### 1. Requirements
- Android 8.0+ (API 26+)
- Microphone permission (`RECORD_AUDIO`)
- A free Google Gemini API key from [Google AI Studio](https://aistudio.google.com/)

### 2. Quick Setup
1. Launch the app on your Android device.
2. Tap the **Settings** icon (top-right).
3. Paste your Google AI Studio API key.
4. (Optional) Customize the AI persona/system prompt.
5. Check the **Hardware Diagnostics** section to verify your device's DSP capabilities (AEC, Noise Suppressor, AGC).
6. Tap **Save Configuration** and return to the main screen.
7. Tap the microphone orb to begin speaking in real time!

---

## 🛠️ Building from Source

### Local Build (with Android Studio or command line):
```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease
```
The output APK will be generated at:
`app/build/outputs/apk/release/app-release.apk`

### Automated Cloud Build (GitHub Actions):
Push to any branch or trigger `workflow_dispatch`. GitHub Actions will automatically compile, sign, and upload the ready-to-install APK to the job artifacts.

### Signing credentials (never committed):
Release signing reads `KEYSTORE_PASSWORD`, `KEY_PASSWORD` and `KEY_ALIAS` from the
environment or Gradle properties. If no keystore/credentials are available the
release build falls back to debug signing.

| Environment | How to supply |
|---|---|
| GitHub Actions | Repository secrets (optional `KEYSTORE_BASE64` to restore a stable keystore). Without them a throwaway keystore is generated with a random password. |
| Local | `KEYSTORE_PASSWORD=...` etc. in the git-ignored `local.properties`, or `./gradlew assembleRelease -PKEYSTORE_PASSWORD=...` |

The Python protocol-test scripts (`scratch_tone.py`, `test_stream_speech.py`, `test_clean_16k.py`)
read the Gemini key from the `GEMINI_API_KEY` environment variable:

```powershell
$env:GEMINI_API_KEY = "your-key"   # PowerShell
python test_stream_speech.py
```

```bash
export GEMINI_API_KEY="your-key"   # bash
python test_stream_speech.py
```
