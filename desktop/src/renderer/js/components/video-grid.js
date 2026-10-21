/**
 * B 站评测视频网格（规范 §6.1）：2 列卡片，
 * 标题含避坑词 → 红色 ⚠️ 角标；含推荐词 → 绿色 👍 角标；
 * 点击经主进程在系统浏览器打开。
 */
import { el, icon } from '../utils/dom.js';
import { formatCount } from '../utils/format.js';
import { openExternalSafe } from '../utils/sanitize.js';
import { showContextMenu } from './context-menu.js';
import { showToast } from './toast.js';
import { copyText } from '../utils/dom.js';

/** 角标关键词映射 */
const BADGE_RULES = [
  { re: /翻车|避坑|缺点|退货|踩雷|智商税/, cls: 'video-badge--warn', label: '⚠️ 避坑' },
  { re: /推荐|必买|真香/, cls: 'video-badge--rec', label: '👍 推荐' },
];

/**
 * 渲染视频视图。
 * @param {HTMLElement} container
 * @param {{videos: Array<{title:string, author:string, play:number,
 *   duration:string, pic:string, url:string, risk?:object}> | null, error?: string}} ctx
 */
export function renderVideoView(container, { videos, error }) {
  if (error) {
    container.appendChild(el('div', { class: 'card empty-state' },
      icon('tv', 48),
      el('div', { class: 'e-title', text: 'B 站数据源暂不可用' }),
      el('div', { class: 'e-desc', text: error }),
      el('button', { class: 'btn', on: { click: () => window.location.reload() } }, '稍后重试')));
    return;
  }
  if (!videos || videos.length === 0) {
    container.appendChild(el('div', { class: 'card empty-state' },
      icon('tv', 48),
      el('div', { class: 'e-title', text: '暂无评测视频' }),
      el('div', { class: 'e-desc', text: '搜索商品后，这里会聚合 B 站最新评测视频。' })));
    return;
  }

  /* 被标记（疑似商单/夸大宣传）的视频沉底，其余保持原相关度顺序。
   * Array.prototype.sort 在 V8 里稳定，等价 Android sortedBy 的稳定语义。 */
  const sorted = [...videos].sort((a, b) => (a.risk?.flagged ? 1 : 0) - (b.risk?.flagged ? 1 : 0));
  const flaggedCount = videos.filter((v) => v.risk?.flagged).length;
  if (flaggedCount > 0) {
    container.appendChild(el('div', { class: 'risk-hint' },
      icon('warn', 14),
      el('span', { text: `${flaggedCount} 条视频被标记为疑似商单/含夸大宣传话术，已沉底展示（规则识别仅供参考）` })));
  }

  const grid = el('div', { class: 'video-grid' });
  // 接口最多返回 12 条；不截成 6 条——否则「被标记视频沉底」后可能一条都看不到，
  // 顶部却写着「已沉底展示」。全部渲染，由页面滚动承载（与 Android 列表同语义）。
  for (const video of sorted) {
    grid.appendChild(buildVideoCard(video));
  }
  container.appendChild(grid);
}

/**
 * 单个视频卡片（右键：浏览器打开 / 复制标题）。
 * @param {object} video
 */
function buildVideoCard(video) {
  const badgeRule = BADGE_RULES.find((r) => r.re.test(video.title));

  const card = el('div', {
    class: 'card card--hover video-card',
    on: {
      click: () => openExternalSafe(video.url),
      contextmenu: (e) => {
        e.preventDefault(); // 显式传参（红线 #5）
        showContextMenu(e.clientX, e.clientY, [
          {
            label: '在浏览器打开',
            icon: 'external',
            action: () => openExternalSafe(video.url),
          },
          {
            label: '复制标题',
            icon: 'copy',
            action: async () => {
              const ok = await copyText(video.title);
              showToast(ok ? '已复制' : '复制失败');
            },
          },
        ]);
      },
    },
  });

  const cover = el('div', { class: 'video-cover' },
    el('img', {
      src: video.pic || 'assets/placeholder.svg',
      alt: '',
      loading: 'lazy',
      on: { error: (e) => { e.target.src = 'assets/placeholder.svg'; } },
    }),
    el('span', { class: 'video-duration', text: video.duration || '--:--' }),
  );
  if (badgeRule) {
    cover.appendChild(el('span', { class: `video-badge ${badgeRule.cls}`, text: badgeRule.label }));
  }

  const body = el('div', { class: 'video-body' },
    el('div', { class: 'video-title clamp-2', text: video.title }),
    el('div', { class: 'video-meta' },
      el('span', null, icon('user', 12), ` ${video.author || '未知UP主'}`),
      el('span', null, icon('play', 12), ` ${formatCount(video.play)} 播放`),
    ),
  );

  const chips = buildRiskChips(video.risk);
  if (chips) body.appendChild(chips);

  card.appendChild(cover);
  card.appendChild(body);
  return card;
}

/**
 * 内容风险标签：疑似商单（红）/ 夸大宣传（橙），命中词放 title 里供鼠标查看。
 * 只标记不删除，与 Android BiliVideoCard.RiskChip 同语义。
 * @param {{sponsored:boolean, hype:boolean, sponsorWord?:string|null,
 *   hypeWord?:string|null}|undefined} risk
 * @returns {HTMLElement|null}
 */
function buildRiskChips(risk) {
  if (!risk || (!risk.sponsored && !risk.hype)) return null;
  const wrap = el('div', { class: 'video-chips' });
  if (risk.sponsored) {
    wrap.appendChild(el('span', {
      class: 'risk-chip risk-chip--sponsor',
      text: '疑似商单',
      title: risk.sponsorWord ? `命中：${risk.sponsorWord}` : '',
    }));
  }
  if (risk.hype) {
    wrap.appendChild(el('span', {
      class: 'risk-chip risk-chip--hype',
      text: '夸大宣传',
      title: risk.hypeWord ? `命中：${risk.hypeWord}` : '',
    }));
  }
  return wrap;
}
