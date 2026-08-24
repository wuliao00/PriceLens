/**
 * Comment Feed — 什么值得买评论区组件 (v2.0)
 *
 * Props: comments: [{ user, text, time, likes, hasWarning }]
 *
 * 时间字段可以是:
 *   - ISO 字符串 (如 "2024-08-02T10:30:00Z") → 用 timeAgo() 计算
 *   - 相对时间字符串 (如 "3小时前") → 直接显示
 *   - Unix 时间戳 (ms) → 用 timeAgo() 计算
 */

function renderCommentFeed(containerId, comments) {
  const container = document.getElementById(containerId);
  if (!container) return;

  if (!comments || comments.length === 0) {
    container.innerHTML = '<div style="text-align:center;padding:40px;color:var(--text-tertiary);">暂无值友评论<br><span style="font-size:11px;">请先搜索商品获取社区反馈</span></div>';
    return;
  }

  container.innerHTML = comments.map(c => `
    <div class="comment-item">
      <div class="comment-header">
        <span class="comment-user">${escHtml(c.user || '匿名值友')}</span>
        <span class="comment-time">${formatTimeDisplay(c.time)}</span>
      </div>
      <div class="comment-text">${highlightKeywords(escHtml(c.text || ''))}</div>
      <div class="comment-meta">
        👍 ${c.likes || 0} ${c.hasWarning ? ' · <span style="color:var(--warning)">⚠ 包含预警信息</span>' : ''}
      </div>
    </div>
  `).join('');
}

/**
 * 智能时间格式化
 */
function formatTimeDisplay(ts) {
  if (!ts) return '';
  // 已经是中文相对时间（如 "3小时前"、"昨天"）
  if (/[\u4e00-\u9fff]/.test(String(ts))) return String(ts);
  // 数字时间戳
  return timeAgo(ts);
}
