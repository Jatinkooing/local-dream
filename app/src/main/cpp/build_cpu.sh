#!/bin/bash
set -e
set -x

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "[build_cpu.sh] PWD: $(pwd)"
echo "[build_cpu.sh] Env: ANDROID_NDK_ROOT=${ANDROID_NDK_ROOT:-unset} ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-unset} ANDROID_HOME=${ANDROID_HOME:-unset} ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-unset} HOME=${HOME}"
ls -la || true
ls -la 3rdparty/ || true

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
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 2>&1 | tee build/cpu/configure.log
CONF_STATUS=${PIPESTATUS[0]}
cat build/cpu/configure.log
if [ $CONF_STATUS -ne 0 ]; then
  echo "[build_cpu.sh] CMake configure failed with $CONF_STATUS" >&2
  echo "::error title=CMake configure failed::Configure failed with $CONF_STATUS"
  cat build/cpu/CMakeFiles/CMakeOutput.log 2>&1 | tail -n 200 || true
  cat build/cpu/CMakeFiles/CMakeError.log 2>&1 | tail -n 800 || true
  # Emit annotation with error log
  ERR=$(tail -n 300 build/cpu/CMakeFiles/CMakeError.log 2>/dev/null | tr '%' '%25' | tr '\r' ' ' | tr '\n' '%0A' | cut -c1-4000)
  echo "::error title=CMakeError.log tail::$ERR"
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
  echo "[build_cpu.sh] Build failed, last 500 lines:" >&2
  tail -n 500 build/cpu/build.log >&2 || true
  echo "[build_cpu.sh] Searching for 'error:' in build log:" >&2
  grep -i "error:" build/cpu/build.log | tail -n 200 >&2 || true
  # Emit annotation with build log tail
  TAIL=$(tail -n 500 build/cpu/build.log | tr '%' '%25' | tr '\r' ' ' | tr '\n' '%0A' | cut -c1-8000)
  echo "::error title=Native build failed (tail 500)::$TAIL"
  # Also try to find specific error lines
  GREP_ERR=$(grep -i "error:" build/cpu/build.log | tail -n 100 | tr '%' '%25' | tr '\r' ' ' | tr '\n' '%0A' | cut -c1-8000)
  if [ -n "$GREP_ERR" ]; then
    echo "::error title=Native build errors::$GREP_ERR"
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
