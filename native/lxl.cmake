# Copyright 2026 Ishan Chattopadhyaya and luxir-lucene contributors
# SPDX-License-Identifier: Apache-2.0

# luxir_lucene: native side of the Luxir postings format for Lucene
# (org.luxir.lucene), built as libluxir_lucene.so inside the Luxir tree via
# its untracked LocalTargets.cmake hook:
#
#   echo 'include(/path/to/luxir-lucene/native/lxl.cmake)' >> luxir/LocalTargets.cmake
#   cmake --build build/lux4j-release --target luxir_lucene
#
# Same constraints as lux4j: jemalloc off, PIC on, only lxl_* exported.

if(LUXIR_JEMALLOC)
  message(FATAL_ERROR "luxir_lucene: configure with -DLUXIR_JEMALLOC=OFF")
endif()
if(NOT CMAKE_POSITION_INDEPENDENT_CODE)
  message(FATAL_ERROR "luxir_lucene: configure with -DCMAKE_POSITION_INDEPENDENT_CODE=ON")
endif()

set(_lxl_dir "${CMAKE_CURRENT_LIST_DIR}")
add_library(luxir_lucene SHARED EXCLUDE_FROM_ALL "${_lxl_dir}/lxl.cpp")
target_include_directories(luxir_lucene PRIVATE "${CMAKE_SOURCE_DIR}/src" "${CMAKE_BINARY_DIR}/protos")
target_link_libraries(luxir_lucene PRIVATE luxir_lib)
set_target_properties(luxir_lucene PROPERTIES
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN ON
  LINK_DEPENDS "${_lxl_dir}/lxl.map")
target_link_options(luxir_lucene PRIVATE
  "-Wl,--version-script=${_lxl_dir}/lxl.map"
  "-Wl,--exclude-libs,ALL"
  "-Wl,-z,defs")
