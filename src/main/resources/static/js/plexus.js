/* =============================================================
   plexus.js —— 标题区「点线穿梭」背景动画（2026 第四版）

   干的事：在指定容器里放一块 canvas，画一堆缓慢漂移的点，
   相邻的点自动连线，形成一张会呼吸的网。canvas 在 DOM 里排在标题
   前面、z-index 更低，所以线条看起来"从标题底下穿过"。

   为什么不写成 CSS：连线的长度和透明度**取决于两点之间的距离**，
   纯 CSS 画不出来（要几百个元素互相定位，反而更卡）。

   三条克制原则（都是踩过的坑，改之前先读）：
     ① 点数是按面积算的，不是写死的 —— 手机上点太多会掉帧；
     ② 系统开了「减少动态效果」就只画一帧静止画面，不做动画；
     ③ 单例：SPA 每次重渲染都会换掉 DOM，旧的 requestAnimationFrame
        必须停掉，否则越点越卡（每次进来多一个循环在后台跑）。
   ============================================================= */

const LINK_DISTANCE = 120;   // 超过这个距离就不连线（px）
const LINE_ALPHA = 0.22;     // 连线最浓时的透明度
const DOT_ALPHA = 0.50;      // 圆点透明度
const BLUE = '36, 99, 223';  // 品牌蓝
const TEAL = '19, 164, 143'; // 品牌青

let current = null;          // 当前在跑的实例，用于下次挂载前清理

/**
 * 在 host 元素里启动点线动画。
 * @param {HTMLElement|null} host 有定位的容器（默认找 #plexus-canvas 的父元素）
 * @returns {Function} 卸载函数
 */
export function mountPlexus(host) {
  destroyPlexus();

  const canvas = (host || document).querySelector('#plexus-canvas');
  if (!canvas) return () => {};

  const ctx = canvas.getContext('2d');
  if (!ctx) return () => {};   // 极老浏览器没有 2d 上下文，静静退出，不报错

  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  let width = 0;
  let height = 0;
  let points = [];
  let raf = 0;

  function resize() {
    // ⚠ 这里量的是 canvas 自己，不是父元素。
    // 父元素 .page-heading 是 flex:1，横跨整行，比标题文字宽得多；
    // 早先量父元素、再用内联样式把画布撑成父元素那么大，等于把 CSS 里
    // 设的宽度完全覆盖掉 —— 点数被摊薄到整行，网就全堆在标题右边的空白
    // 处，看着像"标题旁边飘着一团网"而不是"线从字底下穿过"。
    // 现在尺寸只由 CSS（#plexus-canvas）决定，脚本只负责把画布像素对齐
    // 到它实际占的框，所以改宽度请改 app.css，别在这里加 style.width。
    const rect = canvas.getBoundingClientRect();
    if (!rect.width || !rect.height) return;
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    width = rect.width;
    height = rect.height;
    canvas.width = Math.round(width * dpr);
    canvas.height = Math.round(height * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    // 点数跟着面积走：每 1700 平方像素一个点，10~46 之间。
    // 分母调小 = 点变密。这块画布被 CSS 限制在标题尺寸（约 460×110），
    // 按 2600 只摊到 17 个点，看着像零散噪点；1700 大约 30 个点，
    // 右侧才有足够的点让边缘自然淡出，而不是"线走到一半突然没了"。
    // 下限给小屏留的：手机上一块 170px 宽的画布如果硬塞十几个点，
    // 加上 120px 的连线距离，会织成一张又密又亮的网，压过标题。
    const count = Math.max(10, Math.min(46, Math.round((width * height) / 1700)));
    points = Array.from({ length: count }, () => ({
      x: Math.random() * width,
      y: Math.random() * height,
      vx: (Math.random() - 0.5) * 0.24,
      vy: (Math.random() - 0.5) * 0.24,
      r: 1 + Math.random() * 1.1,
      color: Math.random() > 0.78 ? TEAL : BLUE
    }));
  }

  function draw() {
    ctx.clearRect(0, 0, width, height);

    for (let i = 0; i < points.length; i++) {
      const p = points[i];
      if (!reduce) {
        p.x += p.vx;
        p.y += p.vy;
        if (p.x < 0 || p.x > width) p.vx = -p.vx;
        if (p.y < 0 || p.y > height) p.vy = -p.vy;
      }
      for (let j = i + 1; j < points.length; j++) {
        const q = points[j];
        const dx = p.x - q.x;
        const dy = p.y - q.y;
        const dist2 = dx * dx + dy * dy;
        if (dist2 < LINK_DISTANCE * LINK_DISTANCE) {
          // 越近越明显：连线强度随距离线性衰减，网才有"层次"
          const alpha = (1 - Math.sqrt(dist2) / LINK_DISTANCE) * LINE_ALPHA;
          ctx.strokeStyle = `rgba(${BLUE}, ${alpha.toFixed(3)})`;
          ctx.lineWidth = 0.8;
          ctx.beginPath();
          ctx.moveTo(p.x, p.y);
          ctx.lineTo(q.x, q.y);
          ctx.stroke();
        }
      }
    }

    for (const p of points) {
      ctx.beginPath();
      ctx.fillStyle = `rgba(${p.color}, ${DOT_ALPHA})`;
      ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2);
      ctx.fill();
    }

    if (!reduce) raf = requestAnimationFrame(draw);
  }

  function boot() {
    resize();
    cancelAnimationFrame(raf);
    draw();
  }

  boot();
  window.addEventListener('resize', boot);

  current = {
    stop() {
      cancelAnimationFrame(raf);
      window.removeEventListener('resize', boot);
    }
  };

  return () => destroyPlexus();
}

/** 停掉当前实例（页面切走 / 重新渲染前都要调） */
export function destroyPlexus() {
  if (current) {
    current.stop();
    current = null;
  }
}
