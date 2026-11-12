# wuwa_login · JavaFX Token 登录

JDK 25 + JavaFX 21 的桌面界面。账号页只有一个固定平台，不提供平台下拉选项。

已实现 Token 输入、显示/隐藏、空值和空白字符检查、异步登录状态、错误反馈，以及打开浏览器获取 Token。Token 只保留在内存中，不写入文件，登录成功后清空输入框。

## 运行

安装 JDK 25 和 Maven 3.9，在本目录执行（IntelliJ IDEA 中重新加载 Maven 项目后，也可运行 `org.example.Main`）：

```bash
mvn javafx:run
```

配置固定平台名称和获取 Token 的 HTTPS 地址：

```bash
mvn javafx:run -Dplatform.name=你的平台 -Dplatform.tokenUrl=https://your-platform.com/settings/tokens
```

系统必须有桌面显示环境；Maven 会自动下载对应操作系统的 JavaFX 依赖。

## 接入实际平台

目前没有提供平台名称、Token 获取网址及认证 API。默认显示「默认平台」；未配置获取地址时提示补充地址，登录时明确提示尚未接入接口，不会伪造登录成功。

实际接入时，在 `TokenClientApp` 中将 `TokenAuthService.unconfigured()` 替换成认证服务实现。`login(token)` 应立即返回 `CompletionStage<LoginResult>`，网络请求使用异步 HTTP 客户端，避免阻塞 JavaFX 线程。成功返回 `LoginResult(true, "登录成功")`，失败返回不含 Token 的用户提示；不要把 Token 或服务器原始错误写入日志或消息。

## 验证与预览

```bash
mvn test
mvn test -Dpreview.path=target/account-preview.png
```

测试使用 Monocle 在无显示器环境运行真实 JavaFX 控件，覆盖输入校验、Token 显隐、获取地址、异步成功/失败状态，以及固定平台没有选择控件。可选参数会导出初始界面 PNG。
