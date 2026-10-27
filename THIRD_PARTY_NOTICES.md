# 第三方组件与来源

## Xposed API 82

`libs/xposed-api-82.jar` 是 Xposed API 的编译期依赖，不把 Xposed / LSPosed 框架实现打包进 APK。

- 上游：[rovo89/XposedBridge](https://github.com/rovo89/XposedBridge)
- API 文档：[api.xposed.info](https://api.xposed.info/)
- 许可：Apache License 2.0；随附完整许可与上游 NOTICE，见 `libs/licenses/`。
- 本仓库 JAR 的 SHA-256：`f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25`。

## Android 与构建工具

Android SDK、Build Tools、NDK、JDK 和 LSPosed 由使用者另行准备，保留其上游许可，不包含在本源码仓库中。ARM64 库使用 NDK 提供的 C++ 运行库，相关许可见随附 Android NDK libc++ / libc++abi 文本。

本项目包含对系统接口和行为的适配，未上传小米、锤子等厂商 ROM、APK、反编译类或研究采集材料。产品名、服务名与商标属于各自权利人。本说明不为 Mi Step 自有代码授予新的开源许可。
