# ProGuard rules for 滴滴TV (EmbyTV)

# ---- 保留元数据 ----
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses

# ---- Fragment 子类：FragmentManager 通过类名反射实例化，类名必须保留 ----
-keep class com.embytv.view.*Fragment { *; }

# ---- Manifest 组件 ----
-keep class com.embytv.MainActivity { *; }
-keep class com.embytv.view.PlayerActivity { *; }

# ---- MPVLib：JNI native 方法绑定类名/方法名，必须保留 ----
# ⚠️ 包名是 is.xyz.mpv（不是 com.embytv.mpv）！写错会让 R8 混淆掉 native 回调的
#    eventProperty/event/logMessage 静态方法 → NoSuchMethodError abort 崩溃
-keep class is.xyz.mpv.MPVLib { *; }
-keep class is.xyz.mpv.** { *; }

# ---- Gson 反序列化模型：字段名 = JSON key，必须保留 ----
-keep class com.embytv.app.EmbyServerConfig { *; }
-keep class com.embytv.app.WebDavServerConfig { *; }
-keep class com.embytv.api.EmbyServerConfig { *; }

# ---- 枚举：保留 values()/valueOf ----
-keepclassmembers enum * { *; }

# ---- 反射调用的 ass-media 内部方法（setRenderCallback/getRender/getTrack 等）----
# 这些是第三方库方法，库自带 consumer 规则；这里防混淆破坏反射
-keep class io.github.peerless2012.ass.** { *; }
-keep class com.rubensousa.dpadrecyclerview.** { *; }

# ---- JNI 库依赖 ----
-dontwarn org.ietf.jgss.**
-dontwarn com.jcraft.jzlib.**
-dontwarn org.slf4j.**
