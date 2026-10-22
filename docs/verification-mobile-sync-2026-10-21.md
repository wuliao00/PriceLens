# 真机取证：手机 App 点按「备份 / 恢复」↔ 桌面端 2.6.0 局域网 WebDAV

- 日期：2026-10-21
- 设备：vivo V2156A（PD2156）/ Android 11 / serial `35152127910030J`
- 手机包：`com.pricelens.dev`，debug 签名 `f86c8a85…`，本机 `assembleDebug`，版本 2.8.0.4/25（含本文修复）
- 桌面：PriceLens 2.6.0 便携版（GitHub `v2.6.0-desktop` 资产，`--user-data-dir=E:\pl_e2e_data`）
- 链路：USB + `adb reverse tcp:47821 tcp:47821`，手机端 WebDAV 地址 `http://127.0.0.1:47821/`
- 桌面 Basic Auth：用户 `pricelens`，口令为本次测试临时口令（不随仓）
- 基线：手机 1 个收藏（`jd:100098765432`）+ 2 个盯价（`jd:100098765432`、`pdd:123456789`）；
  桌面 2 个盯价（`jd:100012043978`、`taobao:654321098765`）
- 原始证据：`E:\项目\_pl260\evidence\`（截图、UI 树、桌面日志、库快照；不入库）

## 1. 修复前：点「测试连接」直接连不上

`01-before-fix.png` 的界面读数是**“连不上：地址不通或超时，检查地址或网络”**，但桌面端
`curl -u … -X PROPFIND http://127.0.0.1:47821/pricelens/` 返回 **207**，`adb reverse --list` 也在。

根因不是网络，是平台策略：

- `app/src/main/AndroidManifest.xml` 原先明确写着“维持默认禁止明文流量，不启用 usesCleartextTraffic”。
- targetSdk 35 ⇒ Android 9+ 默认禁止明文 HTTP；OkHttp 在发请求前抛
  `UnknownServiceException: CLEARTEXT communication to 127.0.0.1 not permitted by network security policy`。
- `WebDavClient.testConnection()` 把它归入 `catch (IOException) → WebDavProbe.Unreachable`，
  于是界面只剩“连不上”，把“平台策略拒绝”伪装成了“网络不通”。

桌面端手机联动只有 **HTTP + Basic Auth** 一条路（没有 TLS）⇒ **2.8.0.4 及更早的 App 在真机上用不了这个功能**。
这是本次真机取证抓到的**发布阻断缺陷**，Node 模拟“按 Android 客户端实现验证”覆盖不到它。

## 2. 修复（2.8.0.5）

- `AndroidManifest.xml` 打开 `android:usesCleartextTraffic="true"`，注释写明只为局域网 WebDAV。
- 新增 `PrivateHosts` + `WebDavClient` 两道守卫：
  - 地址层：`http://` 只许 `localhost` / `127.*` / `10.*` / `192.168.*` / `172.16–31.*` /
    `169.254.*` / IPv6 回环、链路本地、ULA / `.local`；公网明文直接拒绝；
  - 网络层：OkHttp `addNetworkInterceptor` 对每个实际请求（含 30x 跳转）再查一次，
    防止私网服务器把请求跳到公网明文。
- `PrivateHostsTest`：4 条（回环/局域网放行、公网拒绝、构造期拒绝公网明文）。
- `:app:testDebugUnitTest --tests 'com.pricelens.data.backup.*'`：**通过**（本机构建日志
  `E:\项目\_pl260\android-build5.log`；AIDL 在中文路径下会因 dep 文件编码失败，构建在 ASCII 工作树完成）。

## 3. 修复后：端到端三个动作

| 动作 | 界面读数 | 证据 |
|---|---|---|
| 测试连接 | **连接成功：WebDAV 可用，认证通过** | `02-test-ok.png` |
| 立即备份 | **已备份：pricelens-20261021-150736.json（远端保留最新 10 份）** | `03-backup-ok.png`、`23-phone-backup-4targets.json` |
| 恢复 | **恢复 0 个收藏 / 3 个盯价目标（冲突按新者胜，较旧的保留本机）** | `04-restore-picker.png`、`05-restore-ok.png` |

桌面端合并读数（`22-desktop-crawl-skipfix.log`）：

```
[mobile-sync] 已接收 pricelens-20261021-150736.json：收到 4 个目标（可用 3），
新增 0 / 更新 3 / 跳过 1（平台不支持 1 / 超出上限 0）
```

手机库（`18-phone-db-after`，db + `-wal` + `-shm` 三件套一起读）：

```
jd:100012043978      jd      888.0  active=1   ← 桌面下发
jd:100098765432      jd      500.0  active=1   ← 手机原有
pdd:123456789        pdd      99.0  active=1   ← 桌面不支持，保留本机
taobao:654321098765  taobao   66.0  active=1   ← 桌面下发
```

⇒ 手机中的桌面目标确实落库；拼多多目标被桌面端丢弃，但手机本机保留，符合“只同步京东/淘宝”。

