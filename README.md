# wuwa_login · 库街区登录与直播监视界面

JDK 25 + JavaFX 21，直播扫码开发分支为 `scaner`。默认连接真实库街区认证服务。

## 运行

安装 JDK 25 和 Maven 3.9，执行：

```bash
mvn javafx:run
```

IntelliJ IDEA 中重新加载 Maven 项目，也可运行 `org.example.Main`。

## 手机号与 Token 登录

1. 输入 11 位手机号，点击「手机号登录」。
2. 自动打开官方极验 v4 验证窗口，按提示点选符号或完成滑块。验证通过后，向 `/user/getSmsCode` 提交验证结果并发送短信。内置窗口加载失败时，点击「在系统浏览器完成验证」，完成后结果自动返回应用。取消、加载失败或超时均不会发送短信，可点击「获取验证码」重试。
3. 输入 6 位短信验证码，调用 `/user/sdkLogin`。取得 Token 后，再通过 `/user/mineV2` 核实当前登录账号；成功才进入账号页并保存凭据。

已有 Token 可直接粘贴登录，也支持 `token, did`、中文逗号，以及 `添加token token, did` 格式。逗号前是 JWT，逗号后是设备标识，不是 JWT 的一部分。导入时会保留配套 did，验证成功后与 Token 一起保存，扫码时使用该会话的设备标识。只导入 Token 时使用本应用的设备标识；已知原 did 时请一并导入。

客户端先拒绝错误格式、`demo-` Token、非 HS256、缺少用户 ID 或明确过期的 JWT，再向官方接口核验身份。**本地解析 JWT 不验证签名，也不作为登录成功依据**；必须得到官方成功响应、匹配的用户 ID 和当前登录用户标记。没有 `exp` 的 Token 同样需要在线验证。账号页「获取 Token」分别显示 Token 和 did，支持「复制 Token」及「复制 Token + did」。

每次启动都重新核验保存的 Token，验证完成前保持未登录。官方明确返回失效（如 `220`）时清除缓存；断网、超时、限流、无法识别的响应保持未登录并保留凭据，可点击「重试本地登录」或「清除本地登录」。

登录信息及配套 did 保存在当前用户目录的 `~/.wuwa_login/session.json`；本应用手机号登录的默认设备标识保存在 `device-id`。新设备标识使用 UUID，已有 40 位十六进制设备标识仍会保留；旧版会话文件没有 did 时，在线核验后使用原本地设备标识迁移。文件采用临时文件替换写入；POSIX 系统限制目录为 `700`、文件为 `600`，Windows 继承用户目录访问权限。Token 当前以明文保存在这个用户私有文件中，不要分享该文件。关闭窗口保留凭据，主动退出登录删除凭据并保留设备标识，避免每次启动被视为新设备。演示模式不会读写真实登录信息。

