# 端侧 LLM：只编 llama.cpp 的库本体 + 一个最小 JNI。
# llama.cpp 的源码目录由 Gradle 传进来（-DPRICELENS_LLAMA_DIR=...），**不放在本仓库里** ——
# 35MB 第三方源码进 git 会让每次 checkout/CI 都变重，而它只在"要出带模型的包"时才需要。
cmake_minimum_required(VERSION 3.22.1)
project(pricelens_llm C CXX)

if(NOT PRICELENS_LLAMA_DIR)
    message(FATAL_ERROR "缺少 -DPRICELENS_LLAMA_DIR=<llama.cpp 源码目录>（见 app/build.gradle.kts 的 pricelens.llamaDir）")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 只要库：示例/工具/服务端都会拉进一堆与 App 无关的目标，编起来慢还容易缺依赖
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)   # NDK 默认不带 OpenMP，开着头文件的 link 会缺符号
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory("${PRICELENS_LLAMA_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp")

add_library(pricelens_llm SHARED llm_jni.cpp)
target_include_directories(pricelens_llm PRIVATE
        "${PRICELENS_LLAMA_DIR}/include"
        "${PRICELENS_LLAMA_DIR}/ggml/include")
target_link_libraries(pricelens_llm PRIVATE llama android log)
