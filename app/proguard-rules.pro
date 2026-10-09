# 归档解析库大量使用 SPI / 反射构造 provider，必须整体保留
-keep class org.apache.commons.compress.** { *; }
-keep class com.github.junrar.** { *; }
-keep class org.tukaani.xz.** { *; }

-dontwarn org.apache.commons.compress.**
-dontwarn com.github.junrar.**
-dontwarn org.tukaani.xz.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# Kotlin 元数据 / 协程
-dontwarn kotlinx.coroutines.**

# 保留数据类字段名（备份 JSON 序列化依赖字段名，走反射读取）
-keepclassmembers class com.gaze.reader.data.** {
    <fields>;
    <init>(...);
}
