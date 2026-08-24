/**
 * Tab 路由（规范 §9 动画表：旧内容先出 150ms，新内容后入 300ms）。
 * 显式传参，无全局状态泄漏。
 */

/**
 * @param {object} opts
 * @param {HTMLElement} opts.container 视图容器（#main-content）
 * @param {Record<string, {render:(el:HTMLElement)=>void}>} opts.views
 * @param {string} opts.defaultView
 * @param {(name:string)=>void} [opts.onSwitch] 切换回调（同步侧边栏高亮）
 */
export function createRouter({ container, views, defaultView, onSwitch }) {
  let currentName = null;
  let token = 0; // 防止快速切换时旧回调覆盖新视图

  /** 用当前视图数据重绘（无切换动画，用于数据到达） */
  function rerender() {
    if (!currentName) return;
    const view = views[currentName] || views[defaultView];
    container.textContent = '';
    const node = document.createElement('div');
    node.className = 'view';
    view.render(node);
    container.appendChild(node);
    container.scrollTop = 0;
  }

  /**
   * 切换视图。
   * @param {string} name
   */
  function go(name) {
    const target = views[name] ? name : defaultView;
    if (target === currentName) {
      rerender();
      return;
    }
    const myToken = ++token;
    currentName = target;
    if (onSwitch) onSwitch(target);

    const old = container.firstElementChild;
    if (old) {
      old.classList.add('view-leave'); // 旧内容先出（150ms）
      setTimeout(() => {
        if (myToken === token) rerender(); // 新内容后入（fadeUp 300ms）
      }, 150);
    } else {
      rerender();
    }
  }

  return { go, rerender, current: () => currentName };
}
