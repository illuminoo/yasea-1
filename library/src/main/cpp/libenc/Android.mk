LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := libenc
LOCAL_SRC_FILES := libenc.cc
LOCAL_CFLAGS    :=
LOCAL_C_INCLUDES += $(LOCAL_PATH)/../libyuv/include $(LOCAL_PATH)/../libx264
LOCAL_STATIC_LIBRARIES := libx264
LOCAL_SHARED_LIBRARIES := libyuv
LOCAL_DISABLE_FORMAT_STRING_CHECKS := true
LOCAL_DISABLE_FATAL_LINKER_WARNINGS := true

include $(BUILD_SHARED_LIBRARY)