cmake_minimum_required(VERSION 3.18)
project(carlink_hichain C)
set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT DEFINED SOURCE_ROOT)
  message(FATAL_ERROR "SOURCE_ROOT must point to pinned external sources")
endif()
set(AUTH "${SOURCE_ROOT}/deviceauth/frameworks/deviceauth_lite/source")
set(HUKS "${SOURCE_ROOT}/huks/frameworks/huks_lite/source/hw_keystore_sdk")
set(SECUREC "${SOURCE_ROOT}/securec")
file(GLOB_RECURSE AUTH_C CONFIGURE_DEPENDS "${AUTH}/*.c")
file(GLOB_RECURSE HUKS_C CONFIGURE_DEPENDS "${HUKS}/*.c")
file(GLOB SECUREC_C CONFIGURE_DEPENDS "${SECUREC}/src/*.c")
if(NOT AUTH_C OR NOT HUKS_C OR NOT SECUREC_C)
  message(FATAL_ERROR "Missing pinned native sources")
endif()
find_package(OpenSSL REQUIRED COMPONENTS Crypto)
find_path(CJSON_INCLUDE cJSON.h PATH_SUFFIXES cjson REQUIRED)
find_library(CJSON_LIB cjson REQUIRED)
find_library(MBEDCRYPTO_LIB mbedcrypto REQUIRED)
add_library(carlink_hichain SHARED compat.c ${AUTH_C} ${HUKS_C} ${SECUREC_C})
target_include_directories(carlink_hichain PRIVATE
  "${SOURCE_ROOT}/huks/interfaces/innerkits/huks_lite" "${HUKS}/.."
  compat "${SECUREC}/include" "${SOURCE_ROOT}/deviceauth/interfaces/innerkits/deviceauth_lite"
  "${AUTH}/base" "${AUTH}/auth_info" "${AUTH}/huks_adapter" "${AUTH}/json"
  "${AUTH}/key_agreement" "${AUTH}/log" "${AUTH}/schedule" "${AUTH}/struct"
  "${SOURCE_ROOT}/huks/interfaces/innerkits/huks_lite" "${HUKS}" "${HUKS}/common"
  "${HUKS}/soft_service" "${CJSON_INCLUDE}" "${CMAKE_CURRENT_SOURCE_DIR}")
target_compile_definitions(carlink_hichain PRIVATE _CUT_LOG_ _POSIX_C_SOURCE=200809L)

# Optional PSK-SPEKE passwordless re-authentication (reverse engineered from
# HwDeviceAuth). OFF by default: with the switch off, HC_PIN_BUFF_LEN stays 16,
# no extra source is compiled and the binary behaves exactly as upstream.
option(ENABLE_PSK_SPEKE "Enable optional PSK-SPEKE re-authentication" OFF)
if(ENABLE_PSK_SPEKE)
  message(STATUS "PSK-SPEKE layer ENABLED (runtime switch still defaults to off)")
  target_compile_definitions(carlink_hichain PRIVATE _ENABLE_PSK_SPEKE_)
  target_sources(carlink_hichain PRIVATE psk_speke.c)
endif()

# Huawei AUTH_* wire compatibility: message-code translation at the JSON
# boundary plus the "2.0.31" algorithm-mask version string. OFF by default.
option(ENABLE_WIRE_AUTH "Enable Huawei AUTH_* wire compatibility" OFF)
if(ENABLE_WIRE_AUTH)
  message(STATUS "AUTH_* wire compatibility ENABLED (runtime switch still defaults to off)")
  target_compile_definitions(carlink_hichain PRIVATE _ENABLE_WIRE_AUTH_)
  target_sources(carlink_hichain PRIVATE hichain_wire.c)
endif()
target_link_libraries(carlink_hichain PRIVATE OpenSSL::Crypto "${CJSON_LIB}" "${MBEDCRYPTO_LIB}")
set_target_properties(carlink_hichain PROPERTIES BUILD_RPATH "$ORIGIN/lib" INSTALL_RPATH "$ORIGIN/lib")
if(NOT APPLE)
 target_link_options(carlink_hichain PRIVATE -Wl,--no-undefined)
endif()
if(ANDROID)
 target_link_options(carlink_hichain PRIVATE -Wl,-z,max-page-size=16384)
endif()
target_sources(carlink_hichain PRIVATE android_jni.c)
if(NOT ANDROID)
 target_include_directories(carlink_hichain PRIVATE "$ENV{JAVA_HOME}/include" "$ENV{JAVA_HOME}/include/darwin")
endif()
