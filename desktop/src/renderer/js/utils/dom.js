/**
 * DOM 工具：声明式 createElement、SVG 构建、内联图标、剪贴板、数字滚动。
 *
 * 红线 #4：本文件是"用 DOM API 而非 innerHTML"渲染外部数据的基础设施。
 * icon() 使用 innerHTML 但只插入下方 ICONS 白名单里的静态 SVG（可信内容）。
 */

/**
 * createElement 快捷函数。
 * @param {string} tag 标签名
 * @param {object} [attrs] { class, text, dataset, style:{}, on:{event:fn}, ...attr }
 * @param {...(Node|string|number|null|undefined|Array)} children 子节点
 * @returns {HTMLElement}
 */
export function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  if (attrs) {
    for (const [key, value] of Object.entries(attrs)) {
      if (value == null) continue;
      if (key === 'class') node.className = value;
      else if (key === 'text') node.textContent = value;
      else if (key === 'dataset') Object.assign(node.dataset, value);
      else if (key === 'style' && typeof value === 'object') Object.assign(node.style, value);
      else if (key === 'on' && typeof value === 'object') {
        for (const [event, handler] of Object.entries(value)) {
          node.addEventListener(event, handler);
        }
      }
      else if (key in node && key !== 'list' && typeof value !== 'string') node[key] = value;
      else node.setAttribute(key, String(value));
    }
  }
  appendChildren(node, children);
  return node;
}

/** 递归挂载子节点（字符串 → textContent） */
function appendChildren(node, children) {
  for (const child of children) {
    if (child == null || child === false) continue;
    if (Array.isArray(child)) appendChildren(node, child);
    else if (child instanceof Node) node.appendChild(child);
    else node.appendChild(document.createTextNode(String(child)));
  }
}

/**
 * 创建 SVG 元素（含 namespace）。
 * @param {string} tag 如 svg / path / circle / line / text
 * @param {object} [attrs] 属性表
 * @returns {SVGElement}
 */
export function svgEl(tag, attrs = {}) {
  const node = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value != null) node.setAttribute(key, String(value));
  }
  return node;
}

/** 清空容器 */
export function clear(node) {
  while (node.firstChild) node.removeChild(node.firstChild);
  return node;
}

