/**
 * 自定义脚本视图（移植自 Android 端 ScriptScreen）
 * ------------------------------------------------
 * - 预置 3 个只读安全脚本 + 自定义脚本新建 / 编辑 / 删除（本地持久化）
 * - 运行经主进程以 PowerShell 执行（对应 Android 的 Shizuku shell）
 * - 输出以等宽文本展示在脚本卡片下方
 *
 * 红线 #4：全部 DOM API 渲染，无 innerHTML 插入外部数据。
 */
import { el, icon } from '../utils/dom.js';
import { showToast } from './toast.js';

/**
 * @param {HTMLElement} container
 * @param {{onRerender: () => void}} _ctx 预留（本页自管理状态）
 */
export function renderScriptsView(container, _ctx) {
  const listWrap = el('div', { class: 'script-list' });

  container.appendChild(el('div', { class: 'script-header' },
    el('div', {},
      el('h2', { class: 'card-title', text: '自定义脚本' }),
      el('p', {
        class: 'fs-caption text-secondary script-tip',
        text: '脚本在本机以 PowerShell 权限执行（对应 Android 端的 Shizuku），请只运行你信任的内容。',
      })),
    el('button', {
      class: 'btn btn--primary',
      on: { click: () => openEditor(null, refresh) },
    }, icon('plus', 14), '新建脚本')));

  container.appendChild(listWrap);
  refresh();

  /** 拉取脚本列表并重绘 */
  async function refresh() {
    const res = await window.priceLens.scripts.list();
    listWrap.textContent = '';
    if (!res || !res.ok) {
      listWrap.appendChild(el('div', { class: 'card empty-state' },
        el('div', { class: 'e-title', text: '加载失败' }),
        el('div', { class: 'e-desc', text: (res && res.error) || '未知错误' })));
      return;
    }
    for (const script of res.scripts) {
      listWrap.appendChild(buildScriptCard(script, refresh));
    }
  }
}

/** 单个脚本卡片（含运行输出区） */
function buildScriptCard(script, onListChanged) {
  const outputEl = el('pre', { class: 'script-output', hidden: true });
  const runBtn = el('button', { class: 'btn btn--text', title: '运行' }, icon('play', 14), '运行');

  runBtn.addEventListener('click', async () => {
    runBtn.disabled = true;
    runBtn.textContent = '运行中…';
    outputEl.hidden = false;
    outputEl.textContent = '正在执行…';
    const res = await window.priceLens.scripts.run(script.id);
    runBtn.disabled = false;
    runBtn.textContent = '';
    runBtn.appendChild(icon('play', 14));
    runBtn.append('运行');
    if (!res || !res.ok) {
      outputEl.textContent = `[执行失败] ${res?.error || res?.stderr || '未知错误'}`;
      outputEl.classList.add('script-output--error');
      return;
    }
    outputEl.classList.remove('script-output--error');
    const out = res.stdout || '(无输出)';
    outputEl.textContent = res.stderr ? `${out}\n[stderr] ${res.stderr}` : out;
  });

  const actions = el('div', { class: 'script-actions' });
  if (!script.builtin) {
    actions.appendChild(el('button', {
      class: 'btn btn--text btn--danger', title: '删除',
      on: {
        click: async () => {
          const res = await window.priceLens.scripts.remove(script.id);
          if (res && res.ok) { showToast('已删除'); onListChanged(); }
          else showToast((res && res.error) || '删除失败');
        },
      },
    }, icon('trash', 14)));
    actions.appendChild(el('button', {
      class: 'btn btn--text', title: '编辑',
      on: { click: () => openEditor(script, onListChanged) },
    }, icon('edit', 14)));
  }
  actions.appendChild(runBtn);

  /* 内容预览：最多 2 行（textContent 渲染，安全） */
  const preview = script.content.split(/\r?\n/).slice(0, 2).join('\n')
    + (script.content.split(/\r?\n/).length > 2 ? ' …' : '');

  return el('div', { class: 'card script-card' },
    el('div', { class: 'script-row' },
      el('div', { class: 'script-name-wrap' },
        el('span', { class: 'script-name', text: script.name }),
        script.builtin ? el('span', { class: 'tag', text: '预置' }) : null),
      actions),
    el('pre', { class: 'script-preview', text: preview }),
    outputEl);
}

/** 新建/编辑弹窗（对齐 Android ScriptEditor：名称 + 内容） */
function openEditor(initial, onSaved) {
  const nameInput = el('input', {
    class: 'script-input', type: 'text', placeholder: '脚本名称',
    value: initial?.name || '', maxlength: 60, spellcheck: false,
  });
  const contentInput = el('textarea', {
    class: 'script-textarea', placeholder: 'PowerShell 脚本（在本机执行）',
    spellcheck: false, rows: 10,
  });
  contentInput.value = initial?.content || '';

  const mask = el('div', {
    class: 'modal-mask',
    on: { click: (e) => { if (e.target === mask) close(); } },
  });

  async function save() {
    const res = await window.priceLens.scripts.save({
      id: initial?.id || '',
      name: nameInput.value,
      content: contentInput.value,
    });
    if (res && res.ok) {
      close();
      showToast(initial ? '已保存' : '已创建');
      onSaved();
    } else {
      showToast((res && res.error) || '保存失败');
    }
  }

  function close() { mask.remove(); }

  const modal = el('div', { class: 'modal' },
    el('h3', { class: 'modal-title', text: initial ? '编辑脚本' : '新建脚本' }),
    el('div', { class: 'modal-section' }, nameInput),
    el('div', { class: 'modal-section' }, contentInput),
    el('div', { class: 'modal-row', style: { justifyContent: 'flex-end', gap: '8px' } },
      el('button', { class: 'btn', on: { click: close } }, '取消'),
      el('button', { class: 'btn btn--primary', on: { click: save } }, '保存')));

  mask.appendChild(modal);
  document.getElementById('modal-root').appendChild(mask);
  nameInput.focus();
}
