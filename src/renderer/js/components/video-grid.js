/**
 * Video Grid — B站视频卡片组件
 *
 * Props: videos: [{ title, author, pic, play, duration, url, isWarning }]
 *
 * 事件委托: 使用 data-url 属性代替 onclick
 */

function renderVideoGrid(containerId, videos) {
  const container = document.getElementById(containerId);
  if (!container) return;

  if (!videos || videos.length === 0) {
    container.innerHTML = '<div style="text-align:center;padding:40px;color:var(--text-tertiary);">暂无B站评测视频</div>';
    return;
  }

  container.innerHTML = videos.map(v => `
    <div class="video-card" data-video-url="${escHtml(v.url)}">
      <div class="video-thumb">
        <div class="video-thumb-placeholder">▶</div>
        ${v.isWarning ? '<div class="video-badge-warning">⚠ 翻车</div>' : ''}
        ${v.duration ? `<div class="video-duration">${escHtml(v.duration)}</div>` : ''}
      </div>
      <div class="video-info">
        <div class="video-title">${escHtml(v.title)}</div>
        <div class="video-meta">${escHtml(v.author)} · ${formatNum(v.play)}播放</div>
      </div>
    </div>
  `).join('');
}

// ─── 全局事件委托：视频卡片点击 ───
document.addEventListener('click', function (e) {
  const card = e.target.closest('.video-card');
  if (!card) return;
  const url = card.dataset.videoUrl;
  if (url) openExternal(url);
});

function openExternal(url) {
  if (window.priceLens?.openExternal) {
    window.priceLens.openExternal(url);
  }
}
