cmake_minimum_required(VERSION 3.22.1)
project(spike C)

add_library(spike SHARED spike.c)
add_library(payload SHARED payload.c)

find_library(log-lib log)
target_link_libraries(spike ${log-lib} dl)

# 16 KB page size support for NDK r27.
target_link_options(spike PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(payload PRIVATE "-Wl,-z,max-page-size=16384")
