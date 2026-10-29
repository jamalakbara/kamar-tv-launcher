cmake_minimum_required(VERSION 3.22.1)
project(kamar_airplay C CXX)

# AirPlay receiver: RPiPlay-style RAOP server (from KqSMea8/AirplayServer) plus the FDK AAC
# decoder for the AAC-ELD audio that comes with screen mirroring.
set(AP ${CMAKE_CURRENT_SOURCE_DIR}/airplay)

# The old C sources lean on implicit libc declarations; give every file the headers up front.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -w -O2 -include string.h -include stdlib.h -include stdio.h")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -w -O2 -fno-exceptions -fno-rtti")

file(GLOB CRYPTO_SRC ${AP}/crypto/*.c)
file(GLOB CURVE_SRC ${AP}/curve25519/*.c)
file(GLOB ED_SRC ${AP}/ed25519/*.c)
file(GLOB PLAYFAIR_SRC ${AP}/playfair/*.c)
file(GLOB PLIST_SRC ${AP}/plist/*.c)
add_library(ap_crypto STATIC ${CRYPTO_SRC})
add_library(ap_curve25519 STATIC ${CURVE_SRC})
add_library(ap_ed25519 STATIC ${ED_SRC})
add_library(ap_playfair STATIC ${PLAYFAIR_SRC})
target_include_directories(ap_playfair PRIVATE ${AP}/playfair)
add_library(ap_plist STATIC ${PLIST_SRC})

set(FDK_PARTS libAACdec libFDK libSYS libMpegTPDec libSBRdec libPCMutils libArithCoding libDRCdec libSACdec)
set(FDK_SRC "")
set(FDK_INC "")
foreach(part ${FDK_PARTS})
    file(GLOB part_src ${AP}/fdk-aac/${part}/src/*.cpp)
    list(APPEND FDK_SRC ${part_src})
    list(APPEND FDK_INC ${AP}/fdk-aac/${part}/include)
endforeach()
add_library(ap_fdkaac STATIC ${FDK_SRC})
target_include_directories(ap_fdkaac PUBLIC ${FDK_INC})

file(GLOB RAOP_SRC ${AP}/*.c)
add_library(kamar_airplay SHARED ${RAOP_SRC} jni_airplay.cpp)
target_include_directories(kamar_airplay PRIVATE
        ${AP} ${AP}/crypto ${AP}/curve25519 ${AP}/ed25519 ${AP}/playfair ${AP}/plist)
target_link_libraries(kamar_airplay
        ap_crypto ap_curve25519 ap_ed25519 ap_playfair ap_plist ap_fdkaac log)
