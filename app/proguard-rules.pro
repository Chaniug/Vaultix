# Vaultix ProGuard 规则
# 参考文档：Docs/11-工程规范与构建体系.md（注意保留序列化模型与 JNI 符号）

# 保留注解与泛型签名（Room / 序列化数据类依赖）
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlinx.serialization.**

# Hilt / KSP 生成代码
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper

# 后续 KDBX / Bitwarden 数据模型将在此追加 -keep 规则，避免混淆破坏序列化
