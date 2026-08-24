/**
 * Coupon List — 优惠券 + 到手价计算
 *
 * Props: coupons: [{ amount, threshold, desc, expireDate, scope, source, stackable, code }]
 *        originalPrice: number
 *
 * 事件委托: 使用 data-code 属性代替 onclick
 */

function renderCouponList(containerId, coupons, originalPrice) {
  const container = document.getElementById(containerId);
  if (!container) return;

  if (!coupons || coupons.length === 0) {
    container.innerHTML = '<div style="text-align:center;padding:40px;color:var(--text-tertiary);">暂无可用优惠券</div>';
    updateFinalPrice(originalPrice || 0, 0);
    return;
  }

  // 计算总优惠
  let totalDiscount = 0;

  container.innerHTML = coupons.map(c => {
    totalDiscount += c.amount || 0;
    const code = c.code || c.id || '';
    return `
      <div class="coupon-card">
        <div class="coupon-left">
          <div class="coupon-amount">减${escHtml(String(c.amount))}</div>
          <div class="coupon-details">
            <div>${escHtml(c.desc || `满${c.threshold}减${c.amount}`)}</div>
            <div class="scope">${escHtml(c.scope || '')} · ${escHtml(c.source || '')} ${c.stackable ? '<span class="stackable-tag">可叠加</span>' : ''}</div>
          </div>
        </div>
        <div style="text-align:right;">
          <div class="coupon-expire">${c.expireDate ? '有效期至 ' + escHtml(c.expireDate) : ''}</div>
          <button class="btn-copy" data-coupon-code="${escHtml(code)}">复制券码</button>
        </div>
      </div>
    `;
  }).join('');

  updateFinalPrice(originalPrice || 0, totalDiscount);
}

// ─── 全局事件委托：优惠券复制 ───
document.addEventListener('click', function (e) {
  const btn = e.target.closest('.btn-copy');
  if (!btn) return;
  const code = btn.dataset.couponCode;
  if (code) copyCoupon(code);
});

function copyCoupon(code) {
  if (!code) { showToast('暂无券码'); return; }
  navigator.clipboard?.writeText(code).then(() => {
    showToast('✅ 券码已复制');
  }).catch(() => {
    showToast('复制失败，请手动复制');
  });
}

/**
 * 到手价计算
 */
function updateFinalPrice(originalPrice, totalDiscount) {
  const finalPrice = Math.max(0, originalPrice - totalDiscount);

  const bar = document.getElementById('finalPriceBar');
  const priceEl = document.getElementById('finalPrice');
  const calcEl = document.getElementById('priceCalc');

  if (bar) bar.classList.remove('hidden');
  if (priceEl) priceEl.textContent = formatPrice(finalPrice);
  if (calcEl) calcEl.textContent = `原价 ${formatPrice(originalPrice)} - 优惠 ${formatPrice(totalDiscount)} = 到手价`;
}
