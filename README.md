# PriceLens — 极简主义全网比价决策工具

> 种草→盯价→找券→决策 四步闭环

## 启动

```bash
npm install
npm start
```

## 构建

```bash
npm run dist    # NSIS 安装器 + 便携版
npm run pack    # 仅打包目录（调试用）
```

## 项目结构

```
src/
├── main/                # Electron 主进程
│   ├── index.js         # 入口
│   ├── preload.js       # contextBridge 安全桥
│   ├── ipc-handlers.js  # IPC 路由
│   ├── crawlers/        # 爬虫模块
│   │   ├── bilibili.js  # B站API
│   │   ├── smzdm.js     # 什么值得买
│   │   ├── manmanbuy.js # 慢慢买历史价
│   │   ├── gwdang.js    # 购物党优惠券
│   │   └── jd.js        # 京东价格
│   ├── cache/           # 离线缓存
│   └── utils/           # 工具
└── renderer/            # 前端
    ├── index.html       # 主页面
    └── js/
        ├── app.js       # 主控
        └── components/  # UI 组件
```

## 设计哲学

- **清晰** — 系统字体，高对比度，留白为主
- **顺从** — 毛玻璃效果，UI 隐退，内容为主
- **深度** — 微妙阴影，弹性动画，可触摸空间感
- **极简** — 无水装饰，纯功能导向

## 数据源

| 阶段 | 数据源 | 接口 |
|------|--------|------|
| 种草 | B站 | api.bilibili.com |
| 盯价 | 慢慢买 | tool.manmanbuy.com |
| 找券 | 购物党 + 京东联盟 | gwdang.com / api.jd.com |
| 决策 | 什么值得买 | search.smzdm.com |