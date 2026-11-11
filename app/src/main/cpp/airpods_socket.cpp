// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from LibrePods bluetooth_socket.cpp, LibrePods contributors, pinned 89356c08.
// No JNI_OnLoad side effects; exemptions run lazily on an attached native thread.
#include <jni.h>
#include <thread>

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_lelonio_square_airpods_AirPodsSocket_enableExemptions(JNIEnv* caller, jobject) {
    JavaVM* vm = nullptr;
    if (caller->GetJavaVM(&vm) != JNI_OK || vm == nullptr) return JNI_FALSE;
    bool ok = false;
    try {
        std::thread worker([&] {
            JNIEnv* env = nullptr;
            if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
            // Every failed JNI lookup is cleared before returning to Kotlin.
            do {
                jclass runtimeClass = env->FindClass("dalvik/system/VMRuntime");
                if (env->ExceptionCheck() || !runtimeClass) break;
                jmethodID get = env->GetStaticMethodID(runtimeClass, "getRuntime", "()Ldalvik/system/VMRuntime;");
                if (env->ExceptionCheck() || !get) break;
                jmethodID set = env->GetMethodID(runtimeClass, "setHiddenApiExemptions", "([Ljava/lang/String;)V");
                if (env->ExceptionCheck() || !set) break;
                jobject runtime = env->CallStaticObjectMethod(runtimeClass, get);
                if (env->ExceptionCheck() || !runtime) break;
                jclass strings = env->FindClass("java/lang/String");
                if (env->ExceptionCheck() || !strings) break;
                jobjectArray prefixes = env->NewObjectArray(2, strings, nullptr);
                if (env->ExceptionCheck() || !prefixes) break;
                jstring socket = env->NewStringUTF("Landroid/bluetooth/BluetoothSocket;");
                if (env->ExceptionCheck() || !socket) break;
                jstring device = env->NewStringUTF("Landroid/bluetooth/BluetoothDevice;");
                if (env->ExceptionCheck() || !device) break;
                env->SetObjectArrayElement(prefixes, 0, socket);
                if (env->ExceptionCheck()) break;
                env->SetObjectArrayElement(prefixes, 1, device);
                if (env->ExceptionCheck()) break;
                env->CallVoidMethod(runtime, set, prefixes);
                ok = !env->ExceptionCheck();
            } while (false);
            if (env->ExceptionCheck()) env->ExceptionClear();
            vm->DetachCurrentThread();
        });
        worker.join();
    } catch (...) { return JNI_FALSE; }
    return ok ? JNI_TRUE : JNI_FALSE;
}
