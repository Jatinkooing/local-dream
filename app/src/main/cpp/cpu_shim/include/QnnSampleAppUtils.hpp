#pragma once
#include "Logger.hpp"
#include <cctype>
#include <string>

namespace qnn {
namespace tools {
namespace sample_app {

enum class ProfilingLevel { OFF, BASIC, DETAILED };
enum class StatusCode { SUCCESS, FAILURE };

inline QnnLog_Level_t parseLogLevel(const char* s) {
  if (!s) return QNN_LOG_LEVEL_ERROR;
  std::string str(s);
  for (auto& c : str) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
  if (str == "debug") return QNN_LOG_LEVEL_DEBUG;
  if (str == "info") return QNN_LOG_LEVEL_INFO;
  if (str == "warn" || str == "warning") return QNN_LOG_LEVEL_WARN;
  if (str == "error") return QNN_LOG_LEVEL_ERROR;
  if (str == "off") return QNN_LOG_LEVEL_OFF;
  try {
    int v = std::stoi(str);
    if (v <= 0) return QNN_LOG_LEVEL_DEBUG;
    if (v == 1) return QNN_LOG_LEVEL_INFO;
    if (v == 2) return QNN_LOG_LEVEL_WARN;
    return QNN_LOG_LEVEL_ERROR;
  } catch (...) {
  }
  return QNN_LOG_LEVEL_ERROR;
}

inline QnnLog_Level_t parseLogLevel(const std::string& s) {
  return parseLogLevel(s.c_str());
}

}  // namespace sample_app
}  // namespace tools
}  // namespace qnn
