package com.pricelens.accessibility

/**
 * 无障碍层测试夹具（A2）。
 *
 * 全部快照为**手工按结构构造**：层级与节点类型参照 2026-09 真机
 * `uiautomator dump` 得到的京东/淘宝/拼多多商详页与列表页 XML
 * （root → RecyclerView/LinearLayout 容器 → TextView 叶子；按钮多为
 * android.widget.Button 或带 contentDescription 的可点击容器），
 * 文本/ID 为真实 dump 的脱敏改写。AccessibilityNodeInfo 在 JVM 单测不可构造
 * （无 mockito/Robolectric），因此被测对象全部是 NodeSnapshot.kt 的纯函数。
 *
 * 局限与本文件的补集：以上快照**只能证明"规则按构造时的想象不误判"**，证明不了
 * "真实的京东首页不会触发规则"。真机实采的两棵京东树（首页 + 搜索页，2026-09-29
 * 从 vivo V2156A dump）走的是另一条链路：`app/src/test/resources/fixtures/jd_*_20260929.xml`
 * → [UiAutomatorDump]（含生产侧 4000 节点/64 深度预算复刻）→ [RealDumpGatingTest]。
 * 真机上已验证：手工夹具 jdHomePage() 不带"加入购物车"信号，而**真实首页带**，
 * 首页不弹窗靠的是"商详分区标记/立购动作"这一关 —— 这类差异只有真机能暴露。
 */

/**
 * 合成夹具的默认位置。
 *
 * 为什么默认值不能再是 null：`extractTitle` 三级启发式从 2026-10-06 起把
 * `bounds == null` 当作**有含义的信号**（未布局/已被回收的节点，不许当商品名候选，
 * 真机取证见 [ServiceViewTitleTest]）。手造的树若不写位置，就等于给每个节点都标了
 * "未布局"，于是一整批老用例集体读不出标题 —— 那不是判据对，是夹具不真实。
 * 真机上布局好的节点一定有边界框，所以默认给一个非空矩形；
 * **刻意**要测"没有位置"的用例自己显式传 `bounds = null`。
 */
internal val LAID_OUT = NodeBounds(0, 0, 1080, 2408)

/** 文本叶子节点（TextView 形态） */
internal fun leaf(
    text: String? = null,
    desc: String? = null,
    res: String? = null,
    clickable: Boolean = false,
    cls: String = "android.widget.TextView",
    bounds: NodeBounds? = LAID_OUT
): NodeSnapshot = NodeSnapshot(text, desc, cls, res, clickable, emptyList(), bounds)

/** 屏幕坐标快捷构造（几何判据的用例都走它，免得满屏写四元组） */
internal fun rect(left: Int, top: Int, right: Int, bottom: Int): NodeBounds = NodeBounds(left, top, right, bottom)

/** 容器节点（View/LinearLayout 形态，自身无文本） */
internal fun container(
    res: String? = null,
    clickable: Boolean = false,
    cls: String = "android.view.ViewGroup",
    bounds: NodeBounds? = LAID_OUT,
    vararg kids: NodeSnapshot
): NodeSnapshot = NodeSnapshot(null, null, cls, res, clickable, kids.toList(), bounds)

/**
 * 京东商详页（结构参照 uiautomator dump 脱敏改写）：
 * 顶栏搜索、价格区（主价 + 12期免息 + 满减券文案）、可点击标题容器（主标题挂在上面）、
 * 规格行、促销长句、推荐位（别的商品名）、商品详情分区标记、底栏购买按钮。
 */
internal fun jdDetailPage(): NodeSnapshot = container(
    kids = arrayOf(
        container("com.jingdong.app.mall:id/top_bar", kids = arrayOf(leaf(text = "搜索"))),
        container(
            "com.jingdong.app.mall:id/price_area",
            kids = arrayOf(
                leaf(text = "¥5,499", res = "com.jingdong.app.mall:id/jd_price"),
                leaf(text = "12期免息"),
                leaf(text = "满4999减300")
            )
        ),
        container(
            "com.jingdong.app.mall:id/goods_title",
            clickable = true,
            kids = arrayOf(leaf(text = "HUAWEI Mate 80 12GB+256GB 曜石黑 鸿蒙AI 第二代红枫影像"))
        ),
        leaf(text = "屏幕尺寸：6.8英寸"),
        leaf(text = "国家补贴 限时直降 晒单返50元红包"),
        container(
            "com.jingdong.app.mall:id/recommend_slot",
            kids = arrayOf(leaf(text = "Apple iPhone 17 Pro Max 256GB 白色钛金属官方旗舰店热销爆款"))
        ),
        leaf(text = "商品详情"),
        leaf(desc = "立即购买", res = "com.jingdong.app.mall:id/buy_now", clickable = true, cls = "android.widget.Button")
    )
)

