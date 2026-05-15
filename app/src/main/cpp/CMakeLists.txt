cmake_minimum_required(VERSION 3.22.1)
project(cameraprobe LANGUAGES C CXX)

set(OpenCV_STATIC ON)
set(OpenCV_DIR "$ENV{HOME}/Projekts/OpenCV-android-sdk/sdk/native/jni")
find_package(OpenCV REQUIRED core imgproc calib3d)

add_library(cameraprobe SHARED
        cameraprobe.cpp
        cameraprobe_record.cpp
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
