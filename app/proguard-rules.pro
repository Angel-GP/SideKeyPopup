# 模块入口由框架按类名反射加载，不能被混淆/裁剪。
-keep class com.linxi.sidekeypopup.xposed.** { *; }

# 框架在运行时注入这些类，编译期只有 compileOnly 的引用。
-dontwarn io.github.libxposed.api.**
-keep class io.github.libxposed.api.** { *; }
