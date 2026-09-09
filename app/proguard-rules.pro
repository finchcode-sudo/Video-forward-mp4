# ffmpeg-kit 内部大量使用JNI/反射调用，混淆时必须保留这些类，
# 否则代码压缩后App会在运行时崩溃（找不到方法）
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }
-dontwarn com.arthenica.**
