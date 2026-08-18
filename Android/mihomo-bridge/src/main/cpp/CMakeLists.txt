cmake_minimum_required(VERSION 3.22)
project(clashnl_mihomo_bridge C)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include_directories("${GO_OUTPUT_DIR}/${CMAKE_ANDROID_ARCH_ABI}")
include_directories("${BRIDGE_SOURCE_DIR}")
link_directories("${GO_OUTPUT_DIR}/${CMAKE_ANDROID_ARCH_ABI}")

add_library(mihomo_bridge SHARED bridge.c)
target_link_libraries(mihomo_bridge log clash)