/* ── 内联图标（SF Symbols 线性风格：描边 1.5、圆角端点、currentColor） ── */
const ICONS = {
  grid: '<rect x="3.5" y="3.5" width="7" height="7" rx="2"/><rect x="13.5" y="3.5" width="7" height="7" rx="2"/><rect x="3.5" y="13.5" width="7" height="7" rx="2"/><rect x="13.5" y="13.5" width="7" height="7" rx="2"/>',
  tv: '<rect x="3" y="7" width="18" height="13" rx="2.5"/><path d="M8.5 3.5 12 7l3.5-3.5"/>',
  chart: '<path d="M3.5 20.5h17"/><path d="M4.5 15.5 9 11l3.5 3L19 7.5"/><path d="M15.5 7.5H19v3.5"/>',
  ticket: '<path d="M3.5 8.5v-2a1.5 1.5 0 0 1 1.5-1.5h14a1.5 1.5 0 0 1 1.5 1.5v2a3.5 3.5 0 0 0 0 7v2a1.5 1.5 0 0 1-1.5 1.5H5a1.5 1.5 0 0 1-1.5-1.5v-2a3.5 3.5 0 0 0 0-7Z"/><path d="M14.5 6.5v2m0 3v2m0 3v2" stroke-dasharray="2 2.5"/>',
  chat: '<path d="M20.5 12c0 4-3.8 7.2-8.5 7.2-1 0-2-.14-2.9-.4L4.5 20.5l1.2-3.4C4.1 15.7 3.5 13.9 3.5 12c0-4 3.8-7.2 8.5-7.2s8.5 3.2 8.5 7.2Z"/>',
  search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m15.5 15.5 5 5"/>',
  gear: '<circle cx="12" cy="12" r="3.2"/><path d="M12 2.8v2.6M12 18.6v2.6M4.3 12H2M21.3 12H19M6.3 6.3 4.5 4.5M19.5 19.5l-1.8-1.8M17.7 6.3l1.8-1.8M4.5 19.5l1.8-1.8"/>',
  trash: '<path d="M4.5 6.5h15M9.5 6.5V4.8A1.3 1.3 0 0 1 10.8 3.5h2.4a1.3 1.3 0 0 1 1.3 1.3v1.7"/><path d="M6.5 6.5 7.3 19a1.5 1.5 0 0 0 1.5 1.4h6.4a1.5 1.5 0 0 0 1.5-1.4l.8-12.5"/><path d="M10 10.5v6M14 10.5v6"/>',
  close: '<path d="m6 6 12 12M18 6 6 18"/>',
  min: '<path d="M5 12h14"/>',
  max: '<rect x="5" y="5" width="14" height="14" rx="2.5"/>',
  restore: '<rect x="4.5" y="8" width="11" height="11" rx="2"/><path d="M8.5 8V6.5a2 2 0 0 1 2-2H18a2 2 0 0 1 2 2V14a2 2 0 0 1-2 2h-1.5"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 3v2.2M12 18.8V21M3 12h2.2M18.8 12H21M5.6 5.6l1.6 1.6M16.8 16.8l1.6 1.6M18.4 5.6l-1.6 1.6M7.2 16.8l-1.6 1.6"/>',
  moon: '<path d="M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5Z"/>',
  external: '<path d="M14 4.5h5.5V10"/><path d="M19.5 4.5 11 13"/><path d="M18 14v4.5a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 4 18.5v-11A1.5 1.5 0 0 1 5.5 6H10"/>',
  copy: '<rect x="8.5" y="8.5" width="11" height="11" rx="2"/><path d="M5 15.5A1.5 1.5 0 0 1 3.5 14V5.5A2 2 0 0 1 5.5 3.5H14A1.5 1.5 0 0 1 15.5 5"/>',
  warn: '<path d="M12 4 2.8 19.5h18.4L12 4Z"/><path d="M12 10v4.5"/><circle cx="12" cy="17.2" r="0.4" fill="currentColor" stroke="none"/>',
  check: '<path d="m4.5 12.5 5 5 10-11"/>',
  clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2.5"/>',
  play: '<path d="M8 5.5v13l11-6.5-11-6.5Z"/>',
  edit: '<path d="M4.5 19.5h15"/><path d="M14.5 5 17 7.5 8.5 16l-3 .5.5-3L14.5 5Z"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4.5 20.5c1.2-3.4 4-5 7.5-5s6.3 1.6 7.5 5"/>',
  fire: '<path d="M12 3.5c.5 3-1.5 4.5-2.8 6C7.8 11 7 12.5 7 14.5a5 5 0 0 0 10 0c0-2.5-1.5-3.5-2-5-.4 1-1 1.5-2 2 .5-2.5-.5-6-1-8Z"/>',
  bell: '<path d="M18 16.5H6c1.2-1.6 1.7-3 1.7-5.5 0-3 1.9-5 4.3-5s4.3 2 4.3 5c0 2.5.5 3.9 1.7 5.5Z"/><path d="M10.3 19.5a1.8 1.8 0 0 0 3.4 0"/>',
  box: '<path d="M12 3.5 4 7.5v9l8 4 8-4v-9l-8-4Z"/><path d="m4 7.5 8 4 8-4M12 11.5v9"/>',
  eye: '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12Z"/><circle cx="12" cy="12" r="3"/>',
  dollar: '<path d="M12 4v16"/><path d="M16.5 7.5c-.8-1.4-2.4-2-4.5-2-2.5 0-4.5 1.1-4.5 3s2 2.6 4.5 3 4.5 1.1 4.5 3-2 3-4.5 3c-2.1 0-3.7-.6-4.5-2"/>',
  thumbsup: '<path d="M7 10.5 10.8 3.5c1.6.2 2.4 1.2 2.4 3l-.7 4h5.1a2 2 0 0 1 2 2.4l-1.2 6a2 2 0 0 1-2 1.6H7"/><path d="M7 10.5H4.5A1.5 1.5 0 0 0 3 12v8a1.5 1.5 0 0 0 1.5 1.5H7"/>',
};

/**
 * 生成内联 SVG 图标元素（继承 currentColor，禁止彩色图标）。
 * @param {keyof typeof ICONS} name
 * @param {number} [size=20] 尺寸（px）
 * @returns {SVGElement}
 */
export function icon(name, size = 20) {
  const body = ICONS[name] || ICONS.box;
  const svg = svgEl('svg', {
    width: size, height: size, viewBox: '0 0 24 24',
    fill: 'none', stroke: 'currentColor',
    'stroke-width': 1.5, 'stroke-linecap': 'round', 'stroke-linejoin': 'round',
  });
  // 只插入本文件白名单中的静态 SVG 字符串（无外部数据）
  svg.innerHTML = body;
  return svg;
}

/**
 * 复制文本到剪贴板（clipboard API + execCommand 兜底）。
 * @param {string} text
 * @returns {Promise<boolean>} 是否成功
 */
export async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    try {
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.cssText = 'position:fixed;opacity:0;';
      document.body.appendChild(ta);
      ta.select();
      const ok = document.execCommand('copy');
      ta.remove();
      return ok;
    } catch {
      return false;
    }
  }
}

/**
 * countUp 数字滚动（规范 §9：600ms --ease-out，requestAnimationFrame）。
 * @param {HTMLElement} node 目标元素
 * @param {number} to 终值
 * @param {{from?: number, dur?: number, format?: (n:number)=>string}} [opts]
 */
export function animateNumber(node, to, opts = {}) {
  const { from = 0, dur = 600, format = (n) => String(Math.round(n)) } = opts;
  const start = performance.now();
  const easeOut = (t) => 1 - Math.pow(1 - t, 3);
  function frame(now) {
    const t = Math.min(1, (now - start) / dur);
    node.textContent = format(from + (to - from) * easeOut(t));
    if (t < 1) requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);
}
