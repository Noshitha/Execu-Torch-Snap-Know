# ExecuTorch JNI - keep native method signatures
-keep class com.snapknow.app.inference.ExecuTorchModule { native <methods>; }
# Room entities
-keep class com.snapknow.app.database.entity.** { *; }