## 4. 第二个发现：桌面端 skipped 计数被覆盖

第一次备份（手机 payload 有 2 个目标：京东 + 拼多多）时，桌面日志却写
“1 个目标，新增 1 / 更新 0 / 跳过 0”：

- `imported` 是**可用**目标数（1），不是收到数（2）；
- `mergeWatches` 返回的 `skipped` 只统计“超过 20 个上限”，`lastImport` 里
  `...merged` 又把 `imported.skipped`（平台不支持 1）覆盖成 0。

修复：`androidBackupToWatches` 增加 `received`；`lastImport` 显式区分
`skippedUnsupported` / `skippedInvalid`，`skipped` 取两者之和；日志与设置页文案改为
“收到 N 个目标（可用 M）… 跳过 S”。`desktop/_unit_check.js` 增加 `received=4` 断言，
**78 项全通过**。打包版 `app.asar` 已同步热修（README/证据里注明是 asar 热修，仓库源码为同款改动）。

## 5. 边界（如实）

- 本次 UI 取证先在 **debug 签名 `com.pricelens.dev`** 上跑通；随后又用 **CI 正式签名包**
  `PriceLens-2.8.0.5.apk`（`com.pricelens`，cert `c759cbb8…`）在真机复验了测试连接 / 恢复 / 备份，
  见 §7。仍有一条没做：从 ≤2.6.5 的旧签名包覆盖安装（签名不同，必须卸载重装，属已知限制）。
- 手机上的数据是为取证播种的（1 个收藏 + 2 个盯价）；拼多多目标刻意保留，用来验证“不支持平台不伪造”。
- 桌面 2.6.0 便携版仍为自签名（`CN=PriceLens (Self-Signed)`，`B253FA…F501AF`）；Windows 仍会提示
  “未知发布者”，这是没有 CA 代码签名证书的硬限制，不是漏签。
- 桌面端 WebDAV 无 TLS、Basic Auth，只建议在可信局域网使用；默认关闭、只接受私网/回环。
- 本机 hosts/Steam 加速器把 `localhost` 的 canonical name 指到 `steamcdn-a.akamaihd.net`，
  第一次跑 `WebDavClientTest` 时被新的明文守卫正确拦下（8 条测试假红）。测试改用
  `http://127.0.0.1:${server.port}/dav/` 字面量；这是**测试夹具**修正，不改生产行为。

## 6. Gitee 发布包复核（桌面端）

- Gitee `dist` 分支：`PriceLens-2.6.0-x64-signed-portable-9m.7z.001…013`（13 × 9MiB，绕开
  Gitee raw >10MB 需登录的限制）+ `pricelens-selfsigned.cer` + `SHA256SUMS-desktop-2.6.0-9m.txt`
  + `README-desktop-2.6.0.md` + `download-desktop-2.6.0.ps1`。
- 匿名直链抽检：`.001/.007/.013`、README、PS1、证书均 **HTTP 200**，字节数正确。
- 在**空目录**里从 Gitee 拉取发布出来的 PS1 并运行：13 卷全部下载、逐卷 sha256 通过、
  7-Zip 解出 75 个文件（404,816,308 B）；解压后的 `PriceLens.exe` sha256
  `9ed8513a…e691e`、`resources/app.asar` `8295c209…10f3` 与本地签名包一致；
  `Get-AuthenticodeSignature` 显示自签证书 Subject 正确（Status=UnknownError，未装根证书）。
- 发布包的主程序是**自签名**，不是 CA 签名；Windows 仍可能提示未知发布者。README 已写明。

## 7. 2.8.0.5 正式签名包真机复验（2026-10-22）

- 包：`PriceLens-2.8.0.5.apk`，**3,325,823 B**，sha256
  `0481d3987f99a354385442fa3522b06d605ba6cd3ac27bd63c67324c2c1db843`，
  证书 SHA-256 `c759cbb8ca2bda75799d75336d615390ec5737d83cb588703bef09821205b5e0`（同 2.8.0.4）。
- 安装：设备上 `com.pricelens` 原为 2.8.0.3/24（在分身空间 user 10）；`pm install -r` 升到 2.8.0.5/26。
  为不碰分身空间数据，用 `pm install-existing --user 0` 在主用户启用一份干净实例做 UI 取证，测试完
  `pm uninstall --user 0` 清掉；user 10 的 2.8.0.5 与数据保留。
- 读数：版本页 **v2.8.0.5**；测试连接 **“连接成功：WebDAV 可用，认证通过”**；
  恢复 **“0 个收藏 / 3 个盯价目标”**；立即备份 **“已备份：pricelens-20261022-094707.json”**，
  桌面日志 **“收到 3 个目标（可用 3），新增 0 / 更新 3 / 跳过 0”**。
- Gitee raw 回读：HTTP 200，3,325,823 B，sha256 与 CI 产物一致；`update.json` 已回填 26 / 2.8.0.5。
- 截图：`assets/screenshots/mobile-sync-2026-10-21/07-release-version.png` … `10-release-backup-ok.png`。
