cmake_minimum_required(VERSION 3.22.1)
project(camera_preview_tests LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(OpenCV_STATIC ON)
find_package(OpenCV REQUIRED core imgproc)

add_executable(side_camera_preview_test side_camera_preview_test.cpp)
target_include_directories(side_camera_preview_test PRIVATE ${OpenCV_INCLUDE_DIRS})
target_link_libraries(side_camera_preview_test ${OpenCV_LIBS})
if(ANDROID)
    target_link_libraries(side_camera_preview_test log android)
endif()

add_executable(latest_preview_frame_test latest_preview_frame_test.cpp)