/** 淘宝商详页（标题节点 text 为空、真标题在 contentDescription——旧实现从不读 cd 的经典案例） */
internal fun taobaoDetailPage(): NodeSnapshot = container(
    kids = arrayOf(
        leaf(text = "¥229.9", res = "com.taobao.taobao:id/detail_price"),
        container("com.taobao.taobao:id/tv_title", clickable = true, kids = arrayOf(leaf(desc = "优衣库男装合作款圆领T恤/短袖纯棉上衣夏季新款434489"))),
        leaf(text = "7天无理由退换"),
        leaf(text = "宝贝详情"),
        leaf(desc = "加入购物车", clickable = true)
    )
)

/** 拼多多商详页（无已知 ID，纯文本层级；底栏"单独购买+发起拼单"成对按钮） */
internal fun pddDetailPage(): NodeSnapshot = container(
    kids = arrayOf(
        leaf(text = "限时秒杀 前100名半价 再返大额红包"),
        leaf(text = "小米13 徕卡专业光学镜头手机 12GB+512GB 黑色 5G全网通", res = "com.xunmeng.pinduoduo:id/goods_title"),
        leaf(text = "运行内存：8GB"),
        leaf(text = "¥1,299", res = "com.xunmeng.pinduoduo:id/sku_price"),
        leaf(text = "商品详情"),
        leaf(text = "单独购买", clickable = true),
        leaf(text = "发起拼单", clickable = true)
    )
)

/** 京东搜索结果列表页：多张卡片（各自标题+价格+购物车图标），绝不能被当成商详 */
internal fun jdSearchListPage(): NodeSnapshot = container(
    kids = arrayOf(
        container("com.jingdong.app.mall:id/search_bar", kids = arrayOf(leaf(text = "搜索"))),
        container(
            "com.jingdong.app.mall:id/card_1",
            clickable = true,
            kids = arrayOf(
                leaf(text = "小米 13 12GB+256GB 黑色 徕卡专业光学长焦 5G手机", res = "com.jingdong.app.mall:id/goods_title"),
                leaf(text = "¥1,299", res = "com.jingdong.app.mall:id/jd_price"),
                leaf(desc = "加入购物车", clickable = true)
            )
        ),
        container(
            "com.jingdong.app.mall:id/card_2",
            clickable = true,
            kids = arrayOf(
                leaf(text = "Redmi K80 Pro 16GB+1024GB 晴雪白 游戏拍照旗舰手机 小米澎湃OS", res = "com.jingdong.app.mall:id/goods_title"),
                leaf(text = "¥2,999", res = "com.jingdong.app.mall:id/jd_price"),
                leaf(desc = "加入购物车", clickable = true)
            )
        )
    )
)

/** 京东购物车页（含"去结算/合计"特征，每张卡片也有加购控件） */
internal fun jdCartPage(): NodeSnapshot = container(
    kids = arrayOf(
        leaf(text = "全选"),
        container(
            clickable = true,
            kids = arrayOf(
                leaf(text = "Apple iPhone 15 5G手机 白色钛金属 128GB", res = "com.jingdong.app.mall:id/goods_title"),
                leaf(text = "¥5,999", res = "com.jingdong.app.mall:id/jd_price")
            )
        ),
        leaf(text = "合计:¥5,999"),
        leaf(text = "去结算", clickable = true)
    )
)

/** 京东首页（推荐流，无购买动作词） */
internal fun jdHomePage(): NodeSnapshot = container(
    kids = arrayOf(
        leaf(text = "搜索"),
        leaf(text = "百亿补贴 国家补贴至高减500元 立即抢购"),
        leaf(text = "¥9.9")
    )
)

/** 无已知 ID 的商详（旧版 PDD 页型）：验证三级启发式回退 */
internal fun pageWithOnlyRawTexts(): NodeSnapshot = container(
    kids = arrayOf(
        leaf(text = "限时补贴 前100名免息 晒单返红包"),
        leaf(text = "Redmi Note 13 Pro 5G 小米手机 第二代骁龙7s 1亿像素 12GB+512GB 子夜黑"),
        leaf(text = "售后服务：7天无理由 180天只换不修"),
        leaf(text = "¥1,099.9"),
        leaf(text = "12")
    )
)
