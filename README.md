# wuwa_login · 鸣潮直播扫码器Mac

JDK 25 + JavaFX 21，全程Ai开发

## 运行

安装 JDK 25 和 Maven 3.9，执行：

```bash
mvn javafx:run
```

IntelliJ IDEA 中重新加载 Maven 项目，也可运行 `org.example.Main`。

## 手机号与 Token 登录

1. 输入 11 位手机号，点击「手机号登录」。
2. 已有 Token 可直接粘贴登录，q群机器人bot获取token直接粘贴登陆，支持 `token, did`、中文逗号，以及 `添加token token, did` 格式。

## 图片扫码登录

登录后点击「扫码登录游戏」，选择二维码图片（PNG/JPG/BMP）或粘贴二维码原始内容，成功后显示「扫码成功 · 请二次确认登录」区域，核对账号，若返回了角色则选择角色，再点击「二次确认登录」。也可以点击「取消本次扫码」结束，识别与扫描阶段不会直接提交登录

## 抖音直播扫码

登录后输入 **抖音直播网页房间号**（例如 `https://live.douyin.com/123456` 中的 `123456`）或完整的该网页链接，点击「开始扫描」。房间号作为 `web_rid`，不等同于主播个人抖音号、内部 room_id 或用户 ID；暂不支持 `v.douyin.com` 短链接。

## 感谢

- [mxyooR/Kuro_login](https://github.com/mxyooR/Kuro_login)
- [DSVVA/KuRo_Scanner(https://github.com/DSVVA/KuRo_Scanner/blob/main/src/Core/KuRoApi.cpp)