# PriceLens Desktop 2.6.0（Windows x64，签名便携版）

这是 GitHub Release [`v2.6.0-desktop`](https://github.com/wuliao00/PriceLens/releases/tag/v2.6.0-desktop)
便携包的重压缩版，并做了两项补强：

1. **Authenticode 自签名**（主程序 `PriceLens.exe`）；
2. 一个真机取证发现的热修：手机备份里“平台不支持”的目标（拼多多/当当/识货/值得买）现在会计入“跳过”数，
   不再显示“跳过 0”。

## 为什么是 13 个分卷

Gitee 对公开仓库的 raw 文件有 **>10MB 需登录** 的限制。为了保持“免登录直链”，整个 7z 流按
**9MiB** 切成 13 卷（`.001` … `.013`），合计 122,055,098 B。它们拼起来就是一个完整的 7z 包，
**必须从 `.001` 开始解压**，不要单独解压 `.013`。

## 推荐：一键下载 + 校验 + 解压

下载并运行 [`download-desktop-2.6.0.ps1`](https://gitee.com/wuliao11541/PriceLens/raw/dist/download-desktop-2.6.0.ps1)
（PowerShell 5.1+）。脚本只做三件事：从本目录下载 13 卷 → 逐卷比对 sha256 → 调用本机 7-Zip 解压。
不会自动运行解压出来的程序。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\download-desktop-2.6.0.ps1
```

如果本机没有 7-Zip，脚本会停在“全部校验通过”，你装好 7-Zip 后右键 `.001` 解压即可。

## 手动下载

本目录下 13 个文件：

```text
PriceLens-2.6.0-x64-signed-portable-9m.7z.001
PriceLens-2.6.0-x64-signed-portable-9m.7z.002
...
PriceLens-2.6.0-x64-signed-portable-9m.7z.013
```

全部下载到同一目录 → 7-Zip 右键 `.001` →「提取到当前文件夹」。
校验和见 `SHA256SUMS-desktop-2.6.0-9m.txt`（逐卷）与文末（解压后的主程序）。

## 签名

- 证书 Subject：`CN=PriceLens (Self-Signed), O=PriceLens, C=CN`
- 证书 SHA-256：`B253FA470910B222FF7531669E6454A551ADCD7079CBA7CA623F71EC75F501AF`
- 这是**自签名**证书，不是 CA 签发。Windows 仍可能提示“未知发布者”，SmartScreen 不会因此放行；
  要彻底消除提示需要购买/申请 CA 代码签名证书（本次没有，别把自签名说成已解决）。
- 校验：
  ```powershell
  Get-AuthenticodeSignature .\PriceLens.exe | Format-List Status, SignerCertificate
  ```
  应看到上述 Subject；`Status` 可能是 `UnknownError`/`UntrustedRoot`，根因是证书未装入
  “受信任的根证书颁发机构”。
- 公钥证书：`pricelens-selfsigned.cer`。只有在你信任本发布者时，才把它安装到
  “当前用户 → 受信任的根证书颁发机构”。

## 手机联动的前置条件（重要）

桌面端手机联动使用 **HTTP + Basic Auth**（无 TLS）。公开的 Android **2.8.0.4 及更早版本**
按 Manifest 默认禁止明文 HTTP，真机上点「测试连接」会报 **“连不上”**（不是网络问题）。
本包需要 **包含 2026-10-21 局域网明文放行修复的 App 构建（2.8.0.5+）**；2.8.0.4 用户请先升级手机 App。
修复的逐条证据见仓库 `docs/verification-mobile-sync-2026-10-21.md`。

## 解压后的校验和

```text
9ed8513a228b5aabde722b3f2c90b7e638c276450423e48e824db445352e691e  PriceLens.exe
8295c20913b5d9b03ae0c44e50e0832025d3522228ae0b528179bfb241f310f3  resources/app.asar
1df7fa8241d11b4ab9b793de8d99d142aba41d4ec76d580b341b6317453f6b4a  full-portable.7z（13 卷按序拼接后的整包）
```

## 安全边界

- 手机联动默认关闭，只接受局域网/回环来源，Basic Auth，请求体 2MB 上限，不经云。
- 仅建议在可信局域网开启；不要在公共 Wi-Fi 使用。
