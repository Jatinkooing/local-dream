#!/bin/bash
set -e
set -x

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "[build_cpu.sh] PWD: $(pwd)"
echo "[build_cpu.sh] Env: ANDROID_NDK_ROOT=${ANDROID_NDK_ROOT:-unset} ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-unset} ANDROID_HOME=${ANDROID_HOME:-unset} ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-unset} HOME=${HOME} GITHUB_WORKSPACE=${GITHUB_WORKSPACE:-unset}"
ls -la || true
ls -la 3rdparty/ || true

# --- Ensure Rust toolchain for tokenizers-cpp (xororz/tokenizers-cpp wraps HF tokenizers Rust) ---
echo "[build_cpu.sh] Checking Rust toolchain..."
if ! command -v cargo >/dev/null 2>&1; then
  echo "[build_cpu.sh] cargo not found, installing rustup..."
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable --profile minimal || true
  export PATH="$HOME/.cargo/bin:$PATH"
  echo "[build_cpu.sh] After install, cargo: $(which cargo || echo notfound) version: $(cargo --version 2>&1 || echo fail)"
else
  echo "[build_cpu.sh] cargo found: $(cargo --version)"
  echo "[build_cpu.sh] rustc: $(rustc --version 2>&1 || echo fail)"
fi

export PATH="$HOME/.cargo/bin:$PATH"
if [ -f "$HOME/.cargo/env" ]; then
  # shellcheck disable=SC1090
  source "$HOME/.cargo/env" || true
fi

if command -v rustup >/dev/null 2>&1; then
  echo "[build_cpu.sh] rustup found, ensuring toolchain 1.80 (pre-dangerous_implicit_autorefs) and stable..."
  rustup toolchain install 1.80 --profile minimal 2>&1 | tail -n 30 || true
  rustup default 1.80 2>&1 | tail -n 20 || true
  echo "[build_cpu.sh] Adding Android Rust targets for 1.80..."
  rustup target add aarch64-linux-android --toolchain 1.80 2>&1 | tail -n 30 || true
  rustup target add armv7-linux-androideabi --toolchain 1.80 2>&1 | tail -n 20 || true
  rustup target add x86_64-linux-android --toolchain 1.80 2>&1 | tail -n 20 || true
  # Also add for stable as fallback
  rustup toolchain install stable --profile minimal 2>&1 | tail -n 20 || true
  rustup target add aarch64-linux-android --toolchain stable 2>&1 | tail -n 20 || true
  cargo --version
  rustc --version
  rustup target list --installed || true
  rustup show || true
fi

# Force older toolchain that doesn't have dangerous_implicit_autorefs lint as error
export RUSTFLAGS="-A warnings -A dangerous_implicit_autorefs -A unsafe_op_in_unsafe_fn"
echo "[build_cpu.sh] RUSTFLAGS=$RUSTFLAGS"

# If cargo still not found, try common locations
if ! command -v cargo >/dev/null 2>&1; then
  for cargo_path in "$HOME/.cargo/bin/cargo" "/root/.cargo/bin/cargo" "/usr/local/cargo/bin/cargo"; do
    if [ -x "$cargo_path" ]; then
      export PATH="$(dirname "$cargo_path"):$PATH"
      echo "[build_cpu.sh] Found cargo at $cargo_path, added to PATH"
      break
    fi
  done
fi

echo "[build_cpu.sh] Final cargo check: $(which cargo || echo notfound) $(cargo --version 2>&1 || echo nocargo)"
# -------------------------------------------------------------------------------

find_ndk() {
  if [ -n "$ANDROID_NDK_ROOT" ] && [ -d "$ANDROID_NDK_ROOT" ] && [ -f "$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" ]; then
    echo "$ANDROID_NDK_ROOT"
    return 0
  fi
  if [ -n "$ANDROID_NDK_HOME" ] && [ -d "$ANDROID_NDK_HOME" ] && [ -f "$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" ]; then
    echo "$ANDROID_NDK_HOME"
    return 0
  fi

  SDK_CANDIDATES=()
  [ -n "$ANDROID_SDK_ROOT" ] && SDK_CANDIDATES+=("$ANDROID_SDK_ROOT")
  [ -n "$ANDROID_HOME" ] && SDK_CANDIDATES+=("$ANDROID_HOME")
  [ -n "$HOME" ] && [ -d "$HOME/Android/Sdk" ] && SDK_CANDIDATES+=("$HOME/Android/Sdk")
  SDK_CANDIDATES+=("/opt/android-sdk" "/usr/local/android-sdk" "/data/android-sdk" "/usr/local/lib/android/sdk" "$HOME/Android/Sdk")

  echo "[build_cpu.sh] Searching SDK candidates: ${SDK_CANDIDATES[*]}" >&2
  for SDK in "${SDK_CANDIDATES[@]}"; do
    if [ -d "$SDK/ndk" ]; then
      ls -la "$SDK/ndk" >&2 || true
      LATEST=$(ls -1 "$SDK/ndk" 2>/dev/null | sort -V | tail -n1)
      echo "[build_cpu.sh] Latest in $SDK/ndk is $LATEST" >&2
      if [ -n "$LATEST" ] && [ -f "$SDK/ndk/$LATEST/build/cmake/android.toolchain.cmake" ]; then
        echo "$SDK/ndk/$LATEST"
        return 0
      fi
    fi
  done

  if command -v sdkmanager >/dev/null 2>&1; then
    echo "[build_cpu.sh] NDK not found, attempting sdkmanager install of ndk;27.2.12479018" >&2
    yes | sdkmanager --install "ndk;27.2.12479018" 2>&1 | tail -n 100 >&2 || true
    for SDK in "${SDK_CANDIDATES[@]}"; do
      if [ -d "$SDK/ndk" ]; then
        LATEST=$(ls -1 "$SDK/ndk" 2>/dev/null | sort -V | tail -n1)
        if [ -n "$LATEST" ] && [ -f "$SDK/ndk/$LATEST/build/cmake/android.toolchain.cmake" ]; then
          echo "$SDK/ndk/$LATEST"
          return 0
        fi
      fi
    done
  else
    echo "[build_cpu.sh] sdkmanager not found" >&2
  fi
  return 1
}

