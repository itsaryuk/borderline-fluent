# R8 keep rules: only what is reached by name. Libraries' own consumer rules (ML Kit, CameraX, Play, AndroidX,
# coroutines) come with their AARs; enums' values()/valueOf (CareerJson, prefs "input") are kept by
# proguard-android-optimize.txt, and enum constant names survive renaming (the name is a string literal).

# sherpa-onnx (its AAR ships an empty proguard.txt): the JNI side reads the config classes field by field
# (GetFieldID by name, nested configs included), builds result objects (GeneratedAudio, OfflineRecognizerResult)
# through their constructors, and binds the `external fun`s by class and method name.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Our TTS streaming callback: sherpa-onnx's JNI calls exactly invoke([F)Ljava/lang/Integer; on it.
-keep class com.lampensoft.borderlinefluent.Piper$OnChunk { *** invoke(float[]); }

# Older libraries' consumer rules keep these classes by name but not their no-arg constructors, which R8 full mode
# (AGP default) then strips; each is built by Class.forName(...).newInstance() at runtime:
# - Room 2.5 (via Play asset-delivery -> WorkManager 2.9) builds WorkDatabase_Impl by name at process start.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
# - WorkManager creates InputMergers (OverwritingInputMerger) from the class name stored with each work request.
-keep class * extends androidx.work.InputMerger { <init>(); }
# - ML Kit (face detection, language id) instantiates its ComponentRegistrars from manifest meta-data names.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }

# Exception class names reach the player (Game.kt loadError, step_failed use javaClass.simpleName): no "a1".
-keepnames class * extends java.lang.Throwable

# Readable crash stack traces in Play Console (deobfuscated with the uploaded mapping.txt).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
