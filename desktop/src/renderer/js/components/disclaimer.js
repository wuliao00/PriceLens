/**
 * 免责协议（免费声明）
 * --------------------
 *   - 首次启动展示一次：同意后写入 localStorage（键名与 Android SharedPreferences
 *     的 disclaimer_agreed 语义一致），之后启动不再弹出
 *   - 正文放在可滚动容器内；「同意」按钮 30 秒倒计时期间禁用并显示剩余秒数
 *   - 倒计时期间用户只能阅读和滚动（遮罩不可点关）
 *   - setInterval 在同意 / 归零时可靠清除，无泄漏
 */
import { el } from '../utils/dom.js';

const AGREED_KEY = 'disclaimer_agreed';
const COUNTDOWN_SECONDS = 30;

/* 文案与 Android res/values/strings.xml 的 disclaimer_title / disclaimer_body / disclaimer_ok 保持一致 */
const TITLE = '免费声明 · 作者：莫';
const AGREE_TEXT = '我知道了，是免费的';
const PARAGRAPHS = [
  '作者：莫',
  '本软件（PriceLens 比价助手）永久免费开源，不收取任何费用，没有付费版、没有会员、没有内购。',
  '⚠ 任何人以任何形式向您收费（付费下载、付费解锁、 VIP 会员、代装服务等），都是骗子，请勿上当！',
  '您的数据仅保存在手机本地，绝不上传。',
];

/** 首次启动展示免责协议；已同意则直接返回 */
export function initDisclaimer() {
  try {
    if (localStorage.getItem(AGREED_KEY) === '1') return; // 已同意，不再显示
  } catch {
    return;
  }

  let remain = COUNTDOWN_SECONDS;
  let timer = null;

  const agreeBtn = el('button', {
    class: 'btn btn--primary',
    disabled: true,
  }, `${AGREE_TEXT}（${remain}）`);

  const modal = el('div', { class: 'modal' },
    el('h3', { class: 'modal-title', text: TITLE }),
    el('div', { class: 'disclaimer-scroll' },
      PARAGRAPHS.map((p) => el('p', { class: 'disclaimer-para', text: p }))),
    el('div', { class: 'disclaimer-actions' }, agreeBtn));

  /* 遮罩不响应点击关闭：倒计时期间只能阅读和滚动 */
  const mask = el('div', { class: 'modal-mask' }, modal);

  function stopTimer() {
    if (timer !== null) {
      clearInterval(timer);
      timer = null;
    }
  }

  function agree() {
    stopTimer();
    try {
      localStorage.setItem(AGREED_KEY, '1');
    } catch { /* 存储不可用时忽略，下次启动重新确认 */ }
    mask.remove();
  }

  agreeBtn.addEventListener('click', agree);

  timer = setInterval(() => {
    remain -= 1;
    if (remain <= 0) {
      stopTimer();
      agreeBtn.disabled = false;
      agreeBtn.textContent = AGREE_TEXT;
    } else {
      agreeBtn.textContent = `${AGREE_TEXT}（${remain}）`;
    }
  }, 1000);

  document.getElementById('modal-root').appendChild(mask);
}
