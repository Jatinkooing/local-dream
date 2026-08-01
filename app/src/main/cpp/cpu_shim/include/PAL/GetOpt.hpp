#pragma once
#include <cstring>
#include <string>

namespace pal {

enum { no_argument = 0, required_argument = 1, optional_argument = 2 };

struct Option {
  const char* name;
  int has_arg;
  int* flag;
  int val;
};

inline char* g_optArg = nullptr;
inline int g_optInd = 1;

inline int getOptLongOnly(int argc, char** argv, const char* /*shortopts*/,
                          const Option* longopts, int* longindex) {
  if (g_optInd >= argc) return -1;
  const char* arg = argv[g_optInd];
  if (arg[0] != '-') {
    g_optInd++;
    return '?';
  }
  const char* nameStart = arg;
  while (*nameStart == '-') nameStart++;
  if (*nameStart == '\0') {
    g_optInd++;
    return '?';
  }
  std::string argStr(nameStart);
  std::string optName;
  std::string optVal;
  size_t eqPos = argStr.find('=');
  if (eqPos != std::string::npos) {
    optName = argStr.substr(0, eqPos);
    optVal = argStr.substr(eqPos + 1);
  } else {
    optName = argStr;
  }

  for (int i = 0; longopts[i].name != nullptr; ++i) {
    if (optName == longopts[i].name) {
      if (longindex) *longindex = i;
      if (longopts[i].has_arg == required_argument) {
        if (!optVal.empty()) {
          static thread_local std::string storage;
          storage = optVal;
          g_optArg = const_cast<char*>(storage.c_str());
        } else {
          if (g_optInd + 1 < argc) {
            g_optInd++;
            g_optArg = argv[g_optInd];
          } else {
            g_optArg = nullptr;
          }
        }
      } else if (longopts[i].has_arg == optional_argument) {
        if (!optVal.empty()) {
          static thread_local std::string storage2;
          storage2 = optVal;
          g_optArg = const_cast<char*>(storage2.c_str());
        } else {
          g_optArg = nullptr;
        }
      } else {
        g_optArg = nullptr;
      }
      if (longopts[i].flag) {
        *(longopts[i].flag) = longopts[i].val;
        g_optInd++;
        return 0;
      } else {
        g_optInd++;
        return longopts[i].val;
      }
    }
  }
  g_optInd++;
  return '?';
}

}  // namespace pal
