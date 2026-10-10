cmake_minimum_required(VERSION 3.22.1)
project(nekoplayer_importer LANGUAGES CXX)

add_library(nekoplayer_importer SHARED native_yomitan_term_bank.cpp)
target_compile_features(nekoplayer_importer PRIVATE cxx_std_17)
