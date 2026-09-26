cmake_minimum_required(VERSION 3.22.1)
project("astrohyperlapse")

find_package(OpenCV REQUIRED)

add_library(astro_processor SHARED src/main/cpp/native-astro-processor.cpp)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(astro_processor
        ${OpenCV_LIBS}
        ${log-lib}
        ${jnigraphics-lib}
        -fopenmp -static-openmp
)
