cmake_minimum_required(VERSION 3.22)
project(aria_android LANGUAGES CXX)

set(NATIVE_PREFIX "${CMAKE_CURRENT_SOURCE_DIR}/../../../build/native/install/${ANDROID_ABI}")
add_library(aria2 SHARED IMPORTED)
set_target_properties(aria2 PROPERTIES
    IMPORTED_LOCATION "${NATIVE_PREFIX}/lib/libaria2.so"
    INTERFACE_INCLUDE_DIRECTORIES "${NATIVE_PREFIX}/include")
add_library(aria_jni SHARED bridge.cpp)
target_compile_features(aria_jni PRIVATE cxx_std_17)
target_compile_options(aria_jni PRIVATE -Wall -Wextra -Werror)
target_link_options(aria_jni PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=4096")
target_link_libraries(aria_jni PRIVATE aria2)
