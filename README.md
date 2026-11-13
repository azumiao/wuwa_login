# wuwa_login · 鸣潮直播扫码器Mac

JDK 25 + JavaFX 21，全程Ai开发

## 运行

安装 JDK 25 和 Maven 3.9，执行：

```bash
mvn javafx:run
```

IntelliJ IDEA 中重新加载 Maven 项目，也可运行 `org.example.Main`。

## 打包后双击启动

使用 JDK 25 自带的 `jpackage`，将应用、JavaFX、OpenCV、FFmpeg、二维码模型和 Java 运行环境一起打包。打包后的电脑无需安装 IDEA、Java 或 Maven。

**打包机器**需要安装与系统架构一致的完整 JDK 25 和 Maven 3.9，先执行 `mvn -version` 确认 Maven 使用 JDK 25；IDEA 的项目 SDK 设置不会自动配置终端的 Java 环境。

- **macOS**：双击项目根目录的 `build-app.command`，完成后双击 `target/dist/WuwaLogin.app` 启动。脚本在未设置 `JAVA_HOME` 时自动查找已安装的 JDK 25。
- **Windows x64**：双击 `build-app.cmd`，完成后双击 `target\dist\WuwaLogin\WuwaLogin.exe` 启动。
- **Linux**：执行 `bash build-app.command`，完成后运行 `target/dist/WuwaLogin/bin/WuwaLogin`。

也可在项目根目录执行相同的打包命令，或在 IDEA Maven 面板勾选 `app` profile 后依次运行 `clean`、`package`：

```bash
mvn -Papp clean package
```

首次构建需要联网下载依赖，默认先运行测试再打包。macOS 脚本若提示没有执行权限，先执行 `chmod +x build-app.command`。从 Finder 启动找不到 Maven 时，可在终端执行上述命令，或先将 Maven 加入 `PATH`。

分发时，macOS 复制整个 `WuwaLogin.app`；Windows/Linux 压缩并分发整个 `WuwaLogin` 文件夹，不能只复制其中的可执行文件。JavaCPP 原生库保留独立 JAR，避免合并 JAR 后丢失原生资源或签名。产物包含运行环境和原生库，体积会较大。

需要在目标系统上打包：macOS 的 Intel 与 Apple Silicon 版本分别使用对应架构的 JDK 构建，不能在 Linux 上生成可用的 `.app` 或 Windows `.exe`。这里生成的是免安装应用，macOS 版本未做 Developer ID 签名或公证；分发到其他 Mac 时可能需要按系统提示允许打开。Linux 仍需系统图形桌面及 JavaFX 所需的系统图形库。

当前已在 Linux x64 完成完整构建、现有测试和应用包独立启动验证；macOS、Windows 产物需在对应系统验证。

## 手机号与 Token 登录

1. 输入 11 位手机号，点击「手机号登录」。
2. 已有 Token 可直接粘贴登录，q群机器人bot获取token直接粘贴登陆，支持 `token, did`、中文逗号，以及 `添加token token, did` 格式。

## 图片扫码登录

登录后点击「扫码登录游戏」，选择二维码图片（PNG/JPG/BMP）或粘贴二维码原始内容，成功后显示「扫码成功 · 请二次确认登录」区域，核对账号，若返回了角色则选择角色，再点击「二次确认登录」。也可以点击「取消本次扫码」结束，识别与扫描阶段不会直接提交登录

## 抖音直播扫码

登录后输入 **抖音直播网页房间号**（例如 `https://live.douyin.com/123456` 中的 `123456`）或完整的该网页链接，点击「开始扫描」。房间号作为 `web_rid`，不等同于主播个人抖音号、内部 room_id 或用户 ID；暂不支持 `v.douyin.com` 短链接。

## 感谢

- [mxyooR/Kuro_login](https://github.com/mxyooR/Kuro_login)
- [DSVVA/KuRo_Scanner](https://github.com/DSVVA/KuRo_Scanner)
