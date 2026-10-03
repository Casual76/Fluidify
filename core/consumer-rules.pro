# Anything the native library reaches by name must survive R8 untouched: JNI
# resolves methods from strings, so a renamed or inlined member fails only at
# runtime, with "NoSuchMethodError: no non-static method ...".
#
# Every entry here has a counterpart in native/src — keep the two in step when
# adding a call across the boundary. Applied to every app that uses :core.

# native fn declarations resolved against this class
-keepclasseswithmembernames class dev.lelonio.square.nativecore.NativeBridge {
    native <methods>;
}

# called from the engine's event pump (native/src/engine.rs)
-keep class dev.lelonio.square.nativecore.NativeEvents { *; }
-keep class * implements dev.lelonio.square.nativecore.NativeEvents { *; }

# start / stop / write, called from the audio sink (native/src/sink.rs)
-keep class * implements dev.lelonio.square.nativecore.NativeAudioSink { *; }

# kotlinx.serialization generates serializers reflectively from these.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class dev.lelonio.square.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
