#pragma once
#include <cstdio>

// Minimal QNN Logger stub for CPU-only builds
enum QnnLog_Level_t {
  QNN_LOG_LEVEL_DEBUG = 0,
  QNN_LOG_LEVEL_INFO = 1,
  QNN_LOG_LEVEL_WARN = 2,
  QNN_LOG_LEVEL_ERROR = 3,
  QNN_LOG_LEVEL_OFF = 4,
  QNN_LOG_LEVEL_MAX = 5
};

namespace qnn {
namespace log {
inline bool initializeLogging() { return true; }
inline bool setLogLevel(QnnLog_Level_t) { return true; }
inline bool isLogInitialized() { return true; }
inline void* getLogCallback() { return nullptr; }
}  // namespace log
}  // namespace qnn

#define QNN_DEBUG(fmt, ...) do { fprintf(stderr, "[DEBUG] " fmt "\n", ##__VA_ARGS__); } while(0)
#define QNN_INFO(fmt, ...) do { fprintf(stderr, "[INFO] " fmt "\n", ##__VA_ARGS__); } while(0)
#define QNN_WARN(fmt, ...) do { fprintf(stderr, "[WARN] " fmt "\n", ##__VA_ARGS__); } while(0)
#define QNN_ERROR(fmt, ...) do { fprintf(stderr, "[ERROR] " fmt "\n", ##__VA_ARGS__); } while(0)
