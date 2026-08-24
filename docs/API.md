# PriceLens API 文档

**版本**：v1.0  
**适用版本**：Android v2.3.0+ / Desktop v2.0.0+  
**更新日期**：2026-08-24

---

## 📋 目录

1. [架构概览](#架构概览)
2. [爬虫接口](#爬虫接口)
3. [缓存层 API](#缓存层-api)
4. [IPC 通信](#ipc-通信)
5. [数据模型](#数据模型)
6. [错误码](#错误码)

---

## 🏗️ 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                        UI 层                                 │
│  Android: Compose UI          Desktop: HTML/CSS/JS         │
└─────────────────────────┬───────────────────────────────────┘
                          │
┌─────────────────────────▼───────────────────────────────────┐
│                      业务逻辑层                               │
│  Android: ViewModel + Repository    Desktop: Renderer JS   │
└─────────────────────────┬───────────────────────────────────┘
                          │
┌─────────────────────────▼───────────────────────────────────┐
│                      数据层                                   │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │ 远程数据源   │  │ 本地数据源   │  │  缓存层     │         │
│  │ (爬虫)      │  │ (Room/SQLite)│  │ (TLRU/LRU)  │         │
│  └─────────────┘  └─────────────┘  └─────────────┘         │
└─────────────────────────────────────────────────────────────┘
```

---

## 🕷️ 爬虫接口

### 统一接口定义

```kotlin
// Android: data/remote/Crawler.kt
interface Crawler {
    suspend fun search(keyword: String): Result<List<ProductSummary>>
    suspend fun getDetail(productId: String): Result<ProductDetail>
    suspend fun getPriceHistory(productId: String): Result<List<PricePoint>>
    suspend fun getCoupons(productId: String): Result<List<Coupon>>
    suspend fun getCommunityInfo(productId: String): Result<CommunityInfo>
}
```

```javascript
// Desktop: src/main/crawlers/index.js
class Crawler {
    async search(keyword) { /* ... */ }
    async getDetail(productId) { /* ... */ }
    async getPriceHistory(productId) { /* ... */ }
    async getCoupons(productId) { /* ... */ }
    async getCommunityInfo(productId) { /* ... */ }
}
```

### 支持平台与实现

| 平台 | 爬虫类 | 搜索 | 详情 | 价格历史 | 优惠券 | 社区 |
|------|--------|------|------|----------|--------|------|
| 京东 | `JdCrawler` | ✅ | ✅ | ✅ | ✅ | ❌ |
| 淘宝/天猫 | `TaoBaoCrawler` | ✅ | ✅ | ✅ | ✅ | ❌ |
| 拼多多 | `PddCrawler` | ✅ | ✅ | ✅ | ❌ | ❌ |
| 哔哩哔哩 | `BiliCrawler` | ✅ | ✅ | ❌ | ❌ | ✅ |
| 什么值得买 | `SmzdmCrawler` | ✅ | ✅ | ✅ | ✅ | ✅ |
| 慢慢买 | `ManmanbuyCrawler` | ✅ | ✅ | ✅ | ✅ | ❌ |
| 购物党 | `GwdangCrawler` | ❌ | ✅ | ✅ | ✅ | ❌ |
| 咕咚 | `CodoonCrawler` | ✅ | ✅ | ✅ | ❌ | ❌ |
| Keep | `KeepCrawler` | ✅ | ✅ | ✅ | ❌ | ❌ |

### 请求参数规范

```typescript
// 搜索请求
interface SearchRequest {
    keyword: string;           // 搜索关键词
    page?: number;             // 页码，默认 1
    pageSize?: number;         // 每页数量，默认 20
    sort?: 'price_asc' | 'price_desc' | 'sales' | 'default';
    filters?: Record<string, string>; // 平台特定筛选
}

// 详情请求
interface DetailRequest {
    productId: string;         // 平台商品 ID
    platform: Platform;        // 来源平台枚举
}
```

### 响应数据模型

```typescript
// 商品摘要（搜索结果列表项）
interface ProductSummary {
    productId: string;         // 平台唯一标识
    platform: Platform;        // 来源平台
    title: string;             // 商品标题
    price: number;             // 当前价格（分）
    originalPrice?: number;    // 原价（分）
    imageUrl: string;          // 主图 URL
    shopName: string;          // 店铺名称
    salesVolume?: number;      // 销量
    rating?: number;           // 评分
    url: string;               // 商品页链接
    tags: string[];            // 标签（自营、包邮、秒杀等）
    updatedAt: number;         // 数据更新时间戳
}

// 商品详情
interface ProductDetail extends ProductSummary {
    description: string;       // 商品描述
    specs: Record<string, string>; // 规格参数
    images: string[];          // 所有图片
    skus: Sku[];               // SKU 列表
    priceHistory: PricePoint[]; // 价格历史
    coupons: Coupon[];         // 可用优惠券
    community: CommunityInfo;  // 社区评价
}

// 价格历史点
interface PricePoint {
    timestamp: number;         // 时间戳
    price: number;             // 价格（分）
    type: 'normal' | 'promotion' | 'coupon' | 'flash_sale'; // 价格类型
    promotionName?: string;    // 促销名称
}

// 优惠券
interface Coupon {
    id: string;
    name: string;              // 券名称
    value: number;             // 面值（分）
    threshold: number;         // 使用门槛（分）
    startTime: number;
    endTime: number;
    remainder: number;         // 剩余张数
    conditions: string[];      // 使用条件
}

// 社区信息
interface CommunityInfo {
    platform: 'bilibili' | 'smzdm' | 'other';
    videos: VideoInfo[];       // 相关视频（B站）
    articles: ArticleInfo[];   // 相关文章（值得买）
    tags: string[];            // 关键词标签
    sentiment: 'positive' | 'negative' | 'neutral'; // 整体倾向
}
```

---

## 💾 缓存层 API

### 三级缓存架构

```
L1: 内存缓存 (TLRU / LRU)     → 热数据，毫秒级读取
    ├── Android: TlruCache<T> (8MB 预算)
    └── Desktop: MemoryCache (Map + TTL)

L2: 持久化缓存 (Room / SQLite) → 温数据，重启保留
    ├── Android: Room DAO (10MB 预算)
    └── Desktop: SQLite (better-sqlite3)

L3: 网络层缓存 (OkHttp / HTTP)  → 网络层面缓存
    ├── Android: OkHttp Cache (5MB)
    └── Desktop: 自定义 HTTP 缓存
```

### 缓存键设计

```kotlin
// 统一缓存键格式
// 搜索: "search:{platform}:{keyword}:{page}:{filtersHash}"
// 详情: "detail:{platform}:{productId}"
// 价格历史: "history:{platform}:{productId}"
// 优惠券: "coupons:{platform}:{productId}"
// 社区: "community:{platform}:{productId}"
```

### 缓存策略

| 数据类型 | TTL | 最大条目 | 淘汰策略 |
|---------|-----|----------|----------|
| 搜索结果 | 10 分钟 | 500 | LRU + TTL |
| 商品详情 | 30 分钟 | 1000 | LRU + TTL |
| 价格历史 | 2 小时 | 2000 | LRU + TTL |
| 优惠券 | 1 小时 | 500 | LRU + TTL |
| 社区信息 | 4 小时 | 300 | LRU + TTL |
| 图片资源 | 7 天 | 200MB | Coil/HTTP 缓存 |

### 缓存操作接口

```kotlin
// Android: data/cache/CacheManager.kt
interface CacheManager {
    suspend fun <T> get(key: String): T?
    suspend fun <T> put(key: String, value: T, ttl: Long = DEFAULT_TTL)
    suspend fun invalidate(key: String)
    suspend fun invalidateByPrefix(prefix: String)
    suspend fun clear()
    fun stats(): CacheStats
}
```

```javascript
// Desktop: src/main/cache/manager.js
class CacheManager {
    async get(key) { /* ... */ }
    async put(key, value, ttl) { /* ... */ }
    async invalidate(key) { /* ... */ }
    async invalidateByPrefix(prefix) { /* ... */ }
    async clear() { /* ... */ }
    stats() { /* ... */ }
}
```

---

## 🔌 IPC 通信

仅适用于 Desktop 端（Electron 主进程 ↔ 渲染进程）

### 通道定义

```typescript
// preload.ts 暴露给渲染进程的 API
interface ElectronAPI {
    // 爬虫相关
    search: (platform: string, keyword: string, options?: SearchOptions) => Promise<ApiResponse<ProductSummary[]>>;
    getDetail: (platform: string, productId: string) => Promise<ApiResponse<ProductDetail>>;
    getPriceHistory: (platform: string, productId: string) => Promise<ApiResponse<PricePoint[]>>;
    getCoupons: (platform: string, productId: string) => Promise<ApiResponse<Coupon[]>>;
    getCommunityInfo: (platform: string, productId: string) => Promise<ApiResponse<CommunityInfo>>;
    
    // 缓存管理
    cache: {
        getStats: () => Promise<CacheStats>;
        clear: () => Promise<void>;
        invalidate: (key: string) => Promise<void>;
    };
    
    // 设置
    settings: {
        get: () => Promise<AppSettings>;
        set: (settings: Partial<AppSettings>) => Promise<void>;
    };
    
    // 盯价任务 (规划中)
    watchTasks: {
        list: () => Promise<WatchTask[]>;
        create: (task: WatchTask) => Promise<WatchTask>;
        update: (id: string, task: Partial<WatchTask>) => Promise<void>;
        delete: (id: string) => Promise<void>;
    };
    
    // 系统
    openExternal: (url: string) => Promise<void>;
    showItemInFolder: (path: string) => Promise<void>;
    getAppVersion: () => Promise<string>;
    getPlatform: () => Promise<NodeJS.Platform>;
}
```

### IPC 处理器

```javascript
// src/main/ipc-handlers.js
const handlers = {
    'search': async (event, { platform, keyword, options }) => {
        const crawler = getCrawler(platform);
        return await crawler.search(keyword, options);
    },
    
    'get-detail': async (event, { platform, productId }) => {
        const crawler = getCrawler(platform);
        return await crawler.getDetail(productId);
    },
    
    // ... 其他处理器
};
```

---

## 📦 数据模型

### 实体关系图

```
Product (商品)
├── ProductSummary (搜索摘要)
├── ProductDetail (详情)
│   ├── Sku[] (SKU 列表)
│   ├── PricePoint[] (价格历史)
│   ├── Coupon[] (优惠券)
│   └── CommunityInfo (社区)
└── WatchTask (盯价任务) ← 仅 Android
    ├── productId
    ├── targetPrice
    ├── interval
    └── enabled
```

### Room 实体 (Android)

```kotlin
// data/local/entity/ProductEntity.kt
@Entity(tableName = "products", indices = [
    Index(value = ["platform", "productId"], unique = true),
    Index("updatedAt")
])
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "platform") val platform: String,
    @ColumnInfo(name = "product_id") val productId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "price") val price: Long, // 分
    @ColumnInfo(name = "original_price") val originalPrice: Long?,
    @ColumnInfo(name = "image_url") val imageUrl: String,
    @ColumnInfo(name = "shop_name") val shopName: String,
    @ColumnInfo(name = "sales_volume") val salesVolume: Long?,
    @ColumnInfo(name = "rating") val rating: Double?,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "tags") val tags: String, // JSON
    @ColumnInfo(name = "detail_json") val detailJson: String, // 完整详情 JSON
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
```

### SQLite 表结构

```sql
-- products 表
CREATE TABLE products (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    platform TEXT NOT NULL,
    product_id TEXT NOT NULL,
    title TEXT NOT NULL,
    price INTEGER NOT NULL,           -- 分
    original_price INTEGER,
    image_url TEXT NOT NULL,
    shop_name TEXT NOT NULL,
    sales_volume INTEGER,
    rating REAL,
    url TEXT NOT NULL,
    tags TEXT,                        -- JSON 数组
    detail_json TEXT,                 -- 完整详情 JSON
    updated_at INTEGER NOT NULL,
    UNIQUE(platform, product_id)
);

CREATE INDEX idx_products_updated_at ON products(updated_at);

-- price_history 表
CREATE TABLE price_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    timestamp INTEGER NOT NULL,
    price INTEGER NOT NULL,           -- 分
    type TEXT NOT NULL,               -- normal/promotion/coupon/flash_sale
    promotion_name TEXT
);

CREATE INDEX idx_price_history_product ON price_history(product_id);

-- watch_tasks 表 (仅 Android)
CREATE TABLE watch_tasks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    target_price INTEGER NOT NULL,    -- 分
    interval_minutes INTEGER NOT NULL DEFAULT 30,
    enabled INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER NOT NULL,
    last_check_at INTEGER
);
```

---

## ❌ 错误码

### 统一错误格式

```typescript
interface ApiError {
    code: string;        // 错误码
    message: string;     // 用户友好提示
    detail?: string;     // 技术细节（调试用）
    retryable: boolean;  // 是否可重试
}
```

### 常见错误码

| 错误码 | HTTP 状态 | 说明 | 可重试 | 处理建议 |
|--------|-----------|------|--------|----------|
| `NETWORK_ERROR` | - | 网络不可用/超时 | ✅ | 检查网络，指数退避重试 |
| `RATE_LIMITED` | 429 | 触发反爬限流 | ✅ | 等待 `Retry-After` 秒后重试 |
| `BLOCKED` | 403 | IP/UA 被封禁 | ❌ | 更换 IP/UA，触发熔断 |
| `PARSE_FAILED` | 200 | 页面结构变化解析失败 | ❌ | 更新爬虫选择器，上报 Issue |
| `NOT_FOUND` | 404 | 商品不存在/已下架 | ❌ | 提示用户商品失效 |
| `PLATFORM_UNSUPPORTED` | - | 平台暂不支持 | ❌ | 降级提示 |
| `CACHE_MISS` | - | 缓存未命中 | ✅ | 回源网络请求 |
| `STORAGE_FULL` | - | 本地存储空间不足 | ❌ | 清理缓存提示用户 |
| `PERMISSION_DENIED` | - | 权限不足（无障碍/悬浮窗） | ❌ | 引导用户授权 |

### 熔断机制

```kotlin
// 实现参考：util/RateLimiter.kt / utils/rate-limiter.js
class CircuitBreaker {
    // 状态：CLOSED(正常) → OPEN(熔断) → HALF_OPEN(探测)
    // 触发条件：连续 5 次 403/429/timeout
    // 恢复条件：HALF_OPEN 状态下 3 次成功请求
    // 熔断时长：5 分钟（可配置）
}
```

---

## 🔄 版本兼容性

| API 版本 | Android 最低版本 | Desktop 最低版本 | 变更说明 |
|---------|------------------|------------------|----------|
| v1 | 2.3.0 | 2.0.0 | 初始版本 |

> 遵循语义化版本：Breaking Change 升主版本号，新增功能升次版本号，Bug 修复升修订号。

---

## 📝 更新日志

| 版本 | 日期 | 变更内容 |
|------|------|----------|
| v1.0 | 2026-08-24 | 初始版本发布 |

---

## 🤝 贡献

欢迎完善 API 文档！请参考 [贡献指南](README.md#-贡献指南) 提交 PR。