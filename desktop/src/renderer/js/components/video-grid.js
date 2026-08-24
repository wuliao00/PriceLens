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
 *   duration:string, pic:string, url:string}> | null, error?: string}} ctx
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

  const grid = el('div', { class: 'video-grid' });
  for (const video of videos.slice(0, 6)) {
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
      src: video.pic || '../assets/placeholder.svg',
      alt: '',
      loading: 'lazy',
      on: { error: (e) => { e.target.src = '../assets/placeholder.svg'; } },
    }),
    el('span', { class: 'video-duration', text: video.duration || '--:--' }),
  );
  if (badgeRule) {
    cover.appendChild(el('span', { class: `video-badge ${badgeRule.cls}`, text: badgeRule.label }));
  }

  card.appendChild(cover);
  card.appendChild(el('div', { class: 'video-body' },
    el('div', { class: 'video-title clamp-2', text: video.title }),
    el('div', { class: 'video-meta' },
      el('span', null, icon('user', 12), ` ${video.author || '未知UP主'}`),
      el('span', null, icon('play', 12), ` ${formatCount(video.play)} 播放`),
    ),
  ));
  return card;
}
