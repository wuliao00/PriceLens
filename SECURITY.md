# 安全策略

## 支持的版本

目前仅最新稳定版本提供安全更新：

| 版本 | 支持状态 |
|------|----------|
| 2.3.x (最新) | ✅ 支持 |
| < 2.3.0 | ❌ 不再支持 |

请及时升级到最新版本以获得安全修复。

## 报告漏洞

**请勿在公开 Issue 中披露安全漏洞细节。**

如果你发现安全漏洞，请通过以下方式**私密报告**：

1. **GitHub Security Advisories** (推荐)：
   - 访问：https://github.com/wuliao00/PriceLens/security/advisories/new
   - 选择 "Report a vulnerability"
   - 填写详细信息并提交

2. **邮件联系**：
   - security@pricelens.example.com (请替换为真实安全邮箱)
   - 主题：`[Security] PriceLens - <简短描述>`

### 报告应包含的信息

- 漏洞类型（如：XSS、SQL注入、权限绕过、数据泄露、RCE 等）
- 影响版本
- 复现步骤（尽可能详细）
- 影响范围与严重程度评估
- 你的联系方式（便于后续沟通）

## 响应承诺

| 阶段 | 响应时间 | 说明 |
|------|----------|------|
| 确认收到 | ≤ 48 小时 | 确认收到报告，分配跟进人 |
| 初步评估 | ≤ 7 天 | 确认漏洞有效性、评估 CVSS 评分 |
| 修复开发 | ≤ 30 天 | 视严重程度，高危漏洞优先修复 |
| 发布补丁 | 修复完成后 ≤ 7 天 | 发布新版本，更新 Release Notes |
| 公开披露 | 补丁发布后 14-30 天 | 给用户足够升级时间，随后公开详情 |

## 安全最佳实践 (用户端)

为保障你的数据安全，建议：

1. **仅从官方渠道下载**：
   - GitHub Releases：https://github.com/wuliao00/PriceLens/releases
   - 蓝奏云/夸克网盘：README.md 提供的官方分享链接
   - **拒绝任何第三方重打包/修改版**

2. **验证文件完整性**：
   ```bash
   # Windows PowerShell
   Get-FileHash -Algorithm SHA256 PriceLens-2.0.0-win.zip
   
   # Linux/macOS
   sha256sum PriceLens-2.0.0-win.zip
   ```
   对比 Release 页面提供的 SHA256 值。

3. **保持应用更新**：
   - 关注 GitHub Releases 或开启 Watch
   - 及时升级到最新版本

4. **权限最小化**：
   - 仅在需要时开启无障碍服务、悬浮窗、通知
   - 不使用盯价功能时可关闭后台任务

## 威胁模型与防护措施

### 数据流威胁模型

```
用户设备 (本地优先)
    │
    ├── 本地存储 (Room/SQLite) → AES-256 加密可选 / 系统沙箱隔离
    ├── 内存缓存 (TLRU/LRU)    → 进程内存，随进程销毁
    └── 网络请求 (仅出站 HTTPS)
            │
            ├── 电商公开页面 (GET only)
            │   └── 强制 TLS 1.2+、证书锁定、UA 轮换
            │
            └── 无任何用户标识上传
                ├── 无 Account/UID
                ├── 无设备指纹
                └── 无埋点/统计 SDK
```

### 代码层面防护

| 风险 | 防护措施 |
|------|----------|
| 中间人攻击 (MITM) | OkHttp/undici 强制证书校验，不信任用户安装的 CA |
| 反序列化攻击 | 仅解析已知 JSON 结构，拒绝任意类反序列化 (Gson/Moshi 安全配置) |
| 代码注入 | 无 `eval()`/`Function()`，无动态代码加载，WebView 禁用 JS 接口 |
| 权限提升 | 无导出组件，无障碍服务仅读 UI 树，不注入事件 |
| 供应链攻击 | Dependabot 自动更新、依赖漏洞扫描 (OWASP Dependency Check)、锁文件完整性 |
| 信息泄露 | 日志脱敏 (不记录 URL 参数/响应体)、Release 混淆、无调试端口 |

### 依赖安全

- **Gradle (Android)**：`dependencyCheck` 任务集成 OWASP Dependency Check，CI 每周扫描
- **npm (Desktop)**：`npm audit` CI 集成，`package-lock.json` 锁定版本
- **最小依赖原则**：Android 无第三方统计/广告/推送 SDK；Desktop 仅核心运行时依赖

## 漏洞分级参考 (CVSS 3.1)

| 严重度 | CVSS 分数 | 响应 SLA | 示例 |
|--------|-----------|----------|------|
| 严重 | 9.0-10.0 | ≤ 72 小时发布补丁 | RCE、SQLi、认证绕过导致数据泄露 |
| 高危 | 7.0-8.9 | ≤ 7 天发布补丁 | XSS、权限提升、敏感信息泄露 |
| 中危 | 4.0-6.9 | ≤ 30 天发布补丁 | CSRF、信息泄露、DoS |
| 低危 | 0.1-3.9 | 下个常规版本修复 | 轻微信息泄露、配置不当 |

## 致谢

感谢以下安全研究员负责任地披露漏洞：

- (暂无，欢迎第一位贡献者)

> 我们承诺：对负责任披露的有效漏洞，将在修复版本发布后的 Release Notes 中公开致谢（除非报告者要求匿名）。

---

## 联系方式

- **安全邮箱**：security@pricelens.example.com (请替换)
- **GPG 公钥**：(如需加密通信，请提供)
- **GitHub Security**：https://github.com/wuliao00/PriceLens/security

---

**PriceLens 致力于构建安全、透明、可信赖的开源比价工具。**