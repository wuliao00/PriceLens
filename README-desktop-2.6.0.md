# PriceLens Desktop 2.6.0（Windows x64，签名便携版）

这是 GitHub Release [`v2.6.0-desktop`](https://github.com/wuliao00/PriceLens/releases/tag/v2.6.0-desktop)
便携包的重压缩版。为绕过 Gitee 发行版 100MB 单附件上限，分成两卷（合计 122,055,098 B）：

- `PriceLens-2.6.0-x64-signed-portable.7z.001` — 99,614,720 B
- `PriceLens-2.6.0-x64-signed-portable.7z.002` — 22,440,378 B

把两卷放在同一目录，用 7-Zip 右键 `.001` →「提取到当前文件夹」。**不要**单独解压 `.002`。

## 签名

- 主程序 `PriceLens.exe` 已做 Authenticode 签名，证书 Subject：
  `CN=PriceLens (Self-Signed), O=PriceLens, C=CN`
- 证书 SHA-256：`B253FA470910B222FF7531669E6454A551ADCD7079CBA7CA623F71EC75F501AF`
- 这是**自签名**证书，不是 CA 签发。Windows 仍可能提示“未知发布者”，SmartScreen 不会因此放行；
  要彻底消除提示需要购买/申请 CA 代码签名证书。
- 校验：
  ```powershell
  Get-AuthenticodeSignature .\PriceLens.exe | Format-List Status, SignerCertificate
  ```
  应看到上述 Subject；`Status` 可能是 `UnknownError`/`UntrustedRoot`，根因是证书未装入
  “受信任的根证书颁发机构”。
- 公钥证书：`pricelens-selfsigned.cer`。只有在你信任本发布者时，才把它安装到
  “当前用户 → 受信任的根证书颁发机构”。

## 与 GitHub 原包的差异

- 主程序做了上述自签名；其余 Electron 运行时文件与原包一致。
- 打包内的 `resources/app.asar` 含一个**真机取证发现的热修**：手机备份里“平台不支持”的目标
  （拼多多/当当/识货/值得买）原先不计入“跳过”数，界面会显示“跳过 0”；现在日志/设置页显示
  “收到 N 个目标（可用 M）… 跳过 S”。仓库源码为同款改动，下一版构建会一致。
- 除这两处外，功能与 GitHub `v2.6.0-desktop` 相同。

## 手机联动的前置条件（重要）

桌面端手机联动使用 **HTTP + Basic Auth**（无 TLS）。公开的 Android **2.8.0.4 及更早版本**
按 Manifest 默认禁止明文 HTTP，真机上点「测试连接」会报 **“连不上”**（不是网络问题）。
本包需要 **包含 2026-10-21 局域网明文放行修复的 App 构建（2.8.0.5+）**；2.8.0.4 用户请先升级手机 App。
修复的逐条证据见仓库 `docs/verification-mobile-sync-2026-10-21.md`。

## 校验和

```text
16476db561f37dbac06baeb955f2379cfd94520528625cedeabcdd080b2ad23d  PriceLens-2.6.0-x64-signed-portable.7z.001
15b63344722090b58c4d190c928c905d24a7458e84587807a33c220c6c450095  PriceLens-2.6.0-x64-signed-portable.7z.002
9ed8513a228b5aabde722b3f2c90b7e638c276450423e48e824db445352e691e  PriceLens.exe（解压后）
8295c20913b5d9b03ae0c44e50e0832025d3522228ae0b528179bfb241f310f3  resources/app.asar（解压后）
```

## 安全边界

- 手机联动默认关闭，只接受局域网/回环来源，Basic Auth，请求体 2MB 上限，不经云。
- 仅建议在可信局域网开启；不要在公共 Wi-Fi 使用。
