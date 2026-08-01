# Architectural Blueprint: Building a High-Performance, Multi-Modal Local AI Engine with GGUF Support on Mobile

This blueprint details how to transition **Local Dream** from a Snapdragon-NPU-focused Stable Diffusion app into a unified, extremely fast **Local AI Multi-Modal Engine (Image, Chat, Audio)** powered by the state-of-the-art **GGUF (GGML) ecosystem**. 

By leveraging the shared memory and tensor computing core of **`ggml`**, we can run **2GB+ models on devices with only 5GB of RAM** with incredible performance.

---

## 🚀 1. The Challenge of 5GB RAM Mobile Devices

On an Android device with **5GB of physical RAM**, the Android OS, system services, and launcher typically consume about **2.2GB to 2.8GB**, leaving only **2.2GB to 2.8GB of active headroom** for user-space apps.
* **The Memory Trap:** If we try to run a raw FP16/FP32 model of **2GB** (like Stable Diffusion 1.5 or a small LLM), the model weight loading takes 2GB. Once inference starts, the **KV Cache** (for text) or the **latent activations and VAE buffers** (for image generation) require an additional **500MB to 1.5GB** of RAM. This easily pushes the memory footprint beyond the available headroom, triggering the **Android Low Memory Killer (LMK)** which instantly and silently crashes the app.
* **The CPU Bottleneck:** On mobile chipsets, memory bandwidth (LPDDR) is a massive bottleneck. Fetching raw 16-bit weights from RAM to the processor caches is much slower than the raw arithmetic speed of the CPU.

### 💡 The GGUF (GGML) Quantization Solution
By migrating to the **GGUF format**, we unlock native, highly optimized integer weight quantization (such as **Q4_0, Q5_0, or Q8_0**).
1. **Dramatically Reduced RAM Footprint:**
   * A 2GB unquantized model is reduced to only **~1.05GB** under **Q4_0 (4-bit)** quantization, or **~1.25GB** under **Q5_0 (5-bit)** quantization.
   * This leaves **1.2GB to 1.5GB of free headroom** for activations, preview buffers, and KV cache, making execution **100% stable** and crash-free.
2. **2x to 3x Faster Inference Speed:**
   * Because the model size is cut in half, the CPU needs to transfer 50% fewer bytes from RAM to its cache per step/token.
   * The integer weights are dequantized in-flight directly inside the CPU's vector registers (using optimized **ARM NEON SIMD instructions**), resulting in instant execution speedups on mobile CPUs.

---

## 🏗️ 2. Unified Multi-Modal Engine Architecture

To make Local Dream the perfect local AI platform, we will integrate three lightweight, dependency-free C++ engines built on the **GGML** tensor backend:

```
                  ┌────────────────────────────────────────────────┐
                  │          Android Kotlin/Compose UI             │
                  │   (Image Screen, Chat Screen, Voice Screen)    │
                  └───────────────────────┬────────────────────────┘
                                          │  Local HTTP REST / SSE
                  ┌───────────────────────▼────────────────────────┐
                  │             C++ Server (cpp-httplib)           │
                  └───────────────────────┬────────────────────────┘
                                          │
                  ┌───────────────────────▼────────────────────────┐
                  │           Unified Multimodal C++ Engine        │
                  └──────┬────────────────┬─────────────────┬──────┘
                         │                │                 │
      ┌──────────────────▼──┐     ┌───────▼───────────┐     │
      │ stable-diffusion.cpp│     │     llama.cpp     │     │ whisper.cpp
      │  (Image Generation)  │     │ (Text Generation) │     │ (Voice-to-Text)
      └──────────┬──────────┘     └───────┬───────────┘     └──────┬──────┘
                 │                        │                        │
                 └──────────────────┬─────┴────────────────────────┘
                                    │
                         ┌──────────▼──────────┐
                         │   GGML Core Engine  │
                         │ (ARM NEON & OpenCL) │
                         └─────────────────────┘
```

1. **Image Generation:** Powered by **`stable-diffusion.cpp`**. It natively supports loading single-file Stable Diffusion (SD 1.5, SDXL) and modern Flow-Matching (Flux.1, Wan 2.1) `.gguf` weights, performing tiled VAE decoding, and running Q4/Q5 quantized models at blazing speeds.
2. **Text / Chat:** Powered by **`llama.cpp`**. It allows chat/text completion using lightweight GGUF models like Qwen-2.5-Instruct (0.5B, 1.5B, 3B), LLaMA-3.2-3B, or Gemma-2B.
3. **Speech-to-Text:** Powered by **`whisper.cpp`**. It provides extremely low-latency audio transcription from Whisper GGUF models (`whisper-tiny-q4_0`, `whisper-base-q4_0`).

---

## 🛠️ 3. Step-by-Step Implementation Guide

### Step 1: Adding the GGML Core & Submodules
We add the three core C++ engines to our `app/src/main/cpp/3rdparty` directory:
```bash
cd app/src/main/cpp/3rdparty
git submodule add https://github.com/leejet/stable-diffusion.cpp.git
git submodule add https://github.com/ggml-org/llama.cpp.git
git submodule add https://github.com/ggerganov/whisper.cpp.git
```

