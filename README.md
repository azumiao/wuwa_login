# wuwa_login · 库街区登录与直播监视界面

JDK 25 + JavaFX 21，开发分支为 `login`。默认连接真实库街区认证服务。

## 运行

安装 JDK 25 和 Maven 3.9，执行：

```bash
mvn javafx:run
```

IntelliJ IDEA 中重新加载 Maven 项目，也可运行 `org.example.Main`。

## 手机号与 Token 登录

1. 输入 11 位手机号，点击「手机号登录」。
2. 自动打开官方极验 v4 验证窗口，按提示点选符号或完成滑块。验证通过后，向 `/user/getSmsCode` 提交验证结果并发送短信。取消、加载失败或超时均不会发送短信，可点击「获取验证码」重试。
3. 输入 6 位短信验证码，调用 `/user/sdkLogin`。取得 Token 后，再通过 `/user/mineV2` 核实当前登录账号；成功才进入账号页并保存凭据。

已有 Token 可直接粘贴登录。客户端先拒绝错误格式、`demo-` Token、非 HS256、缺少用户 ID 或明确过期的 JWT，再向官方接口核验身份。**本地解析 JWT 不验证签名，也不作为登录成功依据**；必须得到官方成功响应、匹配的用户 ID 和当前登录用户标记。没有 `exp` 的 Token 同样需要在线验证。

每次启动都重新核验保存的 Token，验证完成前保持未登录。官方明确返回失效（如 `220`）时清除缓存；断网、超时、限流、无法识别的响应保持未登录并保留凭据，可点击「重试本地登录」或「清除本地登录」。

登录信息保存在当前用户目录的 `~/.wuwa_login/session.json`，设备标识保存在 `device-id`。文件采用临时文件替换写入；POSIX 系统限制目录为 `700`、文件为 `600`，Windows 继承用户目录访问权限。Token 当前以明文保存在这个用户私有文件中，不要分享该文件。关闭窗口保留凭据，主动退出登录删除凭据并保留设备标识，避免每次启动被视为新设备。演示模式不会读写真实登录信息。

## 游戏扫码登录

登录后点击「扫码登录游戏」，选择二维码图片（PNG/JPG/BMP）或粘贴二维码原始内容，读取角色后，选择角色并点击「确认登录游戏」。请求固定发往官方 API，不会访问二维码中的网址。

- `/user/auth/roleInfos`：检查二维码并返回可登录角色。
- `/user/auth/scanLogin`：提交二维码和选择的角色；`2240` 时进入短信验证状态，保留本次二维码和角色。
- `/user/sms/scanSms`：用户完成人机验证后发送扫码短信，输入验证码后继续同一次扫码登录。失败时允许重试，不会绕过验证码或自动改用空验证码。
- `2209`：二维码过期，必须重新获取二维码并读取角色。`220`：账号 Token 失效，退出账号并清除本地凭据。

短信发送成功后有 60 秒重试倒计时。请求期间防止重复提交，关闭弹窗后忽略迟到响应。网络超时的扫码请求不会自动重发，界面提示结果尚未确认。

直播监视与自动识别直播画面的功能仍待接入；本次提供手动图片扫码入口和可供后续识别流程调用的 `ScanService`。

## 演示与测试

```bash
mvn javafx:run -Dapp.demo=true
mvn test
mvn test -Dpreview.dir=target/previews
```

演示模式有明确标识，不发送真实短信、不验证真实 Token；演示短信验证码为 `123456`。测试使用本地 HTTP 模拟服务和 Monocle JavaFX 界面，覆盖短信验证前置、请求编码、非法及失效 Token、服务端身份不匹配、断网保留凭据、启动恢复、退出清理、扫码首次短信验证及取消后的迟到响应。测试不发送真实短信，不使用真实账号。

## 接口参考与联调

- [mxyooR/Kuro_login](https://github.com/mxyooR/Kuro_login)：短信、SDK 登录、极验 captcha ID 和请求头。采用官方交互式验证组件，未移植自动解题逻辑。
- [Haiyu 的 KuroClient](https://github.com/HaiyuGame/Haiyu/blob/master/src/Haiyu.KuroClient/KuroClient.cs)：扫码接口、角色字段。
- [KuRo_Scanner 社区实现](https://github.com/cjh-better/wuthering-waves-scancer/blob/master/utils/kuro_api.py)：扫码短信参数；[状态处理](https://github.com/cjh-better/wuthering-waves-scancer/blob/master/ui/main_window.py)中的 `2240` / `2209`。
- [mineV2 接口记录](https://github.com/TomyJan/Kuro-API-Collection/blob/main/API/user/mineV2.md)：Token 认证与当前用户标记。

实际运行需能访问 `api.kurobbs.com`、`static.geetest.com`、`gcaptcha4.geetest.com` 及极验返回的静态资源域名。当前云环境的网络策略未开放库街区和极验域名，因此真实验证码图案、短信下发、真实 Token 和游戏扫码尚未进行在线联调；本地模拟测试不能代替这部分验证。上游接口或 captcha ID 变更时需更新对应适配。
