# ColorOS 飞牛 Bridge

这是一个 LSPosed 模块，用于修复部分 ColorOS 相册无法连接飞牛 NAS 的问题。问题根因是相册在本地加载 token 解密 prefix 时调用 `cryptoeng cmd 26`，但系统底层拒绝了 `com.coloros.gallery3d` 进程，导致 prefix 为空、token 解密失败、NAS 连接流程无法继续。

## 功能

- 只作用于 `com.coloros.gallery3d`。
- Hook 相册内已知 token 解密类 `erq`、`in80` 或 `op80` 的 `e()`，也就是相册侧 prefix 加载方法。
- 优先保留系统原始 `cryptoeng` 路径，只有原方法返回空字符串或 `null` 时才提供 fallback。
- fallback 会优先解析当前安装的相册 APK dex 字符串池，自动提取包含 `GwToken` 的精确 prefix。
- 如果 APK 扫描失败，会使用当前已验证的飞牛 token prefix 作为兜底。
- 这是纯 legacy Xposed Bridge 模块，不使用 libxposed API。
- 模块内置 legacy 作用域推荐，LSPosed 应自动推荐 `相册 / com.coloros.gallery3d`。

## 实现原理

ColorOS 相册会通过已保存的 access token 和 refresh token 构造飞牛 NAS 的 `ConnectionSetupData`。token 解密使用 AES-GCM，密钥派生方式是：

```text
SHA-256(prefix + deviceId)
```

受影响的系统版本中，相册会通过 `cryptoeng cmd 26` 获取 `prefix`。但 `cryptoeng` 根据调用进程做权限检查，拒绝了相册进程，导致 `prefix` 为 `null`。随后 token 解密返回 `null`，相册也就不会继续建立 NAS 连接。

本模块只在原始 prefix 加载方法执行之后检查结果：

- 如果原方法已经成功返回 prefix，模块不做任何修改。
- 如果原方法返回空或 `null`，模块从相册 APK 的 dex 字符串池中解析飞牛 prefix 并返回给相册。
- 相册随后继续执行它自己的 token 解密和 NAS 连接流程。

模块不会伪造 token，不会伪造连接对象，也不会跳过飞牛服务端认证。它只恢复相册原有链路缺失的本地 prefix 值。

## 不做什么

- 不修改相册 APK。
- 不修改 MyDevices。
- 不修改数据库。
- 不修改账号绑定或 NAS 设备记录。
- 不绕过飞牛服务端认证。
- 不打印、不保存、不上传 token。
- 不在相册进程外解密 token。

## 兼容性

LSPosed/Xposed 要求：

- 最低声明 API：`82`
- 推荐作用域：`com.coloros.gallery3d`
- 入口：`assets/xposed_init`
- 作用域推荐：`AndroidManifest.xml` 中的 `xposedscope` metadata
- 不包含 `META-INF/xposed/java_init.list`，不声明 libxposed 入口。
- 编译依赖：`de.robv.android.xposed:api:82`

已在 ColorOS 16 / Android 16 的相册版本上验证。附近版本理论上也可用，但需要保持以下点不变：

相册 16.40.13 已核实使用 `com.oplus.aiunit.vision.op80.e()`。

- 目标包名：`com.coloros.gallery3d`
- token 解密类：`com.oplus.aiunit.vision.erq`、`com.oplus.aiunit.vision.in80` 或 `com.oplus.aiunit.vision.op80`
- prefix 加载方法：`e()`
- token 密钥派生方式：`SHA-256(prefix + deviceId)`

如果 OPPO/OnePlus 后续改了混淆类名、方法名或 token 派生方式，模块需要跟进适配。

## 构建

GitHub Actions 会在每次 push、pull request 和手动触发时自动构建 APK，并上传 debug/release unsigned APK artifact。

也可以用 Android Studio 打开本仓库，然后运行 `app` 构建任务。

本地有 Gradle 时可以执行：

```bash
gradle :app:assembleRelease
```

如果发布前需要仓库内置 Gradle Wrapper，可以在有 Gradle 的机器上执行一次：

```bash
gradle wrapper --gradle-version 8.7
```

构建产物位置：

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

release APK 需要自行签名后再分发。

## 安装

1. 安装已签名 APK，或者安装 GitHub Actions 生成的 debug APK。
2. 在 LSPosed 中启用模块。
3. LSPosed 应自动推荐 `相册 / com.coloros.gallery3d` 作用域，只保留这个作用域即可。
4. 强停相册或重启手机。
5. 打开相册，进入飞牛 NAS / 私有云入口。

## 预期日志

LSPosed 日志中应能看到：

```text
ColorOSFeiniuBridge: installed for com.coloros.gallery3d
ColorOSFeiniuBridge: prefix fallback supplied len=33
```

随后相册会继续原有连接流程，并连接飞牛 NAS 服务。

## 排查

- 没有 `ColorOSFeiniuBridge` 日志：模块没有被 LSPosed 加载，检查模块是否启用、作用域是否包含 `com.coloros.gallery3d`、是否重启或强停相册。
- `install failed: ClassNotFoundException`：相册混淆类名变了，需要重新定位 token 解密类。
- 没有 `prefix fallback supplied`：原始 `cryptoeng` 可能已经成功，或者没有触发飞牛入口。
- fallback 后仍无法连接：检查相册日志里是否有 `AEADBadTagException`、token 过期、NAS 不可达、账号绑定异常等问题。
- token 解密成功但相册为空：本模块只恢复连接构造，照片索引和同步状态由相册与飞牛 NAS 自身处理。

常用排查命令：

```bash
adb shell logcat | grep -iE 'ColorOSFeiniuBridge|FeiniuNasSDK|TokenDecryptor|NasAlbum|cryptoeng'
adb shell su -c 'ss -tnp | grep 5667'
```

## 安全说明

本模块仅用于用户访问自己拥有并已绑定的飞牛 NAS。模块恢复的是相册本地 prefix 加载失败的问题，不改变 token 来源、不改变服务端校验、不改变账号或设备绑定。

请不要将本模块用于访问你不拥有或无权访问的设备、账号或 NAS 服务。

## 许可证

MIT