### Step 2: Updating `CMakeLists.txt`
We configure `CMakeLists.txt` to build `ggml` with **ARM NEON SIMD** enabled, and optionally **Vulkan or OpenCL** for GPU offloading on mobile:

```cmake
# Enable NEON SIMD for ARM64 CPUs
if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    add_compile_definitions(GGML_USE_NEON)
endif()

# (Optional) Enable Vulkan GPU offload
option(GGML_VULKAN "Use Vulkan" ON)

# Include stable-diffusion.cpp, llama.cpp, and whisper.cpp directories
add_subdirectory(3rdparty/stable-diffusion.cpp EXCLUDE_FROM_ALL)
add_subdirectory(3rdparty/llama.cpp EXCLUDE_FROM_ALL)
add_subdirectory(3rdparty/whisper.cpp EXCLUDE_FROM_ALL)

# Link the multimodal libraries to our core server
target_link_libraries(${PROJECT_NAME} PRIVATE sd llama whisper)
```

### Step 3: Implementing a Unified API in `main.cpp`
We configure `cpp-httplib` inside `app/src/main/cpp/src/main.cpp` to expose endpoints for all three modalities:
* **`POST /generate`**: Stable Diffusion GGUF generation.
* **`POST /v1/chat/completions`**: OpenAI-compatible chat endpoint (returns Server-Sent Events / SSE for live token streaming).
* **`POST /v1/audio/transcriptions`**: Speech transcription.

---

## 🖥️ 4. Unified C++ Server Prototype (`MultimodalEngine.hpp`)

This prototype implements a high-performance C++ class that handles GGUF loading, memory limits, and handles thread-safe calls to the three GGML backends.

We will write this directly into the workspace so you have a complete code draft to build on!

---

## 📱 5. UI Layout for Multimodal Support

To support all three modalities properly, the Jetpack Compose navigation (`Navigation.kt`) is updated with a sleek Navigation Suite / Navigation Bar containing three tabs:
1. **🎨 Paint (Image Generation)**: Holds the current SD/Flux engine, supports upscaling, inpainting, and .gguf selection.
2. **💬 Chat (LLM Chatbot)**: A full messaging interface supporting system prompts, conversation memory, markdown formatting, and real-time word-by-word streaming.
3. **🎙️ Voice (Speech to Text)**: A simple recording/transcription hub where users tap to speak and copy/share the translated text instantly.

Let's write a C++ model loader & runner file in the project to make these components completely integrated!

---

## ✅ Implementation Status (airound v3.2.0)

The blueprint above is now wired end-to-end between the Compose UI and the
native C++ server:

| Surface | Endpoint(s) | Integration |
| --- | --- | --- |
| `ChatScreen.kt` | `POST /v1/chat/completions` (SSE) | Real OpenAI-compatible token streaming into the AI bubble, live tokens/sec indicator, conversation history (last 12 turns), inline engine/model error reporting. |
| `VoiceScreen.kt` | `POST /v1/audio/transcriptions` | `AudioRecord` captures 16 kHz mono PCM16 (with runtime `RECORD_AUDIO` flow), the file is posted for Whisper GGUF transcription, then deleted to keep the cache clean. |
| `HomePortalScreen.kt`, `AdvancedSettingsDialog.kt` → `ModelRunScreen.kt`, `ChatScreen.kt` | `POST /v1/multimodal/config` | CPU-thread / GPU-offload-layer sliders are pushed to the engine debounced; on ≤4.5GB devices a 1.25GB `memory_limit_bytes` budget is included. A live sync-status dot shows engine state. |
| `BackendService` | process level | `backendType=multimodal` launches `libstable_diffusion_core.so --multimodal_mode`: no diffusion/QNN init, sub-second startup, identical reconciliation logic to the diffusion server. |

### Multimodal model lifecycle

* **Registry:** `Model.modelKind` (`diffusion` / `chat` / `audio`). Built-in
  entries: `qwen_chat_1b` (Qwen2.5-1.5B Q4_K_M), `llama_chat_3b`
  (Llama-3.2-3B Q4_K_M), `whisper_tiny_q4_0` (~39MB).
* **Download:** single `.gguf`/`.bin` payloads use `modelType=gguf`; the file
  lands in `models/<id>/` under its original name (zip semantics untouched
  for diffusion models).
* **Custom imports:** any model directory containing a `*.gguf` file scans in
  as a multimodal chat model, and tapping it opens the matching workspace
  (chat → `ChatScreen`, audio → `VoiceScreen`).
* **RAM safety:** the `MultimodalEngine` singleton keeps exactly one model
  family resident (`freeActiveModelExcept`), reuses already-resident weights
  instead of re-mapping them, resolves model names to on-disk GGUF files via
  `resolveGgufPath`, and honors the configured memory ceiling.

### CI diagnostics

`gradlew` (CI only) tees the Gradle console to `build/ci/gradle-build.log`
and, on failure, re-emits Kotlin/javac errors plus the failure summary as
GitHub check annotations (`::error` workflow commands), so build breaks stay
diagnosable even when raw job logs are unavailable.
