cmake_minimum_required(VERSION 3.22.1)
project(kelma_lua C)

file(GLOB LUA_SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/src/*.c")
list(REMOVE_ITEM LUA_SOURCES
    "${CMAKE_CURRENT_SOURCE_DIR}/src/liolib.c"
    "${CMAKE_CURRENT_SOURCE_DIR}/src/loslib.c"
    "${CMAKE_CURRENT_SOURCE_DIR}/src/ldblib.c"
    "${CMAKE_CURRENT_SOURCE_DIR}/src/linit.c"
)
add_library(
    kelma_lua
    SHARED
    kelma_lua_host.c
    kelma_lua_calls.c
    kelma_lua_jni.c
    ${LUA_SOURCES}
)
target_include_directories(kelma_lua PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}/src")
target_compile_definitions(kelma_lua PRIVATE LUA_USE_LINUX)
target_compile_options(kelma_lua PRIVATE -O2 -Wall -Wextra -Werror)
target_link_libraries(kelma_lua PRIVATE dl log m)