验证页参考 [DSVVA/KuRo_Scanner 的 WindowGeeTest](https://github.com/DSVVA/KuRo_Scanner/blob/main/src/UI/WindowGeeTest.cpp)，通过 `http://127.0.0.1:随机端口/随机路径/index.html` 加载，设置 `clientType: "android"`。参考项目使用 Windows WebView2，本项目保留跨平台 JavaFX WebKit，并提供系统浏览器入口解决内置引擎兼容性问题。脚本下载、初始化和组件错误均显示提示，15 秒仍未就绪时提示使用系统浏览器。

验证结果通过同源回调自动返回。临时 HTTP 服务仅绑定回环地址，校验随机路径、Host、Origin、结果格式和请求大小；取消、完成或 120 秒超时后关闭监听。页面不包含手机号、账号 Token 或本地凭据。

## 游戏扫码登录

登录后点击「扫码登录游戏」，选择二维码图片（PNG/JPG/BMP）或粘贴二维码原始内容，程序会自动扫描。成功后显示「扫码成功 · 请二次确认登录」区域，核对账号，若返回了角色则选择角色，再点击「二次确认登录」。也可以点击「取消本次扫码」结束，识别与扫描阶段不会直接提交登录。请求固定发往官方 API，不会访问二维码中的网址。

- `/user/auth/roleInfos`：扫描二维码；可用角色信息会显示在确认区域，没有返回角色绑定 ID 时也能进入二次确认。
- `/user/auth/scanLogin`：仅在点击确认后提交二维码和选择的角色；未返回绑定 ID 时按参考项目传空 `id`。`2240` 时进入短信验证状态，保留本次二维码和角色。
- `/user/sms/scanSms`：用户完成人机验证后发送扫码短信，输入验证码后继续同一次扫码登录。失败时允许重试，不会绕过验证码或自动改用空验证码。
- `2209`：二维码过期，必须重新获取二维码并读取角色。`220`：账号 Token 失效，退出账号并清除本地凭据。

短信发送成功后有 60 秒重试倒计时。请求期间防止重复提交，关闭弹窗后忽略迟到响应。网络超时的扫码请求不会自动重发，界面提示结果尚未确认。

修改或取消二维码会清除原来的确认状态；扫描失败、二维码过期或角色明确不受支持时不能确认登录。手动「扫描二维码」按钮可用于失败后的重试。

短信、登录、扫描、确认请求使用同一设备上下文，并按 KuRo_Scanner 对齐 `source=android`、`version=2.5.0`、`versionCode=2500`、`devCode=did`，同时提供独立的 `did` 请求头。扫码验证、二次确认、扫码短信分别显示服务端错误码与脱敏原因，HTTP 错误也显示状态码；不会输出原始 Token、did、二维码或短信验证码。HTTP 403/一般业务拒绝不会直接当作 Token 过期。二次确认按参考项目以明确的 `code=200` 判定成功，同时拒绝显式的 `success=false` / `data=false`。

## 抖音直播扫码（scaner）

登录后输入 **抖音直播网页房间号**（例如 `https://live.douyin.com/123456` 中的 `123456`）或完整的该网页链接，点击「开始扫描」。房间号作为 `web_rid`，不等同于主播个人抖音号、内部 room_id 或用户 ID；暂不支持 `v.douyin.com` 短链接。

取流流程：

1. 访问房间页，通过独立的 CookieManager 接收服务端 Cookie，解析 `RENDER_DATA` 或 Next/Flight 内嵌 JSON。
2. 页面无可用流时，使用同一 Cookie 会话和 User-Agent 请求 `webcast/room/web/enter/`；签名覆盖实际发送的 URL 编码参数，包括 `web_rid` 和空 `msToken`。
3. `a_bogus` 使用 Java 实现的 SM3/RC4/变体 Base64，与公开 Python 实现的固定时间测试向量核对。平台签名变化可能导致通道失效，会显示空响应/拒绝等原因。
4. 解析 `pull_datas`、`live_core_sdk_data.pull_data.stream_data` 和传统 FLV/HLS 字段，优先原画 FLV，再回退可用清晰度及 HLS。将 UA、Referer 传给 FFmpeg。库街区 Token/did 不进入取流请求，房间 Cookie 不传给其他域的 CDN。

Next/Flight 长字符串通过逐字符扫描引号和转义字符解析，避免 Java 递归正则在大型房间页面上触发 `StackOverflowError`。后台线程异常退出会停止本次扫描并中断另一个线程。错误提示区分 `CAPTURE`（取流/解码）、`QR_INIT`（二维码组件初始化）和 `QR_DECODE`（识别执行）；原生库失败显示异常链、运行系统与 JDK 架构，并提示重新加载 Maven。控制台提供有长度限制的脱敏原因及调用位置，不记录完整直播地址、Token 和二维码。

视频由 JavaCV/FFmpeg 持续解码，二维码直接使用 **OpenCV WeChatQRCode**。图片扫码也使用同一个识别实现；ZXing 仅作为测试二维码生成器。四个模型随应用打包，加载时验证 SHA-256，首次扫码无需下载模型。

创建 WeChat 识别器前先加载 DNN，再按 `flann → features2d → calib3d → objdetect → wechat_qrcode` 加载原生依赖。macOS 的 OpenCV 4.10 WeChat 二进制链接了这些模块，但对应 JavaCPP preset 只声明了 DNN；显式加载用于补齐该依赖缺口。直接加载底层库，不引入 OpenCV GUI 模块，不需要通过 Homebrew 安装另一套 OpenCV，也不需要手动修改 `java.library.path`。

原生加载诊断会单独保留 `Library not loaded`、`Symbol not found`、`Reason` 等 dyld 原因，长缓存路径从中间缩短，不再使用界面短提示的 180 字符上限截断控制台日志。状态长文本自动换行，避免挤压预览区域。

采集与识别各有独立线程，只保留一张待识别最新帧。跨线程传递独立的 BGR 字节，不引用 FFmpeg 复用内存。识别前、识别后及界面接收时检查帧龄；默认本机帧龄上限 500ms，可通过 JVM 参数 `-Dapp.scan.maxAgeMs=500` 调整（50～5000ms）。开始、停止、重连与 PTS 回退会隔离旧结果。读取有 5 秒网络 I/O 超时，停止不会等待网络或原生识别而阻塞界面；工作线程在阻塞操作返回后自行释放资源。

连接异常后最多重试三次，每次重新解析房间地址。未开播立即停止并提示。媒体 PTS 相对单调时钟落后增长超过 2.5 秒时重新连接。该指标只能反映连接后的延迟增长，**本机帧龄和 PTS 均不能证明主播到 CDN 的绝对延迟**。

识别到有效游戏二维码后自动进入现有扫码确认弹窗，点击二次确认才登录。等待确认或短信验证时锁定当前二维码，拉流持续读取；关闭弹窗后恢复识别，同一码短时间内去重；已成功或明确过期的码在当前扫描会话内不再自动提交（最多记住 128 个）。取消或失败后也可在弹窗手动重试。停止扫描、退出账号或关闭窗口后，旧回调不会发起新扫码。

**预览尚未接入**，画面区域保留说明；扫码完全独立运行。状态区提供读取/识别帧数、覆盖旧帧数、过旧丢弃数、识别耗时、本机帧龄与流延迟增长。

原生依赖按当前平台下载：Windows x64、macOS Intel/Apple Silicon、Linux x64/ARM64；也可用 Maven 参数 `-Djavacpp.platform=macosx-arm64` 等显式指定。需使用与系统架构一致的 JDK 25，IDEA 修改依赖后重新加载 Maven 项目。当前自动验证运行于 Linux x64，其余平台需本机验证。

测试包括独立签名向量、模拟抖音服务的 Cookie/请求头/签名传递、嵌套 JSON 与过期/未开播状态、慢识别跳帧与取消，以及真实 FFmpeg 本地视频解码 → WeChat 模型识别。云环境当前不允许连接抖音，真实房间取流、CDN 兼容性与端到端直播延迟仍需可访问抖音的环境联调。

## 演示与测试

```bash
mvn javafx:run -Dapp.demo=true
mvn test
mvn test -Dpreview.dir=target/previews
```

演示模式有明确标识，不发送真实短信、不验证真实 Token；演示短信验证码为 `123456`。测试使用本地 HTTP 模拟服务和 Monocle JavaFX 界面，覆盖短信验证前置、请求编码、非法及失效 Token、服务端身份不匹配、断网保留凭据、启动恢复、退出清理、扫码首次短信验证及取消后的迟到响应。验证页测试实际运行 WebView 与模拟 SDK，检查 HTTP 页面来源、Android 参数、成功回调后才发送短信、初始化错误提示、浏览器回传与监听清理。测试不发送真实短信，不使用真实账号。

## 接口参考与联调

- [mxyooR/Kuro_login](https://github.com/mxyooR/Kuro_login)：短信、SDK 登录、极验 captcha ID 和请求头。采用官方交互式验证组件，未移植自动解题逻辑。
- [Haiyu 的 KuroClient](https://github.com/HaiyuGame/Haiyu/blob/master/src/Haiyu.KuroClient/KuroClient.cs)：扫码接口、角色字段。
- [KuRo_Scanner 社区实现](https://github.com/cjh-better/wuthering-waves-scancer/blob/master/utils/kuro_api.py)：扫码短信参数；[状态处理](https://github.com/cjh-better/wuthering-waves-scancer/blob/master/ui/main_window.py)中的 `2240` / `2209`。
- [mineV2 接口记录](https://github.com/TomyJan/Kuro-API-Collection/blob/main/API/user/mineV2.md)：Token 认证与当前用户标记。
- [DSVVA/KuRo_Scanner 的 KuRoApi](https://github.com/DSVVA/KuRo_Scanner/blob/main/src/Core/KuRoApi.cpp)：扫码请求头、设备标识、表单字段及二次确认成功判据。
- [XutheringWavesUID 的 Token 帮助](https://github.com/Loping151/XutheringWavesUID/blob/main/XutheringWavesUID/wutheringwaves_help/help.json)：`添加token <token>,<did>` 格式；[请求实现](https://github.com/Loping151/XutheringWavesUID/blob/main/XutheringWavesUID/utils/api/requests.py)使用独立的设备标识。

实际运行需能访问 `api.kurobbs.com`、`static.geetest.com`、`gcaptcha4.geetest.com` 及极验返回的静态资源域名。当前云环境的网络策略未开放库街区和极验域名，因此真实验证码图案、短信下发、真实 Token 和游戏扫码尚未进行在线联调；本地模拟测试不能代替这部分验证。上游接口或 captcha ID 变更时需更新对应适配。