NDK_ROOT=$(find_ndk) || {
  echo "[build_cpu.sh] ERROR: Android NDK not found" >&2
  exit 1
}

echo "[build_cpu.sh] Using NDK: $NDK_ROOT"
ls -la "$NDK_ROOT" | head -n 20 || true

TOOLCHAIN="$NDK_ROOT/build/cmake/android.toolchain.cmake"
if [ ! -f "$TOOLCHAIN" ]; then
  echo "[build_cpu.sh] ERROR: Toolchain file not found: $TOOLCHAIN" >&2
  exit 1
fi

mkdir -p build/cpu

echo "[build_cpu.sh] Configuring CMake (AIROND_CPU_ONLY=ON)..."
set +e
cmake -S . -B build/cpu \
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
  -DANDROID_ABI=arm64-v8a \
  -DCMAKE_ANDROID_ARCH_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-21 \
  -DANDROID_NATIVE_API_LEVEL=21 \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DAIROND_CPU_ONLY=ON \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
  -DTOKENIZERS_CPP_RUST_FLAGS="-A warnings -A dangerous_implicit_autorefs -A unsafe_op_in_unsafe_fn" 2>&1 | tee build/cpu/configure.log
CONF_STATUS=${PIPESTATUS[0]}
cat build/cpu/configure.log
if [ $CONF_STATUS -ne 0 ]; then
  echo "[build_cpu.sh] CMake configure failed with $CONF_STATUS" >&2
  echo "::error title=CMake configure failed::Configure failed with $CONF_STATUS"
  cat build/cpu/CMakeFiles/CMakeOutput.log 2>&1 | tail -n 200 || true
  cat build/cpu/CMakeFiles/CMakeError.log 2>&1 | tail -n 800 || true
  ERR=$(tail -n 300 build/cpu/CMakeFiles/CMakeError.log 2>/dev/null | tr '%' '%25' | tr '\r' ' ' | tr '\n' '%0A' | cut -c1-4000)
  echo "::error title=CMakeError.log tail::$ERR"
  if [ -n "$GITHUB_WORKSPACE" ]; then
    mkdir -p "$GITHUB_WORKSPACE/dist" || true
    cp build/cpu/configure.log "$GITHUB_WORKSPACE/dist/" 2>/dev/null || true
    cp build/cpu/CMakeFiles/CMakeError.log "$GITHUB_WORKSPACE/dist/" 2>/dev/null || true
    cp build/cpu/CMakeFiles/CMakeOutput.log "$GITHUB_WORKSPACE/dist/" 2>/dev/null || true
  fi
  exit 2
fi
set -e

echo "[build_cpu.sh] Building native lib (parallel 2)..."
set +e
cmake --build build/cpu --parallel 2 2>&1 | tee build/cpu/build.log
BUILD_STATUS=${PIPESTATUS[0]}
echo "[build_cpu.sh] Build finished with status $BUILD_STATUS"
tail -n 400 build/cpu/build.log
if [ $BUILD_STATUS -ne 0 ]; then
  echo "[build_cpu.sh] Build failed, last 800 lines:" >&2
  tail -n 800 build/cpu/build.log >&2 || true
  echo "[build_cpu.sh] Searching for 'error:' in build log:" >&2
  grep -i "error:" build/cpu/build.log | tail -n 300 >&2 || true
  TAIL=$(tail -n 800 build/cpu/build.log | tr '%' '%25' | tr '\r' ' ' | tr '\n' '%0A' | cut -c1-8000)
  echo "::error title=Native build failed (tail 800)::$TAIL"
  GREP_ERR=$(grep -i "error:" build/cpu/build.log | tail -n 150 | tr '%' '%25' | tr '\r' ' ' | tr '\n' '%0A' | cut -c1-8000)
  if [ -n "$GREP_ERR" ]; then
    echo "::error title=Native build errors::$GREP_ERR"
  fi
  if [ -n "$GITHUB_WORKSPACE" ]; then
    mkdir -p "$GITHUB_WORKSPACE/dist" || true
    cp build/cpu/build.log "$GITHUB_WORKSPACE/dist/" 2>/dev/null || true
    cp build/cpu/configure.log "$GITHUB_WORKSPACE/dist/" 2>/dev/null || true
  fi
  exit 2
fi
set -e

BUILT_SO=$(find build/cpu -name "libstable_diffusion_core.so" -type f | head -n1)
if [ -z "$BUILT_SO" ]; then
  echo "[build_cpu.sh] ERROR: Built .so not found" >&2
  find build/cpu -type f | head -n 100 >&2 || true
  exit 1
fi

echo "[build_cpu.sh] Found built so: $BUILT_SO"

mkdir -p ../jniLibs/arm64-v8a/
cp "$BUILT_SO" ../jniLibs/arm64-v8a/libstable_diffusion_core.so

echo "[build_cpu.sh] Copied to ../jniLibs/arm64-v8a/libstable_diffusion_core.so"
ls -lh ../jniLibs/arm64-v8a/libstable_diffusion_core.so
