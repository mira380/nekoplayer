cmake_minimum_required(VERSION 3.22.1)
project(nekoplayer_importer LANGUAGES C CXX)

include(FetchContent)

# Build the same QuickJS 0.9.2 JNI implementation used by the extension runtime,
# using the project's NDK so its LOAD segments support 16 KB page-size devices.
FetchContent_Declare(
    quickjs_source
    URL https://codeload.github.com/cashapp/zipline/tar.gz/a738129cc4aa99206c00ae49e9c5b15cb58ad880
    URL_HASH SHA256=b54a2153690533afd0181fc1136ce989eb31e3c39bf2e852b2ff1adf4179d56f
)
FetchContent_GetProperties(quickjs_source)
if(NOT quickjs_source_POPULATED)
    FetchContent_Populate(quickjs_source)
endif()

set(QUICKJS_NATIVE_DIR "${quickjs_source_SOURCE_DIR}/quickjs/common/native")
file(GLOB_RECURSE QUICKJS_SOURCES
    "${QUICKJS_NATIVE_DIR}/*.c"
    "${QUICKJS_NATIVE_DIR}/*.cpp"
)

add_library(quickjs SHARED ${QUICKJS_SOURCES})
set_target_properties(quickjs PROPERTIES
    C_STANDARD 99
    C_STANDARD_REQUIRED ON
    CXX_STANDARD 11
    CXX_STANDARD_REQUIRED ON
)
target_include_directories(quickjs PRIVATE
    "${QUICKJS_NATIVE_DIR}"
    "${QUICKJS_NATIVE_DIR}/quickjs"
)
target_compile_options(quickjs PRIVATE
    -fstrict-aliasing
    "$<$<COMPILE_LANGUAGE:CXX>:-include>"
    "$<$<COMPILE_LANGUAGE:CXX>:functional>"
  )
target_compile_definitions(quickjs PRIVATE CONFIG_VERSION=\"2021-03-27\")
target_link_options(quickjs PRIVATE "-Wl,-z,max-page-size=16384")

add_library(nekoplayer_importer SHARED native_yomitan_term_bank.cpp)
target_compile_features(nekoplayer_importer PRIVATE cxx_std_17)
target_link_options(nekoplayer_importer PRIVATE "-Wl,-z,max-page-size=16384")
