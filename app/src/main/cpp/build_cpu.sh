#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

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

  # Search SDK locations
  SDK_CANDIDATES=()
  [ -n "$ANDROID_SDK_ROOT" ] && SDK_CANDIDATES+=("$ANDROID_SDK_ROOT")
  [ -n "$ANDROID_HOME" ] && SDK_CANDIDATES+=("$ANDROID_HOME")
  [ -n "$HOME" ] && [ -d "$HOME/Android/Sdk" ] && SDK_CANDIDATES+=("$HOME/Android/Sdk")
  SDK_CANDIDATES+=("/opt/android-sdk" "/usr/local/android-sdk" "/data/android-sdk")

  for SDK in "${SDK_CANDIDATES[@]}"; do
    if [ -d "$SDK/ndk" ]; then
      # newest version dir
      LATEST=$(ls -1 "$SDK/ndk" 2>/dev/null | sort -V | tail -n1)
      if [ -n "$LATEST" ] && [ -f "$SDK/ndk/$LATEST/build/cmake/android.toolchain.cmake" ]; then
        echo "$SDK/ndk/$LATEST"
        return 0
      fi
    fi
  done

  # Try sdkmanager install
  if command -v sdkmanager >/dev/null 2>&1; then
    echo "[build_cpu.sh] NDK not found, attempting sdkmanager install of ndk;27.2.12479018" >&2
    yes | sdkmanager --install "ndk;27.2.12479018" >/dev/null 2>&1 || true
    # re-search after install
    for SDK in "${SDK_CANDIDATES[@]}"; do
      if [ -d "$SDK/ndk" ]; then
        LATEST=$(ls -1 "$SDK/ndk" 2>/dev/null | sort -V | tail -n1)
        if [ -n "$LATEST" ] && [ -f "$SDK/ndk/$LATEST/build/cmake/android.toolchain.cmake" ]; then
          echo "$SDK/ndk/$LATEST"
          return 0
        fi
      fi
    done
    # Check default SDK path used by setup-android action: $ANDROID_HOME or $ANDROID_SDK_ROOT
    # sdkmanager often installs under $HOME/Android/Sdk or /usr/local/lib/android/sdk
    for SDK in "/usr/local/lib/android/sdk" "$HOME/Android/Sdk"; do
      if [ -d "$SDK/ndk" ]; then
        LATEST=$(ls -1 "$SDK/ndk" 2>/dev/null | sort -V | tail -n1)
        if [ -n "$LATEST" ] && [ -f "$SDK/ndk/$LATEST/build/cmake/android.toolchain.cmake" ]; then
          echo "$SDK/ndk/$LATEST"
          return 0
        fi
      fi
    done
  fi

  return 1
}

NDK_ROOT=$(find_ndk) || {
  echo "[build_cpu.sh] ERROR: Android NDK not found. Set ANDROID_NDK_ROOT or install ndk;27.2.12479018 via sdkmanager." >&2
  exit 1
}

echo "[build_cpu.sh] Using NDK: $NDK_ROOT"

TOOLCHAIN="$NDK_ROOT/build/cmake/android.toolchain.cmake"
if [ ! -f "$TOOLCHAIN" ]; then
  echo "[build_cpu.sh] ERROR: Toolchain file not found: $TOOLCHAIN" >&2
  exit 1
fi

# Configure
echo "[build_cpu.sh] Configuring CMake (AIROND_CPU_ONLY=ON)..."
cmake -S . -B build/cpu \
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-21 \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DAIROND_CPU_ONLY=ON \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5

# Build
echo "[build_cpu.sh] Building native lib (parallel 2)..."
cmake --build build/cpu --parallel 2

# Locate built .so
BUILT_SO=$(find build/cpu -name "libstable_diffusion_core.so" -type f | head -n1)
if [ -z "$BUILT_SO" ]; then
  echo "[build_cpu.sh] ERROR: Built .so not found under build/cpu" >&2
  find build/cpu -type f -name "*.so" | head -n20 >&2 || true
  exit 1
fi

echo "[build_cpu.sh] Found built so: $BUILT_SO"

mkdir -p ../jniLibs/arm64-v8a/
cp "$BUILT_SO" ../jniLibs/arm64-v8a/libstable_diffusion_core.so

echo "[build_cpu.sh] Copied to ../jniLibs/arm64-v8a/libstable_diffusion_core.so"
ls -lh ../jniLibs/arm64-v8a/libstable_diffusion_core.so
