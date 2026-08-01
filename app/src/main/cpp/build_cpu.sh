#!/bin/bash
set -e
set -x

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "[build_cpu.sh] PWD: $(pwd)"
echo "[build_cpu.sh] Env: ANDROID_NDK_ROOT=${ANDROID_NDK_ROOT:-unset} ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-unset} ANDROID_HOME=${ANDROID_HOME:-unset} ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-unset} HOME=${HOME}"
ls -la
ls -la 3rdparty/ || true

# Find NDK: $ANDROID_NDK_ROOT -> newest $SDK_ROOT/ndk/* -> sdkmanager "ndk;27.2.12479018"
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
    echo "[build_cpu.sh] Checking $SDK" >&2
    ls -la "$SDK" 2>&1 | head -n 20 >&2 || true
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
    echo "[build_cpu.sh] sdkmanager not found in PATH" >&2
    which sdkmanager 2>&1 || true
    echo "PATH=$PATH" >&2
  fi

  return 1
}

NDK_ROOT=$(find_ndk) || {
  echo "[build_cpu.sh] ERROR: Android NDK not found. Set ANDROID_NDK_ROOT or install ndk;27.2.12479018 via sdkmanager." >&2
  exit 1
}

echo "[build_cpu.sh] Using NDK: $NDK_ROOT"
ls -la "$NDK_ROOT" | head -n 20

TOOLCHAIN="$NDK_ROOT/build/cmake/android.toolchain.cmake"
if [ ! -f "$TOOLCHAIN" ]; then
  echo "[build_cpu.sh] ERROR: Toolchain file not found: $TOOLCHAIN" >&2
  exit 1
fi

mkdir -p build/cpu

# Configure with verbose output
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
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 2>&1 | tee build/cpu/configure.log
CONF_STATUS=${PIPESTATUS[0]}
cat build/cpu/configure.log
if [ $CONF_STATUS -ne 0 ]; then
  echo "[build_cpu.sh] CMake configure failed with $CONF_STATUS" >&2
  cat build/cpu/CMakeFiles/CMakeOutput.log 2>&1 | tail -n 200 || true
  cat build/cpu/CMakeFiles/CMakeError.log 2>&1 | tail -n 500 || true
  exit 2
fi
set -e

# Build
echo "[build_cpu.sh] Building native lib (parallel 2)..."
set +e
cmake --build build/cpu --parallel 2 --verbose 2>&1 | tee build/cpu/build.log
BUILD_STATUS=${PIPESTATUS[0]}
echo "[build_cpu.sh] Build finished with status $BUILD_STATUS, last 200 lines:"
tail -n 200 build/cpu/build.log
if [ $BUILD_STATUS -ne 0 ]; then
  echo "[build_cpu.sh] Build failed" >&2
  exit 2
fi
set -e

# Locate built .so
BUILT_SO=$(find build/cpu -name "libstable_diffusion_core.so" -type f | head -n1)
if [ -z "$BUILT_SO" ]; then
  echo "[build_cpu.sh] ERROR: Built .so not found under build/cpu" >&2
  find build/cpu -type f 2>&1 | head -n 100 >&2 || true
  exit 1
fi

echo "[build_cpu.sh] Found built so: $BUILT_SO"

mkdir -p ../jniLibs/arm64-v8a/
cp "$BUILT_SO" ../jniLibs/arm64-v8a/libstable_diffusion_core.so

echo "[build_cpu.sh] Copied to ../jniLibs/arm64-v8a/libstable_diffusion_core.so"
ls -lh ../jniLibs/arm64-v8a/libstable_diffusion_core.so
