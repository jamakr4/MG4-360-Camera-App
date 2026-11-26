cmake_minimum_required(VERSION 3.22.1)
project(cameraprobe LANGUAGES C CXX)

set(OpenCV_STATIC ON)
# OPENCV_ANDROID_SDK points at an unpacked OpenCV Android SDK (CI sets it); without it the
# original local path is used, so existing workstations keep building unchanged.
if(DEFINED ENV{OPENCV_ANDROID_SDK})
    set(OpenCV_DIR "$ENV{OPENCV_ANDROID_SDK}/sdk/native/jni")
elseif(NOT OpenCV_DIR)
    set(OpenCV_DIR "$ENV{HOME}/Projekts/OpenCV-android-sdk/sdk/native/jni")
endif()
find_package(OpenCV REQUIRED core imgproc)

add_library(cameraprobe SHARED
        cameraprobe.cpp
        cameraprobe_record.cpp
        camera_stream_manager.cpp
        side_camera_preview.cpp
        ipk_rearview.cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(media-lib mediandk)

target_link_libraries(cameraprobe
        ${log-lib}
        ${android-lib}
        ${media-lib}
        ${OpenCV_LIBS}
)
