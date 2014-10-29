# Copyright (C) 2009 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,k
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := sodium
LOCAL_SRC_FILES := $(TARGET_ARCH_ABI)/libsodium.a
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/include
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := sodium_wrapped
LOCAL_MODULE_FILENAME := libsodium
LOCAL_SRC_FILES := sodium_wrap.c
LOCAL_CFLAGS := -fPIC
LOCAL_STATIC_LIBRARIES := sodium
include $(BUILD_SHARED_LIBRARY)