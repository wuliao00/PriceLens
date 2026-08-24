/**
 * HTML/JSON 解析工具
 *
 * 使用 cheerio 进行 HTML 解析，带降级方案
 */

/**
 * 解析 HTML 字符串为类 jQuery 对象
 * @param {string} html
 * @returns {function} — cheerio 实例 或 降级接口
 */
function parseHTML(html) {
  if (!html || typeof html !== 'string') {
    return createFallbackParser('');
  }

  try {
    const cheerio = require('cheerio');
    return cheerio.load(html);
  } catch {
    // cheerio 不可用时使用降级解析器
    return createFallbackParser(html);
  }
}

/**
 * 降级解析器（无 cheerio 时的轻量替代）
 * 提供基本的 $(selector).each().find().text() 接口
 */
function createFallbackParser(html) {
  function $(selector) {
    const results = [];

    if (typeof selector === 'string' && selector.startsWith('.')) {
      const className = selector.slice(1);
      // 匹配 class 中包含指定类名的元素
      const regex = new RegExp(
        `<([a-zA-Z][a-zA-Z0-9]*)[^>]*class="[^"]*\\b${escapeRegex(className)}\\b[^"]*"[^>]*>` +
        `([\\s\\S]*?)<\\/\\1>`,
        'gi'
      );
      let match;
      while ((match = regex.exec(html)) !== null) {
        results.push(createSimpleElement(match[0], match[2]));
      }
    }

    return createSimpleCollection(results, html);
  }

  return $;
}

function escapeRegex(str) {
  return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function createSimpleCollection(elements, _html) {
  const api = {
    length: elements.length,

    get(i) { return elements[i]; },

    each(fn) {
      elements.forEach((el, i) => fn(i, el));
      return api;
    },

    find(sel) {
      // 在子元素中搜索
      const nested = [];
      elements.forEach(el => {
        if (el.inner && typeof el.inner === 'string') {
          const subMatches = el.inner.match(
            new RegExp(`<[^>]*class="[^"]*\\b${escapeRegex(sel.replace('.', ''))}\\b[^"]*"[^>]*>([\\s\\S]*?)<\\/[^>]*>`, 'gi')
          );
          if (subMatches) {
            subMatches.forEach(m => nested.push(createSimpleElement(m, stripTags(m))));
          }
        }
      });
      return createSimpleCollection(nested);
    },

    first() {
      return elements[0] ? createSimpleCollection([elements[0]]) : createSimpleCollection([]);
    },

    text() {
      return elements.map(e => (typeof e.text === 'function' ? e.text() : '')).join(' ').trim();
    },

    attr(_name) {
      // 从 raw HTML 中提取属性
      if (elements[0]?.raw) {
        const match = elements[0].raw.match(new RegExp(`${_name}="([^"]*)"`, 'i'));
        return match ? match[1] : '';
      }
      return '';
    },

    eq(i) {
      return elements[i] ? createSimpleCollection([elements[i]]) : createSimpleCollection([]);
    },

    slice(s, e) {
      return createSimpleCollection(elements.slice(s, e));
    }
  };

  return api;
}

function createSimpleElement(rawHtml, innerHtml) {
  const el = {
    raw: rawHtml,
    inner: innerHtml,
    text() { return stripTags(this.inner); },
    find() { return createSimpleCollection([]); },
    attr() { return ''; }
  };
  return el;
}

function stripTags(str) {
  return (str || '').replace(/<[^>]*>/g, '').replace(/&amp;/g, '&').replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'").trim();
}

module.exports = { parseHTML };
