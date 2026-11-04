/* =============================================================
   app.js —— 前端的"总控"：决定现在该显示哪个页面，并把页面画出来。

   它是怎么工作的（三句话）：
     1. 这是个**单页应用**：整站只有一个 index.html，地址栏里的 #/xxx 才是"第几页"；
     2. 地址一变（或登录状态一变）就调用 render()，把 #app 盒子里的内容整体换掉；
     3. 每个页面就是一个函数（viewLogin / viewHome / viewPassword），
        返回一段 HTML 字符串，然后挂上事件监听。

   加一个新页面只要三步：写一个 viewXxx() 函数 → 在 render() 里加一个分支
   → 在导航里加个链接。
   ============================================================= */

import {
  api, getToken, setToken, clearToken, getDeviceToken, setDeviceToken,
  requestBlob, saveBlob
} from './api.js?v=20261105';
// 标题区的点线背景动画（单独一个文件：canvas 绘制逻辑和页面渲染没关系，混进来只会更难读）
import { mountPlexus, destroyPlexus } from './plexus.js?v=20261105';

/* ---------- 全局状态：整个前端就这一个"记事本" ---------- */

const state = {
  me: null,                   // 当前登录人；null = 没登录（这就是"登录态"）
  loginChallenge: null,       // 已通过密码、等待新设备短信验证的登录挑战
  needChangePassword: false,  // 是否还在用初始密码（学号后 6 位）
  notice: null,               // 页面上要显示的一条提示：{ type, text }

  // ---- 「我的工单」列表页的状态 ----
  // 为什么放在全局 state 里、而不是写在函数内部？
  //   因为它是**跨次渲染**要记住的东西：将来点进详情再返回，得还停在第 3 页、
  //   还带着刚才的搜索词，而不是被打回第 1 页空白列表。
  orderQuery: { page: 1, pageSize: 10, status: '', keyword: '' },
  ordersTotal: 0,             // 后端返回的"符合条件的总条数"（不只有本页），用来算总页数
  ordersPage: 1,              // 后端**实际**返回的页码（后端会纠正非法页码，不能拿请求值当准）
  ordersPageSize: 10,

  // ---- 「提交工单」页的状态 ----
  submitCategory: '',         // 选中的分类（5 个中文枚举之一）
  submitContent: '',          // 已输入的问题描述（离开页面再回来能回填，不至于白写一遍）
  submitFiles: [],            // 已选/已上传的附件：[{ name, size, status, id, error }]
  submitRequestId: '',        // 幂等用的请求号：见 ensureSubmitRequestId() 的注释
  reConsultFrom: '',          // 非空 = 当前这张表单是"再次咨询"，值是**老单号**（见 viewSubmit 的注释）

  // ---- 「消息通知」页的状态 ----
  // isRead 用字符串存，因为它是绑定在 <select> 上的，而 select 的 value 只能是字符串：
  //   ''      = 全部（qs() 会把它丢掉，等于不传这个参数）
  //   'false' = 只看未读
  //   'true'  = 只看已读
  // 发请求时再把它翻成真正的布尔值，见 loadNotifs()。
  notifQuery: { page: 1, pageSize: 20, isRead: '' },
  notifsTotal: 0,
  notifsPage: 1,
  notifsPageSize: 20,

  // ---- 「常见问题」页的状态 ----
  // FAQ 列表接口没有分页（返回的就是一个数组），所以这里只有筛选条件、没有页码。
  faqQuery: { category: '', keyword: '' },

  // ---- 「工单详情」页的写操作状态 ----
  detailId: '',               // 当前正在看的单号（追问 / 确认解决都要带上它）
  detailVersion: 0,           // 详情接口给的乐观锁版本号（「确认解决」必须原样传回去）
  followUpRequestId: '',      // 追问的幂等号：见 ensureFollowUpRequestId()
  replyRequestId: '',         // 干事回复的幂等号：见 ensureReplyRequestId()（和追问两个号，别混用）
  detailTpls: [],             // 「插入模板」下拉里的模板（进详情页时异步拉的，只含启用中的）

  // ---- 「合并工单」的状态（⑩ 第十一步）----
  // 这三样都是"一次合并尝试的中间过程"，换一张单就必须全部清空（见 viewOrderDetail）。
  // 为什么不留着：留着的话，从 A 单切到 B 单时会看到"已选主工单"还是上一次选的，
  // 而那个选择是针对 A 单做的 —— 看起来像界面记错了。
  mergeKeyword: '',           // 搜主工单时敲的关键词
  mergeHits: [],              // 搜索结果（就是列表接口返回的那一页，已经掐掉了自己）
  mergePicked: null,          // 已选中的主工单：{ workOrderId, status, statusLabel, content }

  // ---- 管理后台：用户管理页的状态（⑩ 第 4 项第一块）----
  // 和 orderQuery 一个套路：跨次渲染要记住页码和筛选条件，
  // 否则"改完一个账号状态、列表一刷新"就被打回第 1 页，翻了三页才找到的人会很崩溃。
  adminUserQuery: { page: 1, pageSize: 20, userType: '', status: '', keyword: '' },
  adminUsersTotal: 0,
  adminUsersPage: 1,
  adminUsersPageSize: 20,

  // ---- 管理后台：敏感词页的状态（⑩ 第 4 项第二块）----
  // 后端这个列表**不分页**（一次全给），所以整份缓存在内存里，
  // 「找词」和「只看启用中」都是在这个数组上做本地过滤，**不发请求**。
  adminWords: [],
  adminWordFilter: { keyword: '', onlyActive: false },

  // ---- 管理后台：回复模板页的状态（⑩ 第 4 项第三块）----
  // adminTplEdit 非 null = "正在编辑这一条"：表单里显示的就是它的值，
  // 保存时走 PUT（只发改动的字段），按钮变成「保存修改」。
  // 用**一个表单**兼顾新增和编辑，而不是给编辑另写一套字段 ——
  // 四个字段写两份，早晚会出现"两处校验不一致"。
  adminTplList: [],
  adminTplFilter: { category: '', status: 'ALL' },
  adminTplEdit: null,

  // ---- 管理后台：数据统计页的状态（⑩ 第 4 项第四块）----
  // preset 记的是"当前用的是哪个快捷范围"（today / 7d / 30d / all），只用来给按钮加高亮；
  // 真正发给后端的是 startDate / endDate（空串 = 不传 = 全量）。
  // 用户手动改了日期 ⇒ preset 变成 'custom'，四个快捷按钮就都不高亮了。
  adminStatsPreset: 'all',
  adminStatsRange: { startDate: '', endDate: '' },
  adminStats: null,           // overview 的 data
  adminWorkload: [],          // 干事工作量（数组）

  // ---- 管理后台：数据导出页的状态（⑩ 第 4 项第五块）----
  // 和统计页一个套路：preset 只用来给快捷按钮加高亮，真正发出去的是 startDate / endDate；
  // 另外三个筛选（状态 / 分类 / 关键词）也留在这里 —— 导完一次想换个状态再导，
  // 条件还在，不用重新选一遍。
  adminExportPreset: 'all',
  adminExportRange: { startDate: '', endDate: '' },
  adminExportFilter: { status: '', category: '', keyword: '' },

  // ---- 管理后台：操作日志页的状态（⑩ 第 4 项第六块）----
  // 一页装两个接口（状态日志 / 操作日志），所以分页和筛选都备**两套** ——
  // 切 tab 回来时不会把另一边的页码、条件冲掉（和 orderQuery 一个套路）。
  adminLogTab: 'status',          // 'status' = 状态日志 / 'operation' = 操作日志
  adminLogQuery: { workOrderId: '', page: 1, pageSize: 20 },
  adminLogTotal: 0,               // 后端返回的"符合条件总数"（用来算总页数）
  adminLogPage: 1,                // 后端**实际**返回的页码（它会纠正非法页码）
  adminLogPageSize: 20,
  adminLogList: [],
  adminLogOpQuery: { operatorId: '', operationType: '', startDate: '', endDate: '', page: 1, pageSize: 20 },
  adminLogOpTotal: 0,
  adminLogOpPage: 1,
  adminLogOpPageSize: 20,
  adminLogOpList: [],
  // 正在"点开看详情"的那条操作日志（存 logId；空串 = 全部收起）。
  // 只允许展开一条：日志是拿来核对某一件事的，同时铺开好几条反而看不清。
  adminLogExpanded: '',

  unreadCount: 0              // 顶部「消息」按钮上的红点数字（未读通知数）
};

const NOW_CHANGE_KEY = 'sc_need_pwd';   // 把"该改密码"这件事记在浏览器里，刷新后还记得

const appEl = document.getElementById('app');

/* ---------- 一、小工具 ---------- */

/** 把要显示的文本先转义。工单内容、姓名都是用户自己填的，直接塞进 HTML 等于给人留了写脚本的口子。 */
function esc(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

const ROLE_CN = { STUDENT: '学生', STAFF: '干事', ADMIN: '管理员' };
function roleCn(r) {
  return ROLE_CN[r] || r || '';
}

/**
 * 状态码 → 中文名 / 徽章颜色。
 *
 * ⚠️ 中文名这里**只用来做兜底**：正常情况下显示的是后端返回的 statusLabel。
 *    为什么还要写一份？因为万一后端以后加了新状态而前端忘了同步，
 *    至少还能显示原始英文码，而不是一片空白。
 */
const STATUS_LABEL = {
  PENDING: '待接单',
  PROCESSING: '处理中',
  REPLIED: '已回复',
  CLOSED: '已关闭',
  MERGED: '已合并'
};

const BADGE_CLASS = {
  PENDING: 'badge-pending',
  PROCESSING: 'badge-processing',
  REPLIED: 'badge-replied',
  CLOSED: 'badge-closed',
  MERGED: 'badge-merged'
};

/** 筛选下拉的选项。空串 = "不筛"（qs() 会把它丢掉，不会拼成 status= 发出去）。 */
const STATUS_FILTERS = [
  { value: '', label: '全部状态' },
  { value: 'PENDING', label: '待接单' },
  { value: 'PROCESSING', label: '处理中' },
  { value: 'REPLIED', label: '已回复' },
  { value: 'CLOSED', label: '已关闭' },
  { value: 'MERGED', label: '已合并' }
];

/**
 * 通知类型 → 中文短标签（消息通知页每条左边那个小标签）。
 *
 * 和 STATUS_LABEL 一样的定位：后端万一日后加了新类型而前端没跟上，
 * notifyTypeCn() 会原样显示英文码，不至于变成一片空白。
 */
const NOTIFY_TYPE = {
  NEW_TICKET: '新工单',
  ACCEPT: '已接单',
  REPLY: '新回复',
  FOLLOW_UP: '学生追问',
  CLOSE: '已关闭',
  MERGE: '已合并',
  PENDING_REMIND: '待接单提醒',
  EXCEPTION: '系统异常'
};

function notifyTypeCn(t) {
  return NOTIFY_TYPE[t] || t || '通知';
}

/**
 * 通知列表的筛选下拉。
 * ⚠️ value 必须和接口的 isRead 参数对得上：'' = 不筛、'false' = 未读、'true' = 已读。
 *    （写成 'all'/'unread' 这种自定义值的话，loadNotifs() 里就得多一层翻译，更容易错。）
 */
const NOTIF_FILTERS = [
  { value: '', label: '全部通知' },
  { value: 'false', label: '只看未读' },
  { value: 'true', label: '只看已读' }
];

/** 未读通知数的轮询间隔（毫秒）。契约里写的就是 15 秒。 */
const UNREAD_POLL_MS = 15000;

/**
 * 提交工单时可选的 5 个分类。
 *
 * ⚠️ 这份列表和后端 `WorkOrderService.CATEGORIES` 是**两份**，改一边就得改另一边。
 *    之所以敢这么写：后端收到不合法分类会返回 400，而且消息里会把正确分类列全，
 *    所以就算漏改也不会"静默出错"，最多是用户看到一句明确的报错。
 *    为它单独做一个"查分类"的接口不值得 —— 它就是个人工维护的固定枚举。
 */
const CATEGORIES = ['学时录入', '审核驳回', '活动咨询', '规则疑问', '其他'];

/** 后端单文件上限 5 MB。这里只是"提前拦一下"，真判定以后端为准（它还会查后缀白名单和文件头）。 */
const MAX_UPLOAD_BYTES = 5 * 1024 * 1024;

/**
 * 生成一个 uuid，用作提交工单的幂等号（requestId）。
 *
 * 优先用浏览器自带的 `crypto.randomUUID()`：它只在**安全上下文**（https 或 localhost）里存在，
 * 所以必须留降级分支 —— 将来把系统部署到 `http://192.168.x.x` 这种内网 IP 上时
 * （校内网部署就是这种），它会直接是 undefined，只有降级这段能兜住。
 */
function uuid() {
  if (window.crypto && typeof window.crypto.randomUUID === 'function') {
    return window.crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
    const r = Math.random() * 16 | 0;
    return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
  });
}

/**
 * 取本次提交要用的 requestId，没有就先造一个并记住。
 *
 * 为什么"记住"而不每次点提交都新造：幂等的意义就在于
 * 「同一次提交动作重试时沿用同一个号」，后端才认得出"这是同一个请求"。
 * 什么时候该换新的，见 onSubmit() 里成功 / 失败两个分支的注释。
 */
function ensureSubmitRequestId() {
  if (!state.submitRequestId) state.submitRequestId = uuid();
  return state.submitRequestId;
}

/** 文件大小给人看的写法：900 → 900 B、2048 → 2 KB、1153433 → 1.1 MB */
function fmtSize(n) {
  if (n === null || n === undefined) return '';
  if (n < 1024) return n + ' B';
  if (n < 1024 * 1024) return (n / 1024).toFixed(0) + ' KB';
  return (n / 1024 / 1024).toFixed(1) + ' MB';
}

/** 详情页用的完整时间：2026-10-05 22:07:31 → 2026-10-05 22:07（秒没意义，但日期要留）。 */
function fmtFull(s) {
  const m = String(s === null || s === undefined ? '' : s)
    .match(/^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2})/);
  return m ? m[1] + ' ' + m[2] : String(s === null || s === undefined ? '' : s);
}

/** 工单要走的正常流程（进度条就是照这个顺序画的）。MERGED 不在这条线上，单独处理。 */
const FLOW = ['PENDING', 'PROCESSING', 'REPLIED', 'CLOSED'];

/**
 * 时间短一点：后端给的是 "2026-08-22 14:30:00"，列表里显示成 "08-22 14:30"。
 * 列表一眼扫过去，年份和秒基本没用，但会占掉手机上珍贵的一行宽度。
 */
function fmtTime(s) {
  const m = String(s == null ? '' : s).match(/^\d{4}-(\d{2}-\d{2})[ T](\d{2}:\d{2})/);
  return m ? m[1] + ' ' + m[2] : String(s == null ? '' : s);
}

/** 列表页的标题按角色变：同一份代码，三种人看到三种叫法。 */
function listTitle() {
  if (state.me && state.me.role === 'STAFF') return '工单处理';
  if (state.me && state.me.role === 'ADMIN') return '全部工单';
  return '我的工单';
}

/** "还在用初始密码"的黄条。多个页面都要显示，抽出来免得写好几遍。 */
function needPwdAlert() {
  const need = state.needChangePassword || localStorage.getItem(NOW_CHANGE_KEY) === '1';
  if (!need) return '';
  return `
    <div class="alert alert-warn">
      你还在用初始密码（学号后 6 位）。为了账号安全，建议现在就改掉。
      <button class="btn btn-ghost" id="go-pwd" type="button">去修改密码 ›</button>
    </div>`;
}

function bindPwdAlert() {
  const b = document.getElementById('go-pwd');
  if (b) b.addEventListener('click', () => go('/password'));
}

/** 是不是管理员。管理后台 6 个页面共用这一句判断。 */
function isAdmin() {
  return !!state.me && state.me.role === 'ADMIN';
}

/** 当前路由：#/password → /password；地址栏没写哈希 → / */
function currentRoute() {
  return location.hash.replace(/^#/, '') || '/';
}

/** 跳转到某个路由。哈希没变时浏览器不会触发 hashchange，所以这里手动补一次渲染。 */
function go(route) {
  const target = '#' + route;
  if (location.hash === target) {
    render();
  } else {
    location.hash = target;
  }
}

/** 提示条。只替换提示那一小块，**不重画整个页面** —— 否则用户填了一半的表单会被清空。 */
function alertHtml() {
  if (!state.notice) return '';
  return '<div class="alert alert-' + state.notice.type + '">' + esc(state.notice.text) + '</div>';
}

function setNotice(type, text) {
  state.notice = text ? { type, text } : null;
  const slot = document.getElementById('notice-slot');
  if (slot) slot.innerHTML = alertHtml();
}

/**
 * 接口说"要登录"时的统一善后。
 *
 * ⚠️ 为什么不能总是 `state.me = null; render()`：
 *    游客能进的页面（公告、常见问题）是**白名单放行**的 —— 已经没登录了还调 render()，
 *    会把**同一个页面**再画一遍、再发一次请求、再拿到 401…… 变成打不开的死循环。
 *    所以游客态下只给一句提示，不动页面。
 *
 *    其余页面（工单、管理端等）游客本来就进不去，`state.me = null; render()` 会把人
 *    送回登录页 —— 那些地方保持原样即可。
 */
function onNeedLogin(msg) {
  if (!state.me) {                       // 已经是游客 → 别再 render()，会原地打转
    setNotice('err', msg || '这个页面需要登录才能看。');
    return;
  }
  state.me = null;
  state.loginChallenge = null;
  render();
}

/* ---------- 二、公共片段 ---------- */

/** 顶部条。每个登录后的页面都套它，所以只写一次。 */
/**
 * 顶部条。**有两个版本**：登录版 和 游客版。
 *
 * ⚠️ 游客版（2026-10-10 加）存在的唯一理由：
 *    用户拍板「公告/FAQ 不登录也能看」，而这两个页面用的就是这条顶部条 ——
 *    登录版下面那两行直接读 `me.userName` / `me.role`，游客（`state.me` 是 null）
 *    一打开就会**崩在渲染里**，连公告都看不到。所以必须先有一版"不碰 me"的。
 *
 *    游客版只留四样：品牌、「常见问题」、「公告」、「登录」。
 *    · 不给「消息」—— 未读红点本来就是登录后才有；
 *    · 不给「更多」下拉 —— 里面那两条（公告、常见问题）在这里已经单列出来了，
 *      再套一层下拉纯属多余；
 *    · 游客能进哪些页面，由 `render()` 里的那份**白名单**说了算，
 *      这里只是给按钮 —— 两边要一起改，否则会出现"按钮点了没反应"。
 */
function brandLockupHtml() {
  return `
    <span class="brand-lockup">
      <span class="brand-mark" aria-hidden="true">
        <svg viewBox="0 0 32 32" role="img" focusable="false">
          <path d="M7.5 7.5h8.2c2.2 0 4 1.8 4 4v13.2c-.6-2.1-2.5-3.6-4.8-3.6H7.5V7.5Z"></path>
          <path d="M24.5 7.5h-4.8c1.2 1 2 2.4 2 4.1v10.6c1.4-1 3.1-1.1 4.8-.9V7.5H24.5Z"></path>
          <path d="M11 11.3h5M11 15.1h4.2"></path>
        </svg>
      </span>
      <span class="brand-copy">
        <span class="brand-title" data-text="第二课堂">第二课堂</span>
        <span class="brand-subtitle">学时答疑服务台</span>
      </span>
    </span>`;
}

function topbarHtml() {
  const me = state.me;

  if (!me) {
    return `
      <div class="topbar">
        <div class="topbar-inner">
          ${brandLockupHtml()}
          <span class="spacer"></span>
          <nav class="topbar-actions" aria-label="主导航">
            <button class="btn btn-ghost nav-link" id="g-faq" type="button">常见问题</button>
            <button class="btn btn-ghost nav-link" id="g-ann" type="button">公告</button>
            <button class="btn btn-primary nav-login" id="g-login" type="button">登录</button>
          </nav>
        </div>
      </div>`;
  }

  const userName = me.userName || '使用者';
  const safeUserName = esc(userName);
  const initial = esc(userName.trim().slice(0, 1) || '用');
  return `
    <div class="topbar">
      <div class="topbar-inner">
        ${brandLockupHtml()}
        <span class="spacer"></span>

        <nav class="topbar-actions" aria-label="用户菜单">
          <span class="more-wrap" id="more-wrap">
            <button class="btn btn-ghost more-btn nav-link" id="more-btn" type="button"
                    aria-label="打开更多导航" aria-haspopup="true" aria-expanded="false">
              <span class="btn-icon" aria-hidden="true">＋</span>
              <span class="btn-text">更多</span>
              <span class="caret" aria-hidden="true">▾</span>
            </button>
            <div class="more-menu" id="more-menu" hidden>
              ${me.role === 'ADMIN'
                ? '<button class="more-item" id="mi-admin" type="button">管理后台</button>' : ''}
              <button class="more-item" id="mi-security" type="button">账号安全</button>
              <button class="more-item" id="mi-ann" type="button">公告</button>
              <button class="more-item" id="mi-faq" type="button">常见问题</button>
            </div>
          </span>

          <button class="btn btn-ghost msg-entry nav-link" id="msg-entry" type="button"
                  aria-label="消息通知">
            <span class="btn-icon" aria-hidden="true">✉</span>
            <span class="btn-text">消息</span>${unreadDotHtml()}
          </button>

          <span class="user-chip" title="${safeUserName} · ${roleCn(me.role)}">
            <span class="user-avatar" aria-hidden="true">${initial}</span>
            <span class="user-copy">
              <span class="who">${safeUserName}</span>
              <span class="user-role">${roleCn(me.role)}</span>
            </span>
          </span>
          <button class="btn btn-ghost logout-btn nav-link" id="logout-btn" type="button"
                  aria-label="退出登录">
            <span class="btn-icon" aria-hidden="true">↗</span>
            <span class="btn-text">退出</span>
          </button>
        </nav>
      </div>
    </div>`;
}

function bindTopbar() {
  // ---- 游客版：只有「常见问题」「公告」「登录」三个按钮，绑完就收工 ----
  if (!state.me) {
    const gFaq = document.getElementById('g-faq');
    if (gFaq) gFaq.addEventListener('click', () => go('/faqs'));
    const gAnn = document.getElementById('g-ann');
    if (gAnn) gAnn.addEventListener('click', () => go('/announcements'));
    // 「登录」的目标是根路径 —— 未登录时 render() 会把根路径渲染成登录页
    const gLogin = document.getElementById('g-login');
    if (gLogin) gLogin.addEventListener('click', () => go('/'));
    return;
  }

  const b = document.getElementById('logout-btn');
  if (b) b.addEventListener('click', onLogout);
  const msg = document.getElementById('msg-entry');
  if (msg) msg.addEventListener('click', () => go('/notifications'));

  const moreBtn = document.getElementById('more-btn');
  if (moreBtn) moreBtn.addEventListener('click', toggleMore);
  // 「管理后台」只有管理员看得到（topbarHtml 里就按 role 决定画不画），
  // 所以这里拿不到元素是正常的，必须判空再绑。
  const miAdmin = document.getElementById('mi-admin');
  if (miAdmin) miAdmin.addEventListener('click', () => go('/admin'));
  const miSecurity = document.getElementById('mi-security');
  if (miSecurity) miSecurity.addEventListener('click', () => go('/password'));
  const miAnn = document.getElementById('mi-ann');
  if (miAnn) miAnn.addEventListener('click', () => go('/announcements'));
  const miFaq = document.getElementById('mi-faq');
  if (miFaq) miFaq.addEventListener('click', () => go('/faqs'));
}

/* ---------- 顶部「更多」下拉 ---------- */

/**
 * 打开 / 收起「更多」菜单。
 *
 * 为什么不拿一个「菜单开着吗」的变量记状态：
 *   这个菜单每次 render() 都会被整块重画，变量很容易和真实 DOM 对不上
 *   （比如跳页之后变量还说"开着"，可刚画出来的菜单明明是收着的）。
 *   直接读 DOM 上的 hidden 属性，永远只有一份"事实"。
 */
function toggleMore() {
  const menu = document.getElementById('more-menu');
  if (!menu) return;
  const willOpen = menu.hidden;
  menu.hidden = !willOpen;
  const btn = document.getElementById('more-btn');
  if (btn) btn.setAttribute('aria-expanded', willOpen ? 'true' : 'false');
}

function closeMore() {
  const menu = document.getElementById('more-menu');
  if (menu) menu.hidden = true;
  const btn = document.getElementById('more-btn');
  if (btn) btn.setAttribute('aria-expanded', 'false');
}

/**
 * 点页面别处就收起下拉。
 *
 * ⚠️ 这个监听器必须**只绑一次**（所以放在 boot() 里，而不是放在每次渲染都会执行的
 *    bindTopbar() 里）—— 绑在 document 上的监听器会越积越多，点一下页面要白跑好几遍。
 */
function bindMoreOutsideClick() {
  document.addEventListener('click', function (e) {
    const wrap = document.getElementById('more-wrap');
    if (!wrap) return;                            // 这一页没有下拉（比如登录页）
    if (e.target.closest('#more-wrap')) return;   // 点在它自己身上，交给它自己处理
    closeMore();
  });
}

/* ---------- 未读通知红点（每 15 秒问一次后端） ---------- */

/** 轮询定时器的句柄。非 null 就说明已经开着 —— 用它避免叠出两个定时器。 */
let unreadTimer = null;

/** 红点里的内容。0 条就不显示；超过 99 显示 99+ —— 否则数字会把顶部按钮撑歪。 */
function unreadDotHtml() {
  const n = state.unreadCount;
  if (!(n > 0)) return '';
  return `<span class="dot" title="${n} 条未读通知">${n > 99 ? '99+' : n}</span>`;
}

/**
 * 只把顶部「消息」按钮里那一小块重画一次 —— **绝不重画整页**。
 * 每 15 秒整页重画一遍的话，用户正在填的表单、刚翻到的位置全会被清掉。
 */
function paintUnread() {
  const btn = document.getElementById('msg-entry');
  if (!btn) return;
  // 兼容旧版被其他脚本替换成纯文字的按钮；当前顶栏会走下面的完整结构。
  if (!btn.querySelector('.btn-icon')) {
    btn.innerHTML = '消息' + unreadDotHtml();
    return;
  }
  btn.innerHTML =
    '<span class="btn-icon" aria-hidden="true">✉</span>' +
    '<span class="btn-text">消息</span>' +
    unreadDotHtml();
}

/** 问后端要一次未读数。失败**静默跳过**：红点没刷新而已，弹个错误框反而莫名其妙。 */
async function refreshUnread() {
  // 标签页在后台时不发请求：省电省流量，也免得切回来时一次涌进好几个过期响应
  if (document.hidden) return;

  const r = await api.notificationUnreadCount();
  if (r.needLogin) { stopUnreadPolling(); state.me = null; render(); return; }
  if (!r.ok) return;

  state.unreadCount = (r.data && r.data.unreadCount) || 0;
  paintUnread();
}

/** 从别的标签页切回这一页时立刻刷一次，不用干等最多 15 秒。 */
function onVisibilityChange() {
  if (!document.hidden) refreshUnread();
}

/** 登录后开始轮询。重复调用是安全的（已经在跑就直接返回）。 */
function startUnreadPolling() {
  if (unreadTimer) return;
  unreadTimer = setInterval(refreshUnread, UNREAD_POLL_MS);
  document.addEventListener('visibilitychange', onVisibilityChange);
  refreshUnread();
}

/** 登出 / Token 失效时停掉，否则定时器会一直拿着一个已经没用的 Token 去打接口。 */
function stopUnreadPolling() {
  if (unreadTimer) { clearInterval(unreadTimer); unreadTimer = null; }
  document.removeEventListener('visibilitychange', onVisibilityChange);
}

async function onLogout() {
  // 后端的登出改成了"永不失败"，但网络本身可能断 —— 所以不管结果如何，本地都要清干净，
  // 否则用户会卡在一个"看着像登录着、其实什么都点不动"的状态里。
  await api.logout();
  // 轮询要停掉：否则每 15 秒还会拿着一个已经作废的 Token 去打一次接口
  stopUnreadPolling();
  clearToken();
  localStorage.removeItem(NOW_CHANGE_KEY);
  state.me = null;
  state.loginChallenge = null;
  state.needChangePassword = false;
  state.unreadCount = 0;
  // 换个账号登进来，不该还停在上一个人翻到的第 3 页、带着他的搜索词、他的未读红点
  state.orderQuery = { page: 1, pageSize: 10, status: '', keyword: '' };
  state.notifQuery = { page: 1, pageSize: 20, isRead: '' };
  state.notice = { type: 'ok', text: '已退出登录' };
  go('/');
}

/* ---------- 三、页面：登录 ---------- */

function viewLogin() {
  const challenge = state.loginChallenge;
  appEl.innerHTML = `
    <div class="center-wrap">
      <div class="login-shell">
        <section class="login-story">
          ${brandLockupHtml()}
          <div class="login-story-copy">
            <p class="eyebrow">学生事务 · 在线服务</p>
            <h1>每一次疑问，<br><span>都有回应。</span></h1>
            <p class="login-lead">第二课堂学时问题、活动咨询与规则疑问，都可以在这里提交、追踪和确认。</p>
          </div>
          <div class="login-points" aria-label="服务特点">
            <div class="login-point">
              <span class="login-point-index">01</span>
              <span><strong>随时提交</strong><small>问题、附件一次说清</small></span>
            </div>
            <div class="login-point">
              <span class="login-point-index">02</span>
              <span><strong>全程可查</strong><small>处理进度实时可见</small></span>
            </div>
            <div class="login-point">
              <span class="login-point-index">03</span>
              <span><strong>闭环确认</strong><small>解决后再关闭工单</small></span>
            </div>
          </div>
          <p class="login-story-foot">第二课堂学时答疑 · 让每次沟通都有记录</p>
        </section>

        <section class="login-panel">
          <div class="card login-card">
            <div class="login-mobile-brand">${brandLockupHtml()}</div>
            ${challenge ? `
              <div class="login-heading">
                <p class="eyebrow">新设备验证</p>
                <h2>确认是你本人</h2>
                <p class="sub">密码校验已通过，请输入发送至 ${esc(challenge.maskedPhone || '绑定手机号')} 的验证码</p>
                ${challenge.debugCode ? `<p class="muted login-hint">本地验证码：${esc(challenge.debugCode)}</p>` : ''}
              </div>
              <div id="notice-slot">${alertHtml()}</div>
              <form id="login-sms-form">
                <div class="field">
                  <label for="loginSmsCode">短信验证码</label>
                  <input class="input sms-code-input" id="loginSmsCode" type="text"
                         inputmode="numeric" maxlength="6" autocomplete="one-time-code"
                         placeholder="6 位验证码" required>
                </div>
                <button class="btn btn-primary btn-block login-submit" id="login-sms-btn" type="submit">
                  验证并登录 <span aria-hidden="true">→</span>
                </button>
                <button class="btn btn-ghost btn-block" id="login-sms-back" type="button">返回密码登录</button>
              </form>
            ` : `
              <div class="login-heading">
                <p class="eyebrow">学时答疑</p>
                <h2>欢迎回来</h2>
                <p class="sub">请用学号登录，查看你的工单与消息</p>
              </div>
              <div id="notice-slot">${alertHtml()}</div>
              <form id="login-form">
                <div class="field">
                  <label for="userId">学号</label>
                  <input class="input" id="userId" type="text" inputmode="numeric"
                         autocomplete="username" placeholder="请输入学号" required>
                </div>
                <div class="field">
                  <label for="password">密码</label>
                  <input class="input" id="password" type="password"
                         autocomplete="current-password" placeholder="请输入密码" required>
                </div>
                <button class="btn btn-primary btn-block login-submit" id="login-btn" type="submit">
                  登录服务台 <span aria-hidden="true">→</span>
                </button>
              </form>
              <div class="login-quick-links">
                <button class="lnk" id="login-register" type="button">注册学生账号</button>
                <button class="lnk" id="login-forgot" type="button">忘记密码</button>
              </div>
              <div class="login-assist">
                <p class="muted login-hint">
                  预置账号初次登录密码为<strong>学号后 6 位</strong>；自助注册账号使用自己设置的密码。
                </p>
              </div>
            `}
          </div>
          <!-- 「无需登录也能看」两个入口：放在**表单卡下方**的玻璃底板上。
               放在卡里会把它挤成一张越来越长的竖长条，卡片高度也就跟着虚高；
               挪出来之后是「一张主卡 + 两张次卡」，主次清楚、高度也收住。
               短信验证那一步不显示：避免在夹着验证码的当口把人带去别的页面。 -->
          ${challenge ? '' : `
          <div class="login-guest">
            <p class="login-guest-title">无需登录也能查看</p>
            <div class="login-guest-links">
              <button class="login-guest-item" id="login-see-faq" type="button">
                <strong>常见问题</strong>
                <small>学时规则与常见疑问</small>
              </button>
              <button class="login-guest-item" id="login-see-ann" type="button">
                <strong>查看公告</strong>
                <small>服务通知与更新</small>
              </button>
            </div>
          </div>`}
        </section>
      </div>
    </div>`;
  if (challenge) {
    document.getElementById('login-sms-form').addEventListener('submit', onVerifyLoginSms);
    document.getElementById('login-sms-back').addEventListener('click', () => {
      state.loginChallenge = null;
      render();
    });
    document.getElementById('loginSmsCode').focus();
    return;
  }

  document.getElementById('login-form').addEventListener('submit', onLogin);
  document.getElementById('login-register').addEventListener('click', () => go('/register'));
  document.getElementById('login-forgot').addEventListener('click', () => go('/forgot-password'));

  // 这两个是游客**唯一**能进的页面入口 —— 不放这里的话，
  // 公告和 FAQ 对游客来说等于"存在但找不到"（游客态顶部条只有进了这两个页面才出现）。
  const seeFaq = document.getElementById('login-see-faq');
  if (seeFaq) seeFaq.addEventListener('click', () => go('/faqs'));
  const seeAnn = document.getElementById('login-see-ann');
  if (seeAnn) seeAnn.addEventListener('click', () => go('/announcements'));
}

async function onLogin(e) {
  e.preventDefault();
  const btn = document.getElementById('login-btn');
  const userId = document.getElementById('userId').value.trim();
  const password = document.getElementById('password').value;
  if (!userId || !password) return;

  btn.disabled = true;
  btn.textContent = '登录中…';
  setNotice(null, null);

  const r = await api.login(userId, password, getDeviceToken());
  if (!r.ok) {
    btn.disabled = false;
    btn.textContent = '登录';
    setNotice('error', r.message);
    return;
  }

  if (r.data.requiresSms) {
    state.loginChallenge = r.data;
    setNotice(null, null);
    render();
    return;
  }
  await finishLogin(r.data);
}

async function onVerifyLoginSms(e) {
  e.preventDefault();
  const challenge = state.loginChallenge;
  if (!challenge) return;
  const btn = document.getElementById('login-sms-btn');
  const smsCode = document.getElementById('loginSmsCode').value.trim();
  if (!/^\d{6}$/.test(smsCode)) {
    setNotice('error', '请输入 6 位短信验证码');
    return;
  }

  btn.disabled = true;
  btn.textContent = '验证中…';
  setNotice(null, null);
  const r = await api.verifyLoginSms(challenge.challengeId, smsCode);
  if (!r.ok) {
    btn.disabled = false;
    btn.textContent = '验证并登录';
    setNotice('error', r.message);
    return;
  }
  state.loginChallenge = null;
  await finishLogin(r.data);
}

async function finishLogin(data) {
  if (data.deviceToken) setDeviceToken(data.deviceToken);
  setToken(data.token);
  state.needChangePassword = !!data.needChangePassword;
  if (state.needChangePassword) localStorage.setItem(NOW_CHANGE_KEY, '1');

  // 登录接口其实已经带了姓名和角色，但 /api/auth/me 才是"当前用户"的单一事实来源，
  // 顺手再确认一次 Token 真的能用。
  const m = await api.me();
  state.me = m.ok
    ? m.data
    : { userId: data.userId, userName: data.userName, role: data.role };

  setNotice(null, null);
  // 登进来就开始盯着未读通知（顶部红点）；顺带会立刻问一次，所以红点是马上出来的
  startUnreadPolling();
  go('/');
}

function startSmsCountdown(btn) {
  if (!btn) return;
  let remaining = 60;
  btn.disabled = true;
  btn.textContent = remaining + ' 秒后重试';
  const timer = window.setInterval(() => {
    remaining -= 1;
    if (remaining <= 0) {
      window.clearInterval(timer);
      if (document.body.contains(btn)) {
        btn.disabled = false;
        btn.textContent = '重新获取';
      }
      return;
    }
    if (document.body.contains(btn)) btn.textContent = remaining + ' 秒后重试';
  }, 1000);
}

function showSmsResult(r, successText) {
  if (!r.ok) {
    setNotice('error', r.message);
    return false;
  }
  const debug = r.data && r.data.debugCode ? `（本地验证码：${r.data.debugCode}）` : '';
  setNotice('ok', (r.message || successText) + debug);
  return true;
}

function viewRegister() {
  const backText = state.me ? '‹ 返回' : '‹ 返回登录';
  appEl.innerHTML = `
    ${topbarHtml()}
    <div class="wrap auth-page-wrap">
      <button class="btn auth-back" id="register-back" type="button">${backText}</button>
      <div class="card auth-form-card">
        <div class="auth-form-head">
          <p class="eyebrow">学生账号</p>
          <h2>注册服务台账号</h2>
          <p class="sub">注册成功后角色固定为学生。管理员账号不会通过此页面创建。</p>
        </div>
        <div id="notice-slot">${alertHtml()}</div>
        <form id="register-form">
          <div class="auth-grid">
            <div class="field">
              <label for="regUserId">学号</label>
              <input class="input" id="regUserId" maxlength="32" autocomplete="username"
                     placeholder="请输入学号" required>
            </div>
            <div class="field">
              <label for="regUserName">姓名</label>
              <input class="input" id="regUserName" maxlength="50" autocomplete="name"
                     placeholder="请输入真实姓名" required>
            </div>
          </div>
          <div class="field">
            <label for="regPhone">手机号</label>
            <input class="input" id="regPhone" type="tel" inputmode="numeric"
                   maxlength="11" autocomplete="tel" placeholder="用于身份验证和密码找回" required>
          </div>
          <div class="field">
            <label for="regSmsCode">短信验证码</label>
            <div class="sms-code-row">
              <input class="input" id="regSmsCode" type="text" inputmode="numeric"
                     maxlength="6" autocomplete="one-time-code" placeholder="6 位验证码" required>
              <button class="btn" id="reg-send-code" type="button">获取验证码</button>
            </div>
          </div>
          <div class="auth-grid">
            <div class="field">
              <label for="regPassword">设置密码</label>
              <input class="input" id="regPassword" type="password" minlength="6" maxlength="20"
                     autocomplete="new-password" placeholder="6-20 位，含字母和数字" required>
            </div>
            <div class="field">
              <label for="regPassword2">确认密码</label>
              <input class="input" id="regPassword2" type="password" minlength="6" maxlength="20"
                     autocomplete="new-password" placeholder="再次输入密码" required>
            </div>
          </div>
          <label class="consent-row">
            <input id="regPrivacy" type="checkbox" required>
            <span>我同意平台为身份验证和密码找回保存加密后的手机号。</span>
          </label>
          <div class="row auth-actions">
            <button class="btn btn-primary" id="register-btn" type="submit">验证并注册</button>
            <button class="btn" id="register-cancel" type="button">返回登录</button>
          </div>
        </form>
      </div>
    </div>`;

  bindTopbar();
  document.getElementById('register-back').addEventListener('click', () => go('/'));
  document.getElementById('register-cancel').addEventListener('click', () => go('/'));
  document.getElementById('reg-send-code').addEventListener('click', onSendRegisterCode);
  document.getElementById('register-form').addEventListener('submit', onRegister);
}

async function onSendRegisterCode() {
  const userId = document.getElementById('regUserId').value.trim();
  const userName = document.getElementById('regUserName').value.trim();
  const phone = document.getElementById('regPhone').value.trim();
  if (!userId || !userName || !/^1[3-9]\d{9}$/.test(phone)) {
    setNotice('error', '请先填写正确的学号、姓名和手机号');
    return;
  }
  const btn = document.getElementById('reg-send-code');
  btn.disabled = true;
  btn.textContent = '发送中…';
  const r = await api.sendRegisterSmsCode(userId, userName, phone);
  if (!showSmsResult(r, '验证码已发送')) {
    btn.disabled = false;
    btn.textContent = '获取验证码';
    return;
  }
  startSmsCountdown(btn);
}

async function onRegister(e) {
  e.preventDefault();
  const userId = document.getElementById('regUserId').value.trim();
  const userName = document.getElementById('regUserName').value.trim();
  const phone = document.getElementById('regPhone').value.trim();
  const smsCode = document.getElementById('regSmsCode').value.trim();
  const password = document.getElementById('regPassword').value;
  const password2 = document.getElementById('regPassword2').value;
  const agreed = document.getElementById('regPrivacy').checked;

  if (password !== password2) {
    setNotice('error', '两次输入的密码不一致');
    return;
  }
  if (!/^\d{6}$/.test(smsCode)) {
    setNotice('error', '请输入 6 位短信验证码');
    return;
  }
  if (!agreed) {
    setNotice('error', '请先同意手机号使用说明');
    return;
  }

  const btn = document.getElementById('register-btn');
  btn.disabled = true;
  btn.textContent = '注册中…';
  const r = await api.register(userId, userName, phone, smsCode, password, agreed);
  if (!r.ok) {
    btn.disabled = false;
    btn.textContent = '验证并注册';
    setNotice('error', r.message);
    return;
  }
  await finishLogin(r.data);
}

function viewForgotPassword() {
  appEl.innerHTML = `
    ${topbarHtml()}
    <div class="wrap auth-page-wrap">
      <button class="btn auth-back" id="forgot-back" type="button">‹ 返回登录</button>
      <div class="card auth-form-card">
        <div class="auth-form-head">
          <p class="eyebrow">账号找回</p>
          <h2>重置密码</h2>
          <p class="sub">验证码会发送到账号已绑定的手机号。未绑定手机号的旧账号请联系管理员。</p>
        </div>
        <div id="notice-slot">${alertHtml()}</div>
        <form id="forgot-form">
          <div class="field">
            <label for="forgotUserId">学号</label>
            <input class="input" id="forgotUserId" maxlength="32" autocomplete="username"
                   placeholder="请输入学号" required>
          </div>
          <div class="field">
            <label for="forgotSmsCode">短信验证码</label>
            <div class="sms-code-row">
              <input class="input" id="forgotSmsCode" type="text" inputmode="numeric"
                     maxlength="6" autocomplete="one-time-code" placeholder="6 位验证码" required>
              <button class="btn" id="forgot-send-code" type="button">获取验证码</button>
            </div>
          </div>
          <div class="auth-grid">
            <div class="field">
              <label for="forgotPassword">新密码</label>
              <input class="input" id="forgotPassword" type="password" minlength="6" maxlength="20"
                     autocomplete="new-password" placeholder="6-20 位，含字母和数字" required>
            </div>
            <div class="field">
              <label for="forgotPassword2">确认新密码</label>
              <input class="input" id="forgotPassword2" type="password" minlength="6" maxlength="20"
                     autocomplete="new-password" placeholder="再次输入新密码" required>
            </div>
          </div>
          <div class="row auth-actions">
            <button class="btn btn-primary" id="forgot-btn" type="submit">重置密码</button>
            <button class="btn" id="forgot-cancel" type="button">返回登录</button>
          </div>
        </form>
      </div>
    </div>`;

  bindTopbar();
  document.getElementById('forgot-back').addEventListener('click', () => go('/'));
  document.getElementById('forgot-cancel').addEventListener('click', () => go('/'));
  document.getElementById('forgot-send-code').addEventListener('click', onSendResetCode);
  document.getElementById('forgot-form').addEventListener('submit', onResetPassword);
}

async function onSendResetCode() {
  const userId = document.getElementById('forgotUserId').value.trim();
  if (!userId) {
    setNotice('error', '请先输入学号');
    return;
  }
  const btn = document.getElementById('forgot-send-code');
  btn.disabled = true;
  btn.textContent = '发送中…';
  const r = await api.sendResetSmsCode(userId);
  if (!showSmsResult(r, '如账号已绑定手机号，验证码将发送到绑定手机')) {
    btn.disabled = false;
    btn.textContent = '获取验证码';
    return;
  }
  startSmsCountdown(btn);
}

async function onResetPassword(e) {
  e.preventDefault();
  const userId = document.getElementById('forgotUserId').value.trim();
  const smsCode = document.getElementById('forgotSmsCode').value.trim();
  const password = document.getElementById('forgotPassword').value;
  const password2 = document.getElementById('forgotPassword2').value;
  if (password !== password2) {
    setNotice('error', '两次输入的新密码不一致');
    return;
  }
  const btn = document.getElementById('forgot-btn');
  btn.disabled = true;
  btn.textContent = '重置中…';
  const r = await api.resetPassword(userId, smsCode, password);
  if (!r.ok) {
    btn.disabled = false;
    btn.textContent = '重置密码';
    setNotice('error', r.message);
    return;
  }
  state.notice = { type: 'ok', text: '密码已重置，请使用新密码登录' };
  go('/');
}

/* ---------- 四、页面：我的工单（列表） ---------- */

function viewMyOrders() {
  const q = state.orderQuery;

  appEl.innerHTML = `
    ${topbarHtml()}
    <div class="wrap">
      <div id="notice-slot">${alertHtml()}</div>
      ${needPwdAlert()}
      ${listHeadHtml()}

      <div class="home-dashboard">
        <section class="home-main-area" aria-label="我的工单">
          <div class="status-filter-bar">
            <span class="status-filter-label">状态</span>
            <div class="status-tabs" id="status-tabs" role="group" aria-label="按工单状态筛选">
              ${STATUS_FILTERS.map(o => `
                <button class="status-tab${o.value === q.status ? ' active' : ''}" type="button"
                        data-status="${o.value}" aria-pressed="${o.value === q.status ? 'true' : 'false'}">
                  ${o.label.replace('全部状态', '全部')}
                </button>`).join('')}
            </div>
            <select class="input status-select-hidden" id="f-status" aria-hidden="true" tabindex="-1">
              ${STATUS_FILTERS.map(o =>
                `<option value="${o.value}"${o.value === q.status ? ' selected' : ''}>${o.label}</option>`).join('')}
            </select>
          </div>

          <div class="filters">
            <input class="input" id="f-keyword" type="search" placeholder="搜索工单内容"
                   value="${esc(q.keyword)}" autocomplete="off">
            <button class="btn btn-primary" id="f-search" type="button">查询</button>
          </div>

          <div id="order-list"></div>
          <div id="order-pager"></div>
        </section>

        <aside class="home-insights" id="home-insights" aria-label="服务资讯">
          ${homeInsightsSkeletonHtml()}
        </aside>
      </div>
    </div>`;

  bindTopbar();
  bindPwdAlert();
  bindListHead();
  // 标题区的点线背景：必须在 innerHTML 写完之后调用，否则找不到 canvas。
  // 只挂「我的工单」这一页 —— 管理后台那种表格页挂动画会显得浮。
  mountPlexus();

  // 监听器绑在**容器**上，而不是每一张卡片上。
  // 好处：换页/改筛选时只替换容器里的 HTML，监听器还在，不用重新绑一遍。
  // 这叫"事件委托"—— 一个监听器管住里面所有（包括以后新增的）元素。
  document.getElementById('f-search').addEventListener('click', onSearch);
  document.getElementById('f-status').addEventListener('change', onSearch);
  document.getElementById('status-tabs').addEventListener('click', onStatusTabClick);
  document.getElementById('f-keyword').addEventListener('keydown', (e) => {
    // 手机上键盘的"搜索/回车"键应该等于点"查询"，否则按了没反应很奇怪
    if (e.key === 'Enter') { e.preventDefault(); onSearch(); }
  });
  document.getElementById('order-list').addEventListener('click', onOrderClick);
  document.getElementById('order-pager').addEventListener('click', onPagerClick);
  document.getElementById('home-insights').addEventListener('click', onHomeInfoClick);

  loadOrders();
  loadHomeInsights();
}

function homeInsightsSkeletonHtml() {
  return `
    <section class="home-info-card home-info-loading" aria-hidden="true">
      <div class="skeleton-line short"></div>
      <div class="skeleton-line medium"></div>
      <div class="skeleton-line"></div>
      <div class="skeleton-line"></div>
    </section>
    <section class="home-info-card home-info-loading" aria-hidden="true">
      <div class="skeleton-line short"></div>
      <div class="skeleton-line"></div>
      <div class="skeleton-line medium"></div>
      <div class="skeleton-line"></div>
    </section>`;
}

function homeApiResult(settled) {
  if (!settled || settled.status !== 'fulfilled') {
    return { ok: false, message: '网络连接失败，请稍后重试。' };
  }
  return settled.value || { ok: false, message: '暂时无法加载，请稍后重试。' };
}

function homeSummary(text, maxLength) {
  const value = String(text || '').replace(/\s+/g, ' ').trim();
  return value.length > maxLength ? value.slice(0, maxLength) + '…' : value;
}

async function loadHomeInsights() {
  const box = document.getElementById('home-insights');
  if (!box) return;
  box.innerHTML = homeInsightsSkeletonHtml();

  const settled = await Promise.allSettled([
    api.announcements(),
    api.faqs({})
  ]);
  const announcementResult = homeApiResult(settled[0]);
  const faqResult = homeApiResult(settled[1]);

  // 请求回来时页面可能已经切走；此时不能把内容写到一个已经脱离文档的容器里。
  const currentBox = document.getElementById('home-insights');
  if (!currentBox || currentBox !== box) return;

  const announcements = announcementResult.ok && Array.isArray(announcementResult.data)
    ? announcementResult.data
    : null;
  const faqs = faqResult.ok && Array.isArray(faqResult.data)
    ? faqResult.data.filter(f => f.status === 'PUBLISHED')
    : null;

  currentBox.innerHTML =
    homeAnnouncementsHtml(announcements, announcementResult) +
    homeFaqsHtml(faqs, faqResult);
}

function homeInfoStateHtml(message, retryLabel) {
  return `
    <div class="home-info-state" role="status">
      <span class="home-info-state-mark" aria-hidden="true">·</span>
      <p>${esc(message)}</p>
      <button class="home-info-link" type="button" data-home-retry>${retryLabel || '重试'}</button>
    </div>`;
}

function homeAnnouncementsHtml(list, result) {
  const rows = list === null
    ? homeInfoStateHtml(result.message || '服务公告暂时无法加载。')
    : (list.length
      ? list.slice(0, 2).map(function (item, index) {
        return `
          <button class="home-notice-item${index > 0 ? ' home-info-extra' : ''}" type="button"
                  data-home-notice-id="${esc(item.noticeId)}">
            <span class="home-notice-date">${esc(fmtTime(item.publishTime))}</span>
            <span class="home-notice-copy">
              <strong>${esc(item.title || '（无标题）')}</strong>
              <span>${esc(homeSummary(item.content, 62))}</span>
            </span>
            <span class="home-item-arrow" aria-hidden="true">›</span>
          </button>`;
      }).join('')
      : homeInfoStateHtml('当前没有服务公告。', '刷新'));

  return `
    <section class="home-info-card">
      <header class="home-info-head">
        <span class="home-info-icon notice" aria-hidden="true">播</span>
        <span class="home-info-heading">
          <strong>服务公告</strong>
          <small>${list ? '共 ' + list.length + ' 条' : '信息通知'}</small>
        </span>
        <button class="home-info-more" type="button" data-home-route="/announcements">全部</button>
      </header>
      <div class="home-info-list">${rows}</div>
    </section>`;
}

function homeFaqsHtml(list, result) {
  const rows = list === null
    ? homeInfoStateHtml(result.message || '推荐问题暂时无法加载。')
    : (list.length
      ? list.slice(0, 4).map(function (item, index) {
        return `
          <button class="home-faq-item${index > 2 ? ' home-info-extra' : ''}" type="button"
                  data-home-faq-id="${esc(item.faqId)}">
            <span class="home-faq-index">${String(index + 1).padStart(2, '0')}</span>
            <span class="home-faq-copy">
              <strong>${esc(item.question || '')}</strong>
              <small>${esc(item.category || '未分类')}</small>
            </span>
            <span class="home-item-arrow" aria-hidden="true">›</span>
          </button>`;
      }).join('')
      : homeInfoStateHtml('当前还没有推荐问题。', '刷新'));

  return `
    <section class="home-info-card">
      <header class="home-info-head">
        <span class="home-info-icon faq" aria-hidden="true">问</span>
        <span class="home-info-heading">
          <strong>推荐问题</strong>
          <small>${list ? '共 ' + list.length + ' 个' : '常见问题'}</small>
        </span>
        <button class="home-info-more" type="button" data-home-route="/faqs">全部</button>
      </header>
      <div class="home-info-list">${rows}</div>
    </section>`;
}

function onHomeInfoClick(e) {
  const retry = e.target.closest('[data-home-retry]');
  if (retry) {
    loadHomeInsights();
    return;
  }

  const route = e.target.closest('[data-home-route]');
  if (route) {
    go(route.dataset.homeRoute);
    return;
  }

  const notice = e.target.closest('[data-home-notice-id]');
  if (notice) {
    go('/announcements/' + encodeURIComponent(notice.dataset.homeNoticeId));
    return;
  }

  const faq = e.target.closest('[data-home-faq-id]');
  if (faq) go('/faqs/' + encodeURIComponent(faq.dataset.homeFaqId));
}

/** 点"查询"、改状态、敲回车 —— 都走这里。 */
async function onSearch() {
  // 换了筛选条件必须回到第 1 页：否则你人在第 5 页去搜一个只有 2 条结果的关键词，
  // 第 5 页是空的 —— 看起来就像"搜不到"。
  state.orderQuery.page = 1;
  state.orderQuery.status = document.getElementById('f-status').value;
  state.orderQuery.keyword = document.getElementById('f-keyword').value.trim();
  paintStatusTabs(state.orderQuery.status);
  setNotice(null, null);
  await loadOrders();
}

function onStatusTabClick(e) {
  const tab = e.target.closest('.status-tab');
  if (!tab) return;
  document.getElementById('f-status').value = tab.dataset.status || '';
  onSearch();
}

function paintStatusTabs(status) {
  document.querySelectorAll('#status-tabs .status-tab').forEach(function (tab) {
    const active = (tab.dataset.status || '') === (status || '');
    tab.classList.toggle('active', active);
    tab.setAttribute('aria-pressed', active ? 'true' : 'false');
  });
}

/** 拉一页工单回来，只重画"列表 + 分页"两块，**不重画整页**。 */
async function loadOrders() {
  const listBox = document.getElementById('order-list');
  const pagerBox = document.getElementById('order-pager');
  if (!listBox) return;

  const q = state.orderQuery;
  listBox.innerHTML = orderSkeletonHtml(3);
  if (pagerBox) pagerBox.innerHTML = '';

  const r = await api.workOrders({
    page: q.page,
    pageSize: q.pageSize,
    status: q.status,
    keyword: q.keyword
  });

  // Token 失效：api.js 已经把本地 Token 清掉了，这里退回登录页就行
  if (r.needLogin) {
    state.me = null;
    render();
    return;
  }

  if (!r.ok) {
    listBox.innerHTML = emptyStateHtml(
      '加载失败',
      esc(r.message),
      '<button class="btn" id="retry-orders" type="button">重新加载</button>',
      'error'
    );
    return;
  }

  const d = r.data || {};
  const list = d.list || [];
  // 这三样都以后端返回的为准，不用"我们请求的值"——
  // 后端会把 page 纠正成 ≥1、pageSize 夹到 1~50，拿请求值算总页数会算错。
  state.ordersTotal = d.total || 0;
  state.ordersPage = d.page || q.page;
  state.ordersPageSize = d.pageSize || q.pageSize;

  if (!list.length) {
    const filtered = !!(q.status || q.keyword);
    if (filtered) {
      listBox.innerHTML = emptyStateHtml(
        '没有找到匹配的工单',
        '换一个状态或关键词试试，也可以直接查看全部工单。',
        '<button class="btn" id="clear-order-filters" type="button">清除筛选</button>',
        'search'
      );
    } else if (state.me.role === 'STUDENT') {
      listBox.innerHTML = emptyStateHtml(
        '还没有提交过工单',
        '遇到学时录入、活动咨询或规则疑问时，可以从这里发起第一次沟通。',
        '<button class="btn btn-primary" id="empty-new-order" type="button">提交第一张工单</button>',
        'inbox'
      );
    } else {
      listBox.innerHTML = emptyStateHtml(
        '当前没有需要处理的工单',
        '新的工单进入服务台后会出现在这里。',
        '<button class="btn" id="retry-orders" type="button">刷新列表</button>',
        'check'
      );
    }
  } else {
    listBox.innerHTML = list.map(orderCardHtml).join('');
  }

  if (pagerBox) pagerBox.innerHTML = pagerHtml();
}

function orderSkeletonHtml(count) {
  return `
    <div class="skeleton-list" role="status" aria-live="polite">
      <span class="sr-only">正在加载工单…</span>
      ${Array.from({ length: count }, function () {
        return `
          <div class="card skeleton-card" aria-hidden="true">
            <div class="skeleton-line short"></div>
            <div class="skeleton-line"></div>
            <div class="skeleton-line medium"></div>
            <div class="skeleton-meta">
              <div class="skeleton-line short"></div>
              <div class="skeleton-line short"></div>
            </div>
          </div>`;
      }).join('')}
    </div>`;
}

function emptyStateHtml(title, text, actions, icon) {
  const marks = {
    search: '⌕',
    inbox: '＋',
    check: '✓',
    error: '!'
  };
  return `
    <div class="empty-state empty-state-${icon}">
      <span class="empty-state-icon" aria-hidden="true">${marks[icon] || '·'}</span>
      <h3>${title}</h3>
      <p>${text}</p>
      ${actions ? `<div class="empty-state-actions">${actions}</div>` : ''}
    </div>`;
}

/** 一张工单 = 一个可点的卡片。 */
function orderCardHtml(o) {
  const cls = BADGE_CLASS[o.status] || 'badge-closed';
  // 中文名优先用后端给的 statusLabel；自己那份只是"后端加了新状态而前端没跟上"时的兜底
  const label = o.statusLabel || STATUS_LABEL[o.status] || o.status || '';
  const unread = o.unreadCount > 0
    ? `<span class="dot" title="有 ${o.unreadCount} 条未读消息">${o.unreadCount}</span>`
    : '';
  return `
    <div class="card order-card" data-id="${esc(o.workOrderId)}" data-status="${esc(o.status)}">
      <div class="order-top">
        <span class="badge ${cls}">${esc(label)}</span>
        <span class="tag">${esc(o.category || '未分类')}</span>
        <span class="spacer"></span>
        ${unread}
      </div>
      <p class="order-content">${esc(o.content || '（无正文）')}</p>
      <div class="order-meta">
        <span>${esc(o.workOrderId)}</span>
        <span>·</span>
        <span>${fmtTime(o.createTime)}</span>
        <span class="spacer"></span>
        <span>处理人：${o.handlerName ? esc(o.handlerName) : '未接单'}</span>
      </div>
    </div>`;
}

/** "上一页 / 下一页"那一行。 */
function pagerHtml() {
  const total = state.ordersTotal;
  if (!total) return '';
  const page = state.ordersPage;
  const pages = Math.max(1, Math.ceil(total / state.ordersPageSize));
  return `
    <div class="pager">
      <button class="btn" id="pg-prev" type="button"${page <= 1 ? ' disabled' : ''}>上一页</button>
      <span class="muted">第 ${page} / ${pages} 页 · 共 ${total} 条</span>
      <button class="btn" id="pg-next" type="button"${page >= pages ? ' disabled' : ''}>下一页</button>
    </div>`;
}

function onOrderClick(e) {
  if (e.target.closest('#clear-order-filters')) {
    state.orderQuery.page = 1;
    state.orderQuery.status = '';
    state.orderQuery.keyword = '';
    const status = document.getElementById('f-status');
    const keyword = document.getElementById('f-keyword');
    if (status) status.value = '';
    if (keyword) keyword.value = '';
    paintStatusTabs('');
    loadOrders();
    return;
  }
  if (e.target.closest('#empty-new-order')) {
    go('/submit');
    return;
  }
  if (e.target.closest('#retry-orders')) {
    loadOrders();
    return;
  }
  const card = e.target.closest('.order-card');
  if (!card) return;
  // 从卡片上的 data-id 拼出详情页地址： #/orders/WO20261005000001
  go('/orders/' + card.dataset.id);
}

async function onPagerClick(e) {
  const btn = e.target.closest('button');
  if (!btn || btn.disabled) return;
  const q = state.orderQuery;
  if (btn.id === 'pg-prev') q.page = Math.max(1, state.ordersPage - 1);
  else if (btn.id === 'pg-next') q.page = state.ordersPage + 1;
  else return;
  setNotice(null, null);
  await loadOrders();
  // 翻页后滚回顶部，否则你会停在"上一页的底部"，看到的是新一页的中间
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

/* ---------- 五、页面：提交工单 ---------- */

/** 列表页顶部那一行：标题 + 「提交工单」入口（只有学生看得到这个按钮）。 */
function listHeadHtml() {
  const isStaff = state.me.role === 'STAFF';
  const isAdmin = state.me.role === 'ADMIN';
  // 学生端的小标题用英文（用户指定 SERVICE DESK）；客服/管理端仍是中文，
  // 要不要一起换英文等用户拍板 —— 三处同一位置混着语言会显得不一致。
  const eyebrow = isStaff ? '服务台工作区' : (isAdmin ? '全局管理视图' : 'SERVICE DESK');
  const subtitle = isStaff
    ? '接单、回复并跟进学生问题，让每一次处理都有迹可循。'
    : (isAdmin ? '查看全量工单，及时掌握服务进度与处理情况。'
      : '提交问题、跟进进度，也可以从这里继续未完成的沟通。');
  return `
    <div class="list-head">
      <div class="page-heading">
        <!-- 点线背景画在这块 canvas 上（绝对定位铺满标题块、z-index 0），
             标题文字 z-index 1 叠在上层 —— 所以线条看起来是从字底下穿过去的。
             动画逻辑在 js/plexus.js，这里只负责留个位置。 -->
        <canvas id="plexus-canvas" aria-hidden="true"></canvas>
        <p class="eyebrow">${eyebrow}</p>
        <h2 class="list-title">${esc(listTitle())}</h2>
        <p class="page-sub">${subtitle}</p>
      </div>
      ${state.me.role === 'STUDENT'
        ? '<button class="btn btn-primary new-order-btn" id="new-order" type="button"><span aria-hidden="true">＋</span> 提交工单</button>'
        : ''}
    </div>`;
}

function bindListHead() {
  const b = document.getElementById('new-order');
  if (b) b.addEventListener('click', () => go('/submit'));
}

/**
 * 提交工单页。同时兼「再次咨询」页 —— 两张表单**字段完全一样**，只有三处不同：
 *   ① 标题/说明/按钮文字；② 多一条"正在对老单 XXX 再次咨询"的提示条；
 *   ③ 提交时打**另一个接口**（re-consult 而不是 POST /api/work-orders）。
 *
 * 为什么不给「再次咨询」单独写一个页面：分类选择、字数计数、附件上传/删除这一整套
 * 提交页已经全有了（尤其是附件那部分，涉及 multipart 上传、进度、失败重试，复制一遍
 * 就是两处要同步维护的地方）。共用一张表单，"再次咨询"反而白得了附件能力。
 *
 * @param fromId 老单号。非空 = 再次咨询模式。
 *   ⚠️ 它来自**地址栏**（`#/submit/from/WO...`），不是内存里的变量。
 *      因为存内存里的话，用户按一下 F5 就丢了，填完描述点提交会被当成**普通新工单**
 *      建出来 —— 而且他不一定能察觉（新单看起来也正常）。放进地址就绕开了这个坑。
 */
function viewSubmit(fromId) {
  // 这张表单只给学生用。后端其实**没做角色校验**（任何登录用户都能调提交接口），
  // 所以这里挡的是"用户体验"，不是"安全" —— 干事/管理员填这张表没有业务意义。
  if (!state.me || state.me.role !== 'STUDENT') {
    setNotice('warn', '只有学生可以提交工单');
    go('/');
    return;
  }

  // 从地址推导出模式。普通提交时这里是空串，正好把上一次的"再次咨询"状态清掉。
  state.reConsultFrom = fromId || '';
  const from = state.reConsultFrom;

  ensureSubmitRequestId();

  appEl.innerHTML = `
    ${topbarHtml()}
    <div class="wrap">
      <div id="notice-slot">${alertHtml()}</div>
      ${from ? reConsultBannerHtml(from) : ''}

      <div class="card">
        <h2>${from ? '再次咨询' : '提交工单'}</h2>
        <p class="sub">${from
          ? '把这次的新问题说清楚。提交后会新建一张工单，老单不受任何影响。'
          : '把遇到的问题说清楚，干事会尽快处理。'}</p>

        <div class="field">
          <label>问题分类</label>
          <div class="chips" id="c-chips">
            ${CATEGORIES.map(c => `
              <button class="chip" type="button" data-cat="${esc(c)}"
                      aria-pressed="${state.submitCategory === c ? 'true' : 'false'}">${esc(c)}</button>`).join('')}
          </div>
        </div>

        <div class="field">
          <label for="c-content">问题描述</label>
          <textarea class="input" id="c-content" maxlength="500"
                    placeholder="例如：我参加了 XX 活动、签到也签了，但学时一直没有录入。">${esc(state.submitContent)}</textarea>
          <div class="counter" id="c-counter">0 / 500</div>
        </div>

        <div class="field">
          <label>附件（可选）</label>
          <div class="row">
            <button class="btn" id="c-pick" type="button">选择文件</button>
            <span class="muted">单个不超过 5 MB，可多选</span>
          </div>
          <input type="file" id="c-file" multiple hidden>
          <div class="file-list" id="c-files"></div>
        </div>

        <div class="row">
          <button class="btn btn-primary" id="c-submit" type="button">${from ? '提交再次咨询' : '提交工单'}</button>
          <button class="btn" id="c-cancel" type="button">${from ? '返回原工单' : '返回列表'}</button>
        </div>
      </div>
    </div>`;

  bindTopbar();
  document.getElementById('c-chips').addEventListener('click', onPickCategory);
  document.getElementById('c-pick').addEventListener('click',
    () => document.getElementById('c-file').click());
  document.getElementById('c-file').addEventListener('change', onPickFiles);
  document.getElementById('c-content').addEventListener('input', updateCounter);
  document.getElementById('c-submit').addEventListener('click', onSubmit);
  // 「返回」回哪儿也分模式：再次咨询是"从老单来的"，就该回老单，而不是掉回列表
  document.getElementById('c-cancel').addEventListener('click',
    () => go(from ? '/orders/' + encodeURIComponent(from) : '/'));
  document.getElementById('c-files').addEventListener('click', onFileListClick);

  updateCounter();
  renderSubmitFiles();
}

/**
 * 「再次咨询」页顶部的提示条。
 *
 * 为什么要专门解释一句：用户刚刚在**已完结**的老单页面上，一转眼又看到一张
 * 长得一模一样的表单，很容易以为"我是不是要在老单上改点什么"。这里必须讲清
 * "是新建一张、老单不动"，否则用户不敢填。
 */
function reConsultBannerHtml(from) {
  return `
    <div class="alert alert-info">
      这是对已完结的工单 <strong>${esc(from)}</strong> 的<b>再次咨询</b>：
      提交后会<strong>新建一张</strong>工单，老单保持原样、处理记录也不会被改动。
      新单会记着"来自 ${esc(from)}"，干事点开就能看到前因后果。
      <br>同一张老单 7 天内只能再次咨询一次；分类可以和老单不一样。
    </div>`;
}

/** 选分类：只翻这一小块的选中态，**不重画整页** —— 否则用户填了一半的描述会被清空。 */
function onPickCategory(e) {
  const btn = e.target.closest('.chip');
  if (!btn) return;
  state.submitCategory = btn.dataset.cat;
  document.querySelectorAll('#c-chips .chip').forEach(function (x) {
    x.setAttribute('aria-pressed', x === btn ? 'true' : 'false');
  });
  setNotice(null, null);
}

function updateCounter() {
  const ta = document.getElementById('c-content');
  const el = document.getElementById('c-counter');
  if (!ta || !el) return;
  state.submitContent = ta.value;   // 存一份，离开页面再回来能回填
  const n = ta.value.length;
  el.textContent = n + ' / 500';
  el.classList.toggle('over', n > 500);
}

/* ----- 附件：选一个传一个，传完把 id 攒起来，提交时一起带上 ----- */

async function onPickFiles(e) {
  const input = e.target;
  const files = Array.prototype.slice.call(input.files || []);
  // 立刻清空 input：不清的话，"把某个文件移除后再选同一个文件"不会触发 change
  //（浏览器认为 value 没变），用户会以为点了没反应。
  input.value = '';
  // 顺序传，别一次并发四五个：并发上传对后端和网络都不是好主意，也更容易触发限流
  for (const f of files) {
    await uploadOne(f);
  }
}

async function uploadOne(file) {
  // 本地先按 5 MB 拦一下，省一次网络往返。
  // 后缀白名单、文件头（Magic Number）一律交给后端 —— 前端再实现一遍只会和后端规则漂移。
  if (file.size > MAX_UPLOAD_BYTES) {
    setNotice('error', '「' + file.name + '」超过 5 MB，没有上传');
    return;
  }

  const item = { name: file.name, size: file.size, status: 'uploading', id: null, error: '' };
  state.submitFiles.push(item);
  renderSubmitFiles();

  const r = await api.uploadFile(file);
  if (r.needLogin) { state.me = null; render(); return; }

  if (r.ok) {
    item.status = 'ok';
    item.id = r.data.attachmentId;
  } else {
    item.status = 'failed';
    item.error = r.message;
  }
  renderSubmitFiles();
}

/** 只重画附件那一块。 */
function renderSubmitFiles() {
  const box = document.getElementById('c-files');
  if (!box) return;
  box.innerHTML = state.submitFiles.map(function (f, i) {
    return `
      <div class="file-item">
        <span class="name">${esc(f.name)}</span>
        <span class="size">${fmtSize(f.size)}</span>
        ${fileStatusHtml(f)}
        <button class="btn btn-ghost" type="button" data-del="${i}">移除</button>
      </div>`;
  }).join('');
}

function fileStatusHtml(f) {
  if (f.status === 'uploading') return '<span class="tag">上传中…</span>';
  if (f.status === 'ok') return '<span class="badge badge-replied">已上传</span>';
  // 失败原因放 title 里（鼠标悬停可见），不铺开显示是免得把这一行撑爆
  return '<span class="badge badge-pending" title="' + esc(f.error) + '">上传失败</span>';
}

function onFileListClick(e) {
  const btn = e.target.closest('[data-del]');
  if (!btn) return;
  // 只是从"这次提交"里去掉。服务端那个 TEMP 附件既然没被绑定，
  // 7 天后会被清理任务标记成 EXPIRED，不用前端操心。
  state.submitFiles.splice(Number(btn.dataset.del), 1);
  renderSubmitFiles();
}

/* ----- 提交 ----- */

async function onSubmit() {
  const category = state.submitCategory;
  const content = (document.getElementById('c-content').value || '').trim();

  // 这几条是"本地先拦"：后端同样会校验并返回中文消息。
  // 前端的价值只是让用户少等一次网络往返（不是安全边界）。
  if (!category) { setNotice('error', '请先选一个问题分类'); return; }
  if (!content) { setNotice('error', '问题描述不能为空'); return; }
  if (content.length > 500) { setNotice('error', '问题描述不能超过 500 字'); return; }
  if (state.submitFiles.some(f => f.status === 'uploading')) {
    setNotice('error', '还有附件正在上传，等它传完再提交');
    return;
  }

  const from = state.reConsultFrom || '';
  const btn = document.getElementById('c-submit');
  btn.disabled = true;
  btn.textContent = '提交中…';
  setNotice(null, null);

  // 两张表单共用这一份提交逻辑，只有"打哪个接口"不同。
  // ⚠️ 请求体字段其实一模一样（都是 category/content/requestId/attachmentIds），
  //    差别全在 URL 上：普通提交没有"老单"这个概念，再次咨询要把老单号带在路径里。
  const body = {
    category: category,
    content: content,
    requestId: state.submitRequestId,
    // 只带"传成功"的那些；上传中/失败的绝不带给后端（后端会判"附件不存在"而整单失败）
    attachmentIds: state.submitFiles.filter(f => f.status === 'ok').map(f => f.id)
  };
  const r = from
    ? await api.reConsult(from, body)
    : await api.submitWorkOrder(body);

  btn.disabled = false;
  btn.textContent = from ? '提交再次咨询' : '提交工单';

  if (r.needLogin) { state.me = null; render(); return; }

  if (!r.ok) {
    // ⚠️ 429 是「同一张老单 7 天内已经再次咨询过」，全站只有这一个接口会返回它。
    //    后端的原文是「该工单 7 天内已再次咨询过，请勿重复提交（RE_CONSULTATION_LIMIT）」——
    //    那句英文码对用户毫无意义，所以这里换成一句人话 + 明确的"过几天再来"。
    //    注意**不换幂等号**：这条根本没建单，用户过几天用同一个号再来也不会被挡。
    if (r.code === 429) {
      setNotice('warn', '这张工单 7 天内已经再次咨询过一次了，过几天再来吧。');
      return;
    }
    // 其余失败：这里**两种情况都不换 requestId**。原因是后端的行为（见 IdempotencyStore.run）：
    //   · 只有**成功**才把结果缓存起来（1 小时）；
    //   · **失败会主动把幂等锁放开**，所以沿用同一个号重试不会被 409 挡住。
    // 于是"一次表单填写 = 一个 requestId，一直用到提交成功为止"是安全的，
    // 而且正是我们想要的：万一第一次其实已经成功、只是响应没回来，再点一次会拿回
    // **同一张工单号**，而不是又建一张单。
    if (r.httpStatus === 0) {
      // 根本没连上（断网 / 服务没起）。这种情况请求**有可能已经到达服务器**了 ——
      // 此时更要保留 requestId，否则就真的会重复建单。
      setNotice('error', r.message + '。可以直接再点一次「' + (from ? '提交再次咨询' : '提交工单') + '」。');
    } else {
      // httpStatus 不是 0 = 服务器明确拒绝了（分类不合法 / 描述为空 / 附件已使用…）。
      // 这种情况一定没有建单，改完直接再提交即可。
      setNotice('error', r.message);
    }
    return;
  }

  // 成功：这一单结束了，换号 + 清表单
  state.submitRequestId = uuid();
  state.submitFiles = [];
  state.submitCategory = '';
  state.submitContent = '';
  // 顺便把列表的筛选重置回"第一页、不筛" —— 否则新单可能落在当前筛选条件之外，
  // 用户回到列表发现"没有我刚提交的那张"，会以为提交失败了。
  state.orderQuery = { page: 1, pageSize: 10, status: '', keyword: '' };

  const newId = (r.data || {}).workOrderId;

  // 再次咨询：直接把用户送到**新单的详情页** —— 他正关心"新单建出来没有"，
  // 让他自己回列表里找是多余的一步。老单号也要一起清掉，免得下次进提交页还带着它。
  if (from) {
    state.reConsultFrom = '';
    state.notice = { type: 'ok', text: '再次咨询已提交，新工单号 ' + newId };
    go(newId ? '/orders/' + encodeURIComponent(newId) : '/');
    return;
  }

  state.notice = { type: 'ok', text: '提交成功，工单号 ' + newId };
  go('/');
}

/* ---------- 六、页面：工单详情 ---------- */

async function viewOrderDetail(id) {
  appEl.innerHTML = `
    ${topbarHtml()}
    <div class="wrap detail-wrap">
      <div id="notice-slot">${alertHtml()}</div>
      <div class="row">
        <button class="btn" id="d-back" type="button">‹ 返回列表</button>
      </div>
      <div id="detail-box">${detailSkeletonHtml()}</div>
    </div>`;

  // 换了一张单就是换了一个场景：幂等号必须重来。否则后端会把"上一张单的追问结果"
  // 当成这次的结果直接返回（同一个 requestId 只认第一次执行）。
  state.followUpRequestId = '';
  state.replyRequestId = '';

  // 合并的中间状态同理 —— 这三个是"针对上一张单挑的主工单"，留着纯粹是误导
  state.mergeKeyword = '';
  state.mergeHits = [];
  state.mergePicked = null;

  bindTopbar();
  document.getElementById('d-back').addEventListener('click', () => go('/'));
  // 事件委托：详情每次加载都会换掉里面的 HTML，绑在容器上就不用重新绑一遍
  document.getElementById('detail-box').addEventListener('click', onDetailClick);

  await loadDetail(id);
}

async function loadDetail(id) {
  const box = document.getElementById('detail-box');
  if (!box) return;
  const detailWrap = document.querySelector('.detail-wrap');
  if (detailWrap) detailWrap.classList.remove('has-action-dock');

  const r = await api.workOrderDetail(id);
  if (r.needLogin) { state.me = null; render(); return; }

  if (!r.ok) {
    // 最常见的是 404（单号不存在）和 403（这不是你的单）—— 后端给的中文消息直接显示就行
    box.innerHTML = `
      <div class="card">
        <div class="empty">${esc(r.message)}</div>
        <div class="row">
          <button class="btn" id="d-back2" type="button">返回列表</button>
        </div>
      </div>`;
    const b = document.getElementById('d-back2');
    if (b) b.addEventListener('click', () => go('/'));
    return;
  }

  const d = r.data || {};
  state.detailId = d.workOrderId || id;
  state.detailVersion = d.version;          // 「确认解决」要把这个原样传回去
  document.title = state.detailId + ' · 第二课堂学时答疑';
  box.innerHTML = detailHtml(d);
  if (detailWrap) {
    detailWrap.classList.toggle('has-action-dock', !!document.getElementById('detail-action-dock'));
  }
  bindDetailActions();

  // 干事的回复卡上有「插入模板」下拉：这里异步去拉模板填进去。
  // 不 await —— 模板没到之前回复框照样能用，不该让它拖住整页渲染。
  loadDetailTemplates();

  // 渲染完再清红点（顺序无所谓，但放最后一行读起来最清楚：先让用户看到内容，顺手做后台清理）
  markDetailRead(id);
}

/**
 * 详情页渲染完之后要补的绑定。
 *
 * 为什么字数计数不能也塞进 onDetailClick（事件委托）里：
 *   委托靠的是 click 这类**冒泡事件**，而"边打字边数字数"靠的是 textarea 的 `input` 事件 ——
 *   那个只能直接绑在元素上。而且这个 textarea 每次重载详情都会被重建，
 *   所以只能在每次渲染之后重新绑一次（不能像 click 那样在容器上绑一次就完事）。
 */
function bindDetailActions() {
  bindCounter('d-follow', 'd-follow-count');             // 学生的追问框
  bindCounter('s-reply', 's-reply-count');               // 干事的回复框
  bindCounter('a-close-remark', 'a-close-remark-count'); // 管理员的关闭理由框
  bindTplPicker();                                       // 干事回复卡上的「插入模板」下拉
}

/**
 * 把「插入模板」下拉接上：选中一条 → 把它的内容填进回复框。
 *
 * 它只**填空**、不发送 —— 填完干事还能按自己的话改两笔，这比"点一下直接发出去"稳得多。
 * 注意它是个"动作"而不是"当前选择"：选完立刻把下拉复位（用一个技巧配合：
 * 复位后 value 变了也不会再触发 change，因为程序化赋值不触发事件）。
 */
function bindTplPicker() {
  const sel = document.getElementById('s-tpl');
  if (!sel) return;
  sel.addEventListener('change', onTplPick);
}

function onTplPick() {
  const sel = document.getElementById('s-tpl');
  const ta = document.getElementById('s-reply');
  if (!sel || !ta) return;
  const id = sel.value;
  sel.value = '';                     // 先复位：它是一个动作，不是"当前选中了哪条"
  if (!id) return;

  const t = (state.detailTpls || []).find(x => x.templateId === id);
  if (!t) return;

  // 回复框里已经有字了就先问一句，别把人写了一半的内容悄悄顶掉
  if (ta.value.trim() &&
      !window.confirm('回复框里已经有内容了，套用模板会把它顶掉，继续吗？')) {
    return;
  }
  ta.value = t.content;
  // ⚠️ 程序化赋值**不会**触发 `input` 事件 ⇒ 字数计数得手动补一次
  syncCounter('s-reply', 's-reply-count');
  ta.focus();
}

/**
 * 拉"可套用的模板"，填进回复卡上的下拉。
 *
 * ⚠️ **不传 status**：干事的请求里那个参数会被后端忽略（只有管理员传才算数），
 *    默认返回的就是"启用中的、按 sortNum 升序"—— 正好是要的。
 * ⚠️ 这是异步的：回来时这张卡可能已经被重画或切走了，所以**每次重新取元素、判空**，
 *    绝不在发起请求前把节点存下来。
 * ⚠️ 拿不到模板**不该影响回复**：下拉置灰说一句"暂无可用模板"就完事，回复框照常用。
 */
async function loadDetailTemplates() {
  state.detailTpls = [];
  const r = await api.templates({});
  if (r.needLogin) { state.me = null; render(); return; }

  const sel = document.getElementById('s-tpl');
  if (!sel) return;                   // 卡片已经不在了（切走了 / 这单轮不到干事回）

  const list = (r.ok && r.data) ? r.data : [];
  if (!list.length) {
    sel.innerHTML = '<option value="">暂无可用模板</option>';
    sel.disabled = true;
    return;
  }
  state.detailTpls = list;
  sel.innerHTML = '<option value="">插入模板…</option>' + list.map(t =>
    `<option value="${esc(t.templateId)}">${esc(t.title)}（${esc(t.category)}）</option>`).join('');
  sel.disabled = false;
}

/**
 * 把"边打字边数字数"绑到一个输入框上。
 *
 * 为什么单独抽成一个函数：详情页现在有**两个**这样的框（学生追问、干事回复），
 * 逻辑一模一样，写两遍就是两处要同步维护的地方。
 * 抽出来后，将来再加第三个框也只需要多调一行。
 */
function bindCounter(inputId, counterId) {
  const ta = document.getElementById(inputId);
  if (!ta) return;
  ta.addEventListener('input', () => syncCounter(inputId, counterId));
}

/**
 * 把字数计数刷成输入框当前的长度。
 *
 * ⚠️ **程序化改 value 不会触发 `input` 事件**（`ta.value = '...'` 是静默的），
 *    所以凡是"代码替用户填内容"的地方（套用模板、编辑模式回填），
 *    都必须自己调一次这个函数 —— 否则字数停在上一次的值，看起来像计数坏了。
 */
function syncCounter(inputId, counterId) {
  const ta = document.getElementById(inputId);
  const counter = document.getElementById(counterId);
  if (ta && counter) counter.textContent = String(ta.value.length);
}

/**
 * 进了详情页就等于"看过了" —— 让后端把这张工单的未读一次清掉。
 *
 * 后端这个接口一次做两件事（同一个事务）：
 *   ① 该工单的**消息**标已读  → 清掉工单列表卡片上的红点
 *   ② 该工单相关的**通知**标已读 → 清掉顶部「消息」红点里的那几条
 *
 * 为什么不能直接把 state.unreadCount 清零：它清的是"这张工单的"通知，
 * 别的工单的通知还是未读的。到底清掉了几条我们并不知道，
 * 所以老老实实重新问后端要一个准数（refreshUnread）。
 */
async function markDetailRead(id) {
  const r = await api.markWorkOrderMessagesRead(id);
  // 静默失败：红点没清掉不影响用户看工单，这时候弹个错误框反而莫名其妙
  if (!r.ok) return;
  await refreshUnread();
}

function detailSkeletonHtml() {
  return `
    <div class="detail-skeleton" role="status" aria-live="polite">
      <span class="sr-only">正在加载工单详情…</span>
      <div class="card skeleton-card" aria-hidden="true">
        <div class="skeleton-line short"></div>
        <div class="skeleton-line"></div>
        <div class="skeleton-steps">
          <div class="skeleton-dot"></div>
          <div class="skeleton-line"></div>
          <div class="skeleton-dot"></div>
          <div class="skeleton-line"></div>
          <div class="skeleton-dot"></div>
        </div>
      </div>
      <div class="card skeleton-card" aria-hidden="true">
        <div class="skeleton-line medium"></div>
        <div class="skeleton-line"></div>
        <div class="skeleton-line"></div>
        <div class="skeleton-line medium"></div>
      </div>
      <div class="card skeleton-card" aria-hidden="true">
        <div class="skeleton-line short"></div>
        <div class="skeleton-line"></div>
        <div class="skeleton-line medium"></div>
      </div>
    </div>`;
}

function detailDockHtml(d) {
  if (!state.me) return '';
  const role = state.me.role;
  let copy = '';
  let actions = '';

  if (role === 'STUDENT' && d.status === 'REPLIED') {
    copy = '<strong>问题已得到回复</strong><small>确认解决，或继续追问</small>';
    actions = `
      <button class="btn" type="button" data-scroll-to="#d-follow">继续追问</button>
      <button class="btn btn-primary" id="d-confirm-dock" type="button">确认解决</button>`;
  } else if (role === 'STUDENT' && d.status === 'CLOSED') {
    copy = '<strong>工单已关闭</strong><small>问题再次出现时可以继续咨询</small>';
    actions = '<button class="btn btn-primary" type="button" data-scroll-to="#d-reconsult">再次咨询</button>';
  } else if (role === 'STAFF' && d.status === 'PENDING' && !d.handlerId) {
    copy = '<strong>这张工单还在等待受理</strong><small>接单后由你继续跟进</small>';
    actions = '<button class="btn btn-primary" type="button" data-scroll-to="#s-accept">接单处理</button>';
  } else if (role === 'STAFF' && d.handlerId === state.me.userId &&
             (d.status === 'PROCESSING' || d.status === 'REPLIED')) {
    copy = `<strong>${d.status === 'REPLIED' ? '已回复学生' : '工单处理中'}</strong><small>需要时可以直接补充回复</small>`;
    actions = '<button class="btn btn-primary" type="button" data-scroll-to="#s-reply">立即回复</button>';
  } else if (role === 'ADMIN' && d.status === 'PENDING') {
    copy = '<strong>待接单工单</strong><small>如有必要可填写原因并强制关闭</small>';
    actions = '<button class="btn" type="button" data-scroll-to="#a-close-remark">前往处理</button>';
  }

  if (!actions) return '';
  return `
    <div class="mobile-action-dock" id="detail-action-dock" aria-label="工单快捷操作">
      <div class="dock-copy">${copy}</div>
      <div class="dock-actions">${actions}</div>
    </div>`;
}

/** 整套详情页面。 */
function detailHtml(d) {
  const cls = BADGE_CLASS[d.status] || 'badge-closed';
  const label = d.statusLabel || STATUS_LABEL[d.status] || d.status || '';

  return `
    <div class="card">
      <div class="row">
        <span class="badge ${cls}">${esc(label)}</span>
        <span class="tag">${esc(d.category || '未分类')}</span>
        <span class="spacer"></span>
        <span class="muted">${esc(d.workOrderId)}</span>
      </div>
      ${stepBarHtml(d.status)}
      ${mergedAlertHtml(d)}
      ${sourceAlertHtml(d)}
    </div>

    <div class="card">
      <h2>工单信息</h2>
      <ul class="kv">
        <li><span class="k">提交人</span><span class="v">${esc(d.userName || '')}</span></li>
        <li><span class="k">处理人</span><span class="v">${d.handlerName ? esc(d.handlerName) : '还没人接单'}</span></li>
        <li><span class="k">提交时间</span><span class="v">${fmtFull(d.createTime)}</span></li>
        <li><span class="k">接单时间</span><span class="v">${d.acceptTime ? fmtFull(d.acceptTime) : '—'}</span></li>
        <li><span class="k">回复时间</span><span class="v">${d.replyTime ? fmtFull(d.replyTime) : '—'}</span></li>
        <li><span class="k">完成时间</span><span class="v">${d.finishTime ? fmtFull(d.finishTime) : '—'}</span></li>
      </ul>
    </div>

    <div class="card">
      <h2>问题描述</h2>
      <p class="content-text">${esc(d.content || '（无）')}</p>
      ${attachmentsHtml(d.attachments)}
    </div>

    <div class="card">
      <h2>处理记录</h2>
      ${timelineHtml(d.messages)}
    </div>

    ${actionCardHtml(d)}
    ${detailDockHtml(d)}`;
}

/**
 * 详情页底部那块"我现在能做什么" —— **总入口**。
 *
 * 同一个页面，不同角色看到的是**完全不同**的一组按钮：
 *   · 学生           → 「确认解决」/「继续追问」/「再次咨询」
 *   · 干事           → 「接单」/「回复」/「完结」/「退回」
 *   · 管理员         → 「强制关闭」（⑩ 第十步）
 *   · 干事 + 管理员  → 「合并重复工单」（⑩ 第十一步）
 * 四个函数各自按角色返回，谁的角色不对就返回空串，拼起来还是同一块位置。
 *
 * 为什么不合并成一个函数里写 if-else：
 *   各自要判断的东西完全不一样（学生看"状态是不是 REPLIED"，
 *   干事看"单子是不是我的 + 状态允许干什么"，管理员只看"是不是还没人接"，
 *   合并看"这张单能不能当子单 + 我有没有资格动它"），
 *   硬揉在一起会变成一团互相穿插的条件，看着就头疼。
 *   分开成四个函数，各自读起来是一条直线。
 *
 * ⚠️ 前三个函数都**先按角色拦一道**（角色不对直接返回空串）；第四个（合并）是**例外** ——
 *    它**同时服务干事和管理员**（契约就是"干事/管理员"），所以在里面按
 *    "是不是 STAFF 或 ADMIN"合着判一次。这也是它不能塞进 staffActionCardHtml 的原因：
 *    那个函数第二行就 `role !== 'STAFF'` 返回了，管理员永远走不到。
 *
 * ⚠️ "待接单且没人接"这个状态下，干事想的是「接单」、管理员想的是「这张单该不该存在」，
 *    业务含义完全不同 —— 所以前三个函数**绝不能互相看见**。
 *    「合并」则是两边共有的动作，对两种角色都出现。
 */
function actionCardHtml(d) {
  return studentActionCardHtml(d) + staffActionCardHtml(d) + adminActionCardHtml(d) + mergeCardHtml(d);
}

/**
 * 学生看到的操作区。
 *
 * 分两种情况：
 *   · **已完结(CLOSED)** → 「再次咨询」（⑩ 第九步）
 *   · **已回复(REPLIED)** → 「确认解决」+「继续追问」
 *   其余状态（待接单 / 处理中 / 已合并）整块不出现 —— 那时候学生该做的是等，不是操作。
 *
 * ⚠️ 前端"只认 REPLIED"这件事，**现在和后端是一致的**：
 *    A2 欠账（2026-10-10）已经把后端收严为"前置状态必须 = REPLIED" ——
 *    在那之前后端放行 PROCESSING，只有前端在拦，属于"前端比后端严"的临时状态。
 *    两边口径现已统一；前端这段判断仍然要保留，因为它还负责
 *    "REPLIED 时才多给『确认解决』和『继续追问』两个按钮"的排版。
 */
function studentActionCardHtml(d) {
  // 干事/管理员不该看到学生的按钮（管理员也能调这两个接口，但那是管理动作，
  // 不属于"我的问题解决了吗"这个语境）
  if (!state.me || state.me.role !== 'STUDENT') return '';

  // ---- 已完结：只剩「再次咨询」这一条路 ----
  // 后端要求老单必须是 CLOSED（否则 409），所以这里也只在这个状态给入口。
  if (d.status === 'CLOSED') {
    return `
      <div class="card">
        <h2>问题还没解决？</h2>
        <p class="sub">这张工单已经结束了。如果又出了新问题，可以基于它再提一张新单 ——
          新单会记着「来自 ${esc(d.workOrderId)}」，干事打开就能看到前因后果，
          不用你从头再讲一遍。</p>
        <div class="row">
          <button class="btn btn-primary" id="d-reconsult" type="button">再次咨询</button>
        </div>
        <p class="hint">同一张工单 7 天内只能再次咨询一次。</p>
      </div>`;
  }

  if (d.status !== 'REPLIED') return '';
  return `
    <div class="card">
      <h2>问题解决了吗？</h2>
      <p class="sub">答复满意就点「确认解决」把工单关掉；还有疑问就在下面继续追问 ——
        追问后工单会回到「处理中」，处理人会重新给你答复。</p>

      <div class="row">
        <button class="btn btn-primary" id="d-confirm" type="button">确认解决</button>
      </div>

      <div class="field follow-up-field">
        <label for="d-follow">继续追问</label>
        <textarea class="input follow-up-input" id="d-follow" maxlength="1000"
                  placeholder="说说还有哪里没解决…"></textarea>
        <div class="counter"><span id="d-follow-count">0</span> / 1000</div>
      </div>
      <div class="row">
        <button class="btn" id="d-follow-btn" type="button">发送追问</button>
      </div>
    </div>`;
}

/**
 * 干事看到的操作区（⑩ 第八步）。
 *
 * 按"这张单现在处于什么阶段"给出**唯一合理**的那几个按钮，别的一律不显示 ——
 * 前端少显示一个按钮，用户就少一次"点了报错"的挫败感。
 *
 *   ┌─────────────┬──────────────────────────────────────────────┐
 *   │ 待接单 PENDING （还没人接）│ 「接单」                            │
 *   │ 处理中 PROCESSING（是我的单）│ 「回复」+「完结」+「退回」        │
 *   │ 已回复 REPLIED（是我的单）  │ 「回复」（补充回复）+「完结」      │
 *   │ 已关闭 / 已合并             │ 什么都不显示（单子结束了）        │
 *   └─────────────┴──────────────────────────────────────────────┘
 *
 * ⚠️ 为什么 REPLIED 还让干事回复（按说该等学生确认了）：
 *    这不是我放水，是后端**故意**支持的 —— 源码里那条 UPDATE 写的是
 *    `WHERE status IN ('PROCESSING','REPLIED')`，注释原话是
 *    「干事对同一条单补充回复时，before 其实已经是 REPLIED 了」。
 *    我原以为是文档对、实现对，去读了源码才发现是**文档没跟上**。
 *    实际业务里确实有这场景：答复里落下一条信息，再补一句，不必等学生追问。
 *
 * ⚠️ 「退回」只在 PROCESSING 出现，REPLIED 没有：
 *    后端要求 status = 'PROCESSING' 才能退回（从 REPLIED 退回报
 *    「只有处理中的工单才能退回」）。因为 REPLIED 意味着答复已经给学生了，
 *    这时候把单子扔回池子、把答复晾在那儿，学生只会更懵。
 *
 * ⚠️ 管理员这一轮不显示这些按钮：
 *    后端允许 ADMIN 调这几个接口（且不受"必须是负责人"的限制），
 *    但管理员的动作（强制关闭 / 合并工单）是另一套，留到下一轮单独做。
 *    这里只放开 STAFF —— 免得管理员点出"退回"却不知道该退回给谁。
 */
function staffActionCardHtml(d) {
  if (!state.me || state.me.role !== 'STAFF') return '';

  // ---- 情况一：待接单池里的单，谁都能接 ----
  // ⚠️ 必须再确认一次"没人接"（handlerId 为空）。后端的接单 SQL 条件是
  //    `status='PENDING' AND handler_id IS NULL`，如果这里只看 status
  //    就给按钮，碰上"状态是 PENDING 但已经有人接了"的脏数据就会白点一下报 409。
  if (d.status === 'PENDING') {
    if (d.handlerId) return '';
    return `
      <div class="card">
        <h2>这张单还没人接</h2>
        <p class="sub">接了之后你就是它的负责人，学生的追问会推到你这里，记得及时答复。
          （同一时间如果有别人也在接，只有一个人能接到，另一个会收到"已被受理"的提示。）</p>
        <div class="row">
          <button class="btn btn-primary" id="s-accept" type="button">接单</button>
        </div>
      </div>`;
  }

  // ---- 下面都是"我的单"才有资格做的事 ----
  // 措辞说明：前端要放行的是"真正的负责人"。用 userId 比姓名可靠 ——
  // 姓名可能重名，工号不会。
  const mine = !!d.handlerId && d.handlerId === state.me.userId;
  if (!mine) return '';

  if (d.status === 'PROCESSING' || d.status === 'REPLIED') {
    return `
      <div class="card">
        <h2>${d.status === 'REPLIED' ? '补充回复' : '回复学生'}</h2>
        <p class="sub">${d.status === 'REPLIED'
          ? '已经答复过了。如果还有要补充的，直接在这里接着写 —— 不用等学生追问。'
          : '把处理结果说清楚：能办的说清什么时候办好，不能办的说明原因。'}</p>

        <div class="field">
          <label for="s-reply">回复内容</label>
          <!-- 「插入模板」：选项由 loadDetailTemplates() 异步填（刚进页面时是空的）。
               它的作用只是**把内容填进下面的回复框**，不会直接发送 —— 填完还能改。 -->
          <div class="row tpl-row">
            <select class="input" id="s-tpl" disabled>
              <option value="">模板加载中…</option>
            </select>
            <button class="btn ai-btn" id="s-ai-btn" type="button">AI 建议</button>
          </div>
          <textarea class="input s-reply-input" id="s-reply" maxlength="1000"
                    placeholder="例如：学时将在 3 个工作日内录入，请留意。"></textarea>
          <div class="counter"><span id="s-reply-count">0</span> / 1000</div>
          <!-- AI 建议的结果说明（来源、耗时、失败原因）。默认隐藏，点按钮后才出现。
               注意：它只是**说明**，正文直接填进上面的回复框，干事可以随便改。 -->
          <div class="ai-panel" id="s-ai-panel" hidden></div>
        </div>
        <div class="row">
          <button class="btn btn-primary" id="s-reply-btn" type="button">发送回复</button>
        </div>

        <div class="staff-ops">
          <div class="ops-title">其他处理方式</div>

          <div class="row">
            <button class="btn" id="s-close" type="button">直接完结</button>
          </div>

          ${d.status === 'PROCESSING' ? `
            <div class="field staff-return-field">
              <label for="s-return-remark">退回理由</label>
              <div class="row">
                <input class="input" id="s-return-remark" maxlength="200"
                       placeholder="例如：该问题需要其他分类处理">
                <button class="btn" id="s-return" type="button">退回待接单池</button>
              </div>
            </div>
            <p class="hint">退回后工单回到「待接单」，负责人清空，任何干事都能重新接；
              学生会在时间线里看到这条理由 —— 所以要写清楚。</p>` : ''}
        </div>
      </div>`;
  }

  // CLOSED / MERGED：单子已经结束了，干事的活干完了，一个按钮都不给
  return '';
}

/**
 * 管理员看到的操作区（⑩ 第十步）。
 *
 * 这里**只有一件事**：强制关闭「待接单」的工单（admin-close）。
 * ⚠️ 合并工单（merge）**不在这里** —— 它是干事和管理员共用的动作，单独放在 `mergeCardHtml`。
 *    （塞进本函数会被开头那句 `role !== 'ADMIN'` 挡掉，干事永远看不到。）
 *
 *   ┌──────────────────────┬──────────────────────┐
 *   │ 待接单 PENDING        │ 「强制关闭」+ 必填理由 │
 *   │ 处理中 / 已回复       │ 什么都不显示           │
 *   │ 已关闭 / 已合并       │ 什么都不显示           │
 *   └──────────────────────┴──────────────────────┘
 *
 * ⚠️ 为什么只在 PENDING 给按钮（后端也是这么卡的）：
 *    管理员这个动作解决的是"这张单根本不该存在"（重复提问 / 与第二课堂无关 / 学生误操作）。
 *    这种单子最典型的样子就是**在待接单池里躺着没人管**。一旦有干事接了单、
 *    说明它是真实问题，该走正常流程，轮不到管理员一刀切掉。
 *    实测：对 PROCESSING / REPLIED / CLOSED 调 admin-close 一律 409
 *    「管理员只能关闭待接单的工单」。
 *
 * ⚠️ 有个中间状态要注意：**被干事退回的单**
 *    （return 之后状态回到 PENDING、handler 被清空）—— 它**也**是 PENDING，
 *    管理员照样能强制关闭。这是对的：它确实又回到"没人管"的状态了。
 *
 * ⚠️ 为什么输入框 maxlength 写 200 而不是 500：
 *    理由最终落到 t_status_log.remark，那一列是 VARCHAR(500)。
 *    实测传 660 个字直接 500「数据库操作失败」—— 整条事务回滚，连状态日志都没写。
 *    也就是说后端**没有**长度校验，兜底只能在前端做。
 *    压到 200 是留余量：真需要写 400 字理由的场景基本不存在。
 */
function adminActionCardHtml(d) {
  if (!state.me || state.me.role !== 'ADMIN') return '';
  if (d.status !== 'PENDING') return '';

  return `
    <div class="card">
      <h2>管理员操作</h2>
      <p class="sub">这张单还在等干事接。如果它本身不该存在（重复提问、与第二课堂无关、
        明显是误操作），可以直接强制关闭，不用等有人接单再关。</p>

      <div class="field">
        <label for="a-close-remark">关闭原因（必填）</label>
        <textarea class="input a-close-input" id="a-close-remark" maxlength="200"
                  placeholder="例如：无效咨询，非第二课堂相关问题"></textarea>
        <div class="counter"><span id="a-close-remark-count">0</span> / 200</div>
      </div>
      <div class="row">
        <button class="btn btn-danger" id="a-close-btn" type="button">强制关闭</button>
      </div>
      <p class="hint">理由会作为系统消息写进工单时间线，学生和干事都看得到 —— 所以要写清楚。
        强制关闭不可撤销：学生不能再追问、也不能确认解决，只能重新提交一张新单。</p>
    </div>`;
}

/**
 * 合并重复工单（⑩ 第十一步）—— 干事和管理员**共用**的一块操作区。
 *
 * 【它解决什么问题】
 *   同一个问题被两个学生各问了一遍，或者一个学生手滑提交了两次，
 *   就会躺着两张内容几乎一样的单。合并就是把其中一张（"子单" = **当前这张**）
 *   并进另一张（"主单"），子单从此变成「已合并」的只读死单 ——
 *   只在它自己的时间线上留一句"已被合并至 X"，学生和后续讨论都转到主单那边。
 *
 * 【什么时候出现】（跟后端校验一一对应，前端提前拦是为了少报无谓的错）
 *   · 角色必须是 干事 或 管理员（学生不该看到）；
 *   · 当前这张单得**还能当子单** —— 状态是 待接单 / 处理中 / 已回复 之一。
 *     已关闭、已合并都是终态，后端会直接 409，所以整块不渲染；
 *   · 干事还得"有资格动这张单"：它是我的单，或者它还躺在待接单池里没人接。
 *     这条跟后端第 6 步的越权校验是**同一套条件**；管理员不受限。
 *
 * 【怎么选主单】就地搜索，两步走：
 *   输关键词 → 列表接口拿几条候选 → 点"选它当主工单" → 再点"确认合并"。
 *   为什么复用列表接口而不另开一个：列表接口按角色返回的范围**正好**就是能合并的范围
 *   （干事 = 自己负责的 + 待接单池；管理员 = 全部），不需要再写一套权限逻辑。
 *
 * ⚠️ 三处**故意的克制**（都是"少做一点反而更对"）：
 *   1. **不传 requestId** —— merge 没有幂等包装，塞了也会被完全忽略（见 api.js 的注释）；
 *   2. **自己不出现在搜索结果里** —— 后端会 400"不能把工单合并到自己"，
 *      与其让用户点了才知道，不如压根不给这一条；
 *   3. **主单是已完结的单时只提醒、不拦** —— 契约只禁止"并入一张已被合并的单"，
 *      并没有禁止"并入一张已办结的单"。前端按现状放行，但把后果说明白
 *      （学生会被引导去看一张已经结束的单，后续不会再有人处理）。
 */
function mergeCardHtml(d) {
  const role = state.me && state.me.role;
  if (role !== 'STAFF' && role !== 'ADMIN') return '';

  // 当前单能不能当子单：终态（已关闭 / 已合并）不行
  if (d.status !== 'PENDING' && d.status !== 'PROCESSING' && d.status !== 'REPLIED') return '';

  // 干事：只能动"自己的"或"还没人接的"
  if (role === 'STAFF') {
    const canTouch = (d.handlerId && d.handlerId === state.me.userId) || d.status === 'PENDING';
    if (!canTouch) return '';
  }

  const picked = state.mergePicked;

  return `
    <div class="card">
      <h2>合并重复工单</h2>
      <p class="sub">如果这张单和另一张单问的是同一件事，可以把<strong>这张</strong>并进那张。
        方向别选反：合并之后"这张"变成只读的「已合并」，讨论都转到主工单那边继续。</p>

      <div class="field">
        <label for="m-keyword">① 找主工单</label>
        <div class="row">
          <input class="input" id="m-keyword" type="search" maxlength="50"
                 placeholder="输入关键词，搜主工单的内容" value="${esc(state.mergeKeyword)}"
                 autocomplete="off">
          <button class="btn" id="m-search" type="button">搜索</button>
        </div>
      </div>

      <div id="m-results">${mergeResultsHtml()}</div>

      <div class="row">
        <button class="btn btn-danger" id="m-merge-btn" type="button"${picked ? '' : ' disabled'}>
          确认合并${picked ? '到 ' + esc(picked.workOrderId) : ''}
        </button>
      </div>
      <p class="hint">合并不可撤销：这张单立刻变成只读，学生不能再追问、也不能确认解决。
        万一方向选反了（该留的那张被并走了），只能重新提交一张新单。</p>
    </div>`;
}

/**
 * 合并卡片里"已选主工单 + 搜索结果"那一块。
 *
 * 单独抽成函数的原因：它要被**局部重绘**。点"选它"或"取消选择"时只换这一块，
 * 不重新拉整个详情 —— 重拉会白闪一下，还可能把刚敲的搜索词和已选状态搅乱。
 * （同理，"确认合并"失败后也只重绘这块，见 paintMergeResults。）
 */
function mergeResultsHtml() {
  const out = [];
  const p = state.mergePicked;

  // 已选中的那张，独立于搜索结果显示 —— 换个关键词重搜也不该把已选的丢掉
  if (p) {
    const done = p.status === 'CLOSED';
    out.push(`
      <div class="alert ${done ? 'alert-warn' : 'alert-info'} m-picked">
        <div>已选主工单：<strong>${esc(p.workOrderId)}</strong>
          （${esc(p.statusLabel || STATUS_LABEL[p.status] || p.status)}）
          <button class="lnk" id="m-unpick" type="button">取消选择</button></div>
        ${done ? '<div class="m-warn">⚠️ 这张主工单<strong>已经完结</strong>了。合并后本单的学生会被引导去看一张'
                 + '已经结束的单，后续不会再有人处理 —— 确认这是你要的结果。</div>' : ''}
      </div>`);
  }

  const hits = state.mergeHits;
  if (!hits.length) {
    // 只有"搜过但没结果"才提示，一进页面（还没搜）不显示任何东西
    if (state.mergeKeyword && state.mergeKeyword.trim()) {
      out.push('<div class="empty">没搜到可以合并的工单，换个关键词试试。</div>');
    }
    return out.join('');
  }

  out.push('<div class="m-hits">' + hits.map((h) => {
    const cls = BADGE_CLASS[h.status] || 'badge-closed';
    const label = h.statusLabel || STATUS_LABEL[h.status] || h.status || '';
    // 已被合并的单不能当主单（后端会 409）—— 直接禁掉按钮，别让人白点一下
    const dead = h.status === 'MERGED';
    const isPicked = !!p && p.workOrderId === h.workOrderId;

    let action;
    if (dead) {
      action = '<span class="hint">这张单已经被合并过了，不能作为主工单。</span>';
    } else if (isPicked) {
      action = '<span class="hint">✓ 已选中</span>';
    } else {
      action = `<button class="btn" data-pick="${esc(h.workOrderId)}" type="button">选它当主工单</button>`;
    }

    return `
      <div class="m-hit${isPicked ? ' picked' : ''}">
        <div class="m-hit-top">
          <span class="badge ${cls}">${esc(label)}</span>
          <span class="muted">${esc(h.workOrderId)}</span>
        </div>
        <p class="m-hit-text">${esc(h.content || '（无正文）')}</p>
        <div class="row">${action}</div>
      </div>`;
  }).join('') + '</div>');

  return out.join('');
}

/** 只重画合并卡片里的候选区（不动输入框，免得丢焦点、丢刚敲的字）。 */
function paintMergeResults() {
  const box = document.getElementById('m-results');
  if (box) box.innerHTML = mergeResultsHtml();

  // 选中状态变了，"确认合并"按钮的可用状态和文案也要跟着走
  const btn = document.getElementById('m-merge-btn');
  if (btn) {
    const p = state.mergePicked;
    btn.disabled = !p;
    btn.textContent = p ? '确认合并到 ' + p.workOrderId : '确认合并';
  }
}

/**
 * 进度条：待接单 → 处理中 → 已回复 → 已关闭。
 * 走过的那几步变绿、当前那步高亮、还没到的灰着。
 */
function stepBarHtml(status) {
  // 被合并的单走的是"子单 → 主单"另一条路，这条四步流程对它没有意义
  if (status === 'MERGED') return '';
  const idx = FLOW.indexOf(status);
  if (idx < 0) return '';
  return `
    <div class="steps">
      ${FLOW.map(function (s, i) {
        const c = i < idx ? 'done' : (i === idx ? 'now' : 'todo');
        return `<span class="step ${c}"><span class="step-dot"></span>${STATUS_LABEL[s]}</span>`;
      }).join('<span class="step-line"></span>')}
    </div>`;
}

/** 被合并的提示：把主工单号亮出来，免得用户对着这张"死单"干等。 */
function mergedAlertHtml(d) {
  if (d.status !== 'MERGED' || !d.mainOrderId) return '';
  return `
    <div class="alert alert-info merged-hint">
      这张工单已被合并进主工单 <strong>${esc(d.mainOrderId)}</strong>，后续请以主工单为准。
    </div>`;
}

/**
 * "本单是从哪来的"提示（⑩ 第九步：再次咨询）。
 *
 * 后端详情接口一直返回 `sourceOrderId` 这个字段（以前这个功能没做，恒为 null），
 * 现在它有值了：**本单是由那张老单再次咨询出来的**。
 *
 * ⚠️ 为什么只给**学生**做成可点，干事/管理员只给纯文字：
 *    再次咨询的入口只有老单的提交人能用，所以对**学生**来说老单一定是自己的、点开必看得到；
 *    但干事能不能看老单要看 `canViewWorkOrder`（得是老单的负责人，或者老单还在待接单池），
 *    点过去有一半概率是 403「无权查看该工单」。
 *    与其给一个会跳进错误页的链接，不如只把单号显示出来 —— 信息一样传达到了。
 *    （对照 `mergedAlertHtml`：那里连学生都不给链接，因为主工单多半是别人的单，必然 403。）
 */
function sourceAlertHtml(d) {
  if (!d.sourceOrderId) return '';
  const id = esc(d.sourceOrderId);

  if (state.me && state.me.role === 'STUDENT') {
    return `
      <div class="alert alert-info src-hint">
        这张工单是由
        <button class="lnk" type="button" data-src="${id}">${id}</button>
        再次咨询而来，点单号可以回看那张老单的处理经过。
      </div>`;
  }
  return `
    <div class="alert alert-info src-hint">
      这张工单是由 <strong>${id}</strong> 再次咨询而来（学生基于一张已完结的老单重新提的问题）。
    </div>`;
}

/*
 * 关于 isFlagged（命中敏感词的内部标记）：**故意不在前端显示**。
 * 它是给管理员留的"待复核记号"，不是给学生看的 —— 学生看到「你这条被标记了」只会一头雾水。
 * 后端也确实只是标记、照样允许提交，对学生的操作没有任何影响。
 */

/** 附件按钮组。工单级和每条消息都用它；点击由 onDetailClick 负责下载。 */
function attachmentsHtml(list) {
  if (!list || !list.length) return '';
  return `
    <div class="atts">
      ${list.map(a => `<button class="att" type="button" data-att="${esc(a.attachmentId)}" data-name="${esc(a.fileName)}"><span class="nm">${esc(a.fileName)}</span></button>`).join('')}
    </div>`;
}

/** 消息时间线。 */
function timelineHtml(msgs) {
  if (!msgs || !msgs.length) return '<div class="empty">还没有处理记录。</div>';
  return `
    <ul class="timeline">
      ${msgs.map(function (m) {
        const kind = m.senderType === 'STAFF' ? 'staff'
          : (m.senderType === 'STUDENT' ? 'student' : 'sys');
        const who = m.senderType === 'SYSTEM'
          ? '系统'
          : ((m.senderName || '') + ' · ' + roleCn(m.senderType));
        return `
          <li class="tl-item ${kind}">
            <div class="tl-head">
              <span class="tl-who">${esc(who)}</span>
              <span class="tl-time">${fmtTime(m.createTime)}</span>
            </div>
            <div class="tl-body">${esc(m.content || '')}</div>
            ${attachmentsHtml(m.attachments)}
          </li>`;
      }).join('')}
    </ul>`;
}

/** 详情页里的点击：附件下载 + 学生三件事 + 干事四件事，全从这里分发。 */
async function onDetailClick(e) {
  const jump = e.target.closest('[data-scroll-to]');
  if (jump) {
    const target = document.querySelector(jump.dataset.scrollTo);
    if (target) {
      target.scrollIntoView({ behavior: 'smooth', block: 'center' });
      target.classList.add('jump-highlight');
      window.setTimeout(() => target.classList.remove('jump-highlight'), 1400);
      if (typeof target.focus === 'function') {
        window.setTimeout(() => target.focus({ preventScroll: true }), 420);
      }
    }
    return;
  }
  // ① 学生：确认解决
  if (e.target.closest('#d-confirm') || e.target.closest('#d-confirm-dock')) {
    await onConfirmClose();
    return;
  }
  // ② 学生：发送追问
  if (e.target.closest('#d-follow-btn')) { await onFollowUp(); return; }
  // ③ 学生：再次咨询 —— 跳到"再次咨询"表单。
  //    把老单号写进**地址**（#/submit/from/WO...）而不是内存里，这样按 F5 刷新后
  //    还在"再次咨询"模式；不然刷新一下就会变成普通新建工单，用户还察觉不到。
  if (e.target.closest('#d-reconsult')) {
    go('/submit/from/' + encodeURIComponent(state.detailId));
    return;
  }
  // ④ 学生：点提示条里那个老单号 → 回看老单
  const src = e.target.closest('[data-src]');
  if (src) { go('/orders/' + encodeURIComponent(src.dataset.src)); return; }

  // ⑤ 干事：接单 / 回复 / 完结 / 退回
  if (e.target.closest('#s-accept')) { await onAccept(); return; }
  if (e.target.closest('#s-reply-btn')) { await onReply(); return; }
  if (e.target.closest('#s-ai-btn')) { await onAiSuggestion(); return; }
  if (e.target.closest('#s-close')) { await onCloseOrder(); return; }
  if (e.target.closest('#s-return')) { await onReturnOrder(); return; }

  // ⑥ 管理员：强制关闭待接单的单
  if (e.target.closest('#a-close-btn')) { await onAdminCloseOrder(); return; }

  // ⑦ 干事 / 管理员：合并工单（⑩ 第十一步）—— 搜主单 / 选主单 / 取消选择 / 确认合并
  if (e.target.closest('#m-search')) { await onMergeSearch(); return; }
  const pick = e.target.closest('[data-pick]');
  if (pick) { onMergePick(pick.dataset.pick); return; }
  if (e.target.closest('#m-unpick')) { onMergeUnpick(); return; }
  if (e.target.closest('#m-merge-btn')) { await onMergeSubmit(); return; }

  // ⑧ 点附件 → 下载
  const att = e.target.closest('[data-att]');
  if (!att) return;

  // ⚠️ 为什么不能写成 <a href="/api/files/xxx"> 让浏览器自己下：
  //    那个接口要 Token，而浏览器跳链接**不会带 Authorization 头**，必然 401。
  //    只能先 fetch 成字节（requestBlob 会带上 Token），再在前端"假装成一次下载"存下来。
  const id = att.dataset.att;
  const name = att.dataset.name || 'download';
  setNotice(null, null);

  const r = await requestBlob('/api/files/' + encodeURIComponent(id));
  if (r.needLogin) { state.me = null; render(); return; }
  if (!r.ok) { setNotice('error', r.message); return; }
  saveBlob(r.blob, name);
}

/**
 * 追问用的幂等号。和提交工单是同一个套路：
 *   进详情页 / 追问成功之后就丢掉，下一次追问重新生成一个；
 *   **失败不换号** —— 同一个动作重试时沿用同一个号，后端才认得出"这是同一个请求"。
 *   （依据 IdempotencyStore.run：只有成功才缓存结果，失败会主动放开锁。）
 */
function ensureFollowUpRequestId() {
  if (!state.followUpRequestId) state.followUpRequestId = uuid();
  return state.followUpRequestId;
}

/** 发送追问。 */
async function onFollowUp() {
  const ta = document.getElementById('d-follow');
  if (!ta) return;
  const content = ta.value.trim();

  if (!content) {
    setNotice('warn', '请先写下要追问的内容');
    ta.focus();
    return;
  }

  const btn = document.getElementById('d-follow-btn');
  if (btn) { btn.disabled = true; btn.textContent = '发送中…'; }
  setNotice(null, null);

  // ⚠️ 这里**不传 version**：后端 WorkOrderFollowUpRequest 里没有这个字段
  //    （契约文档的示例有，但实现没有 —— 实测传了也会被忽略）。
  const r = await api.followUp(state.detailId, {
    content: content,
    requestId: ensureFollowUpRequestId(),
    attachmentIds: []
  });

  if (btn) { btn.disabled = false; btn.textContent = '发送追问'; }
  if (r.needLogin) { state.me = null; render(); return; }

  if (!r.ok) {
    // 失败时**不换幂等号**、也**不重画详情** —— 否则用户刚敲的一整段话就没了
    setNotice('error', r.message);
    return;
  }

  // 成功才换号：下一次追问是一个新动作，不能复用这个 requestId
  state.followUpRequestId = '';
  setNotice('ok', '已追问。工单回到「处理中」，处理人会重新回复。');
  // 重载详情：状态、进度条、处理记录时间线、底部这块操作区都要跟着变
  await loadDetail(state.detailId);
}

/** 确认解决（会关单，不可逆）。 */
async function onConfirmClose() {
  // 关了就回不去了（之后只能重新提交一张新单），所以先挡一道
  if (!window.confirm('确认问题已经解决？\n\n' +
      '关闭后这张工单就结束了。如果之后还有问题，需要重新提交一张新工单。')) {
    return;
  }

  const btn = document.getElementById('d-confirm');
  const dockBtn = document.getElementById('d-confirm-dock');
  if (btn) { btn.disabled = true; btn.textContent = '处理中…'; }
  if (dockBtn) { dockBtn.disabled = true; dockBtn.textContent = '处理中…'; }
  setNotice(null, null);

  // ⚠️ 必须带上详情里拿到的 version（后端靠它做乐观锁）
  const r = await api.confirmWorkOrder(state.detailId, state.detailVersion);

  if (btn) { btn.disabled = false; btn.textContent = '确认解决'; }
  if (dockBtn) { dockBtn.disabled = false; dockBtn.textContent = '确认解决'; }
  if (r.needLogin) { state.me = null; render(); return; }

  if (!r.ok) {
    // 409 = 版本冲突 / 状态已经不对了，说白了就是"你看的这一份已经过期"。
    // 这时候别把后端那句话原样甩给用户（他看不懂），直接帮他重新加载最新状态再让他决定。
    if (r.code === 409) {
      setNotice('warn', '这张工单刚刚有变化，已为你重新加载，请确认最新状态后再操作。');
      await loadDetail(state.detailId);
      return;
    }
    setNotice('error', r.message);
    return;
  }

  setNotice('ok', '已确认解决，工单已关闭。');
  await loadDetail(state.detailId);
}

/* ---------- 六之一、页面：干事端的四个动作（⑩ 第八步） ---------- */

/**
 * 干事回复用的幂等号。
 *
 * ⚠️ 为什么不让它和追问共用 state.followUpRequestId：
 *    后端的请求指纹里带了**接口名**（SHA-256(userId + "REPLY" + 单号 + 内容)），
 *    所以同一个号换个接口再调，指纹必然对不上，会被判成
 *    「请求ID已被其他请求占用（IDEMPOTENCY_CONFLICT）」。
 *    两个按钮各用一个号，谁也碍不着谁。
 *
 * 生命周期和追问那一套**一模一样**（这是全站统一的规矩）：
 *   进详情页 / 换单 → 清空；成功 → 清空（下次是新动作）；失败 → 保留（重试才算同一个请求）。
 */
function ensureReplyRequestId() {
  if (!state.replyRequestId) state.replyRequestId = uuid();
  return state.replyRequestId;
}

/**
 * 乐观锁冲突（业务码 409）的统一善后。
 *
 * 干事这四个动作里，409 都是同一个意思：「你手上这份工单已经过期了」——
 *   接单：别人抢先接了 / 版本对不上
 *   回复：单子被关了或合并了
 *   完结 / 退回：版本对不上（别人刚好改过）
 * 用户读不懂「WO_VERSION_CONFLICT」，所以不把原文甩给他，
 * 而是**直接帮他重新加载最新状态**，让他对着新鲜的数据重新决定。
 *
 * ⚠️ 注意这里**必须重新请求后端**，不能只把本地的 state.detailVersion 加 1 ——
 *    版本号 +1 只是我们猜的，真实值只有后端知道。
 */
async function reloadAfterConflict() {
  setNotice('warn', '这张工单刚刚有变化，已为你重新加载最新的状态，请确认后再操作。');
  await loadDetail(state.detailId);
}

/** 接单（PENDING → PROCESSING，我成为负责人）。 */
async function onAccept() {
  const btn = document.getElementById('s-accept');
  if (btn) { btn.disabled = true; btn.textContent = '接单中…'; }
  setNotice(null, null);

  // ⚠️ 必须带上详情里拿到的 version。后端允许不传，但不传等于放弃并发保护：
  //    万一你点接单的同时别人也接了，后端会以为"你没意见"照接不误。
  const r = await api.acceptWorkOrder(state.detailId, state.detailVersion);

  if (btn) { btn.disabled = false; btn.textContent = '接单'; }
  if (r.needLogin) { state.me = null; render(); return; }

  if (!r.ok) {
    // 这个接口的 409 文案是「该工单已被受理或状态不允许受理」，
    // 两种原因（被抢 / 版本过期）后端合并成了一句，处理方式都是"重载看看"。
    if (r.code === 409) { await reloadAfterConflict(); return; }
    setNotice('error', r.message);
    return;
  }

  setNotice('ok', r.message || '受理成功');
  // 重载：状态变「处理中」、进度条往前走、处理人显示我的名字、操作区换成"回复"那一套
  await loadDetail(state.detailId);
}

/**
 * 干事端「AI 建议」：取一张草稿填进回复框。
 *
 * 三条设计上的自我约束：
 * 1. **只填框，不发送**。填完干事还能改，发送仍走原来的 onReply()。
 * 2. `mode === 'SEARCH_ONLY'`（没开 AI / 没密钥 / 模型挂了）**不是错误**：
 *    后端照样返回 code=0 和相关 FAQ，这里照常填、只是把原因说明白。
 * 3. 正文用 value 赋值（不是 innerHTML），来源列表一律过 esc() —— 后端内容也算不可信输入。
 */
async function onAiSuggestion() {
  const btn = document.getElementById('s-ai-btn');
  const panel = document.getElementById('s-ai-panel');
  const ta = document.getElementById('s-reply');
  if (!btn || !ta || !state.detailId) return;

  const oldText = btn.textContent;
  btn.disabled = true;
  btn.textContent = '生成中…';
  if (panel) { panel.hidden = true; panel.innerHTML = ''; }

  const r = await api.aiSuggestion(state.detailId);

  btn.disabled = false;
  btn.textContent = oldText;

  if (r.needLogin) { state.me = null; render(); return; }
  if (!r.ok) { setNotice('error', r.message || 'AI 建议生成失败'); return; }

  const d = r.data || {};
  const suggestion = (d.suggestion || '').trim();
  if (!suggestion) {
    setNotice('warn', '这次没生成出内容，你可以先按模板写，或稍后再试。');
    return;
  }

  // 填进回复框，并把字数计数器同步刷新（否则计数器还停在 0，看着像没填上）
  ta.value = suggestion;
  ta.dispatchEvent(new Event('input', { bubbles: true }));

  if (!panel) return;
  const isAi = d.mode === 'AI';
  const src = (d.sources || []).map(function (s) {
    return '<li><b>' + esc(s.faqId) + '</b> ' + esc(s.question) + '</li>';
  }).join('');

  panel.innerHTML =
    '<div class="ai-tag ' + (isAi ? 'ai-tag-ai' : 'ai-tag-search') + '">'
      + (isAi ? 'AI 草稿 · 已填入回复框，请核对后发送' : '未调用 AI · 以下为相关 FAQ 拼接')
    + '</div>'
    + (d.reason ? '<div class="ai-reason">' + esc(d.reason) + '</div>' : '')
    + (src ? '<div class="ai-src-title">依据的常见问题</div><ul class="ai-src">' + src + '</ul>'
           : '<div class="ai-reason">没有检索到相关的常见问题，这段内容请重点核对。</div>')
    + '<div class="ai-meta">'
      + (d.model ? '模型 ' + esc(d.model) + ' · ' : '')
      + '耗时 ' + (d.elapsedMs || 0) + ' ms</div>';
  panel.hidden = false;
}

/** 干事回复（插一条 REPLY 消息 + 状态 → REPLIED）。 */
async function onReply() {
  const ta = document.getElementById('s-reply');
  if (!ta) return;
  const content = ta.value.trim();

  if (!content) {
    setNotice('warn', '请先写下回复内容');
    ta.focus();
    return;
  }

  const btn = document.getElementById('s-reply-btn');
  if (btn) { btn.disabled = true; btn.textContent = '发送中…'; }
  setNotice(null, null);

  // ⚠️ 这里**不传 version**：后端 WorkOrderReplyRequest 里根本没有这个字段
  //    （契约文档的示例写了 version，实测传 version=999 照样成功 —— 字段被完全忽略）。
  //    它的并发保护是在 SQL 里用 `AND status IN ('PROCESSING','REPLIED')` 兜的。
  const r = await api.replyWorkOrder(state.detailId, {
    content: content,
    requestId: ensureReplyRequestId()
  });

  if (btn) { btn.disabled = false; btn.textContent = '发送回复'; }
  if (r.needLogin) { state.me = null; render(); return; }

  if (!r.ok) {
    // 失败**不换幂等号**、也**不重画详情** —— 否则用户刚敲的一整段话就白写了。
    // （幂等号沿用是安全的：IdempotencyStore 在失败时会主动放开锁、也不写结果缓存，
    //   所以改了内容再传同一个号不会撞 409。）
    if (r.code === 409) { await reloadAfterConflict(); return; }
    setNotice('error', r.message);
    return;
  }

  // 成功才换号：下一条回复是一个新动作
  state.replyRequestId = '';
  setNotice('ok', '已回复。工单状态变成「已回复」，等学生确认。');
  await loadDetail(state.detailId);
}

/** 干事手动完结（PROCESSING / REPLIED → CLOSED，不可逆）。 */
async function onCloseOrder() {
  // 完结之后就回不去了（学生只能重新提交一张新单），所以先挡一道
  if (!window.confirm('确定直接完结这张工单？\n\n' +
      '完结后工单就关闭了：学生不能再追问，只能重新提交一张新工单。')) {
    return;
  }

  const btn = document.getElementById('s-close');
  if (btn) { btn.disabled = true; btn.textContent = '处理中…'; }
  setNotice(null, null);

  // ⚠️ 必须传 version（和「确认解决」一样）：不传报 400「缺少 version 参数」，
  //    对不上报 409 版本冲突。
  const r = await api.closeWorkOrder(state.detailId, state.detailVersion);

  if (btn) { btn.disabled = false; btn.textContent = '直接完结'; }
  if (r.needLogin) { state.me = null; render(); return; }

  if (!r.ok) {
    if (r.code === 409) { await reloadAfterConflict(); return; }
    setNotice('error', r.message);
    return;
  }

  setNotice('ok', '工单已完结。');
  await loadDetail(state.detailId);
}

/** 退回工单（PROCESSING → PENDING，负责人清空、丢回公共池）。 */
async function onReturnOrder() {
  const input = document.getElementById('s-return-remark');
  const remark = input ? input.value.trim() : '';

  // ⚠️ 后端**不校验** remark（不传就是 remark=null，系统消息里光秃秃一句「XX 退回工单」），
  //    但前端在这里强制要求填 —— 学生从时间线里只看得见这条理由，
  //    没理由的退回等于把他晾在那儿，连"我的单怎么了"都不知道。
  //    这是前端按业务意图收窄，不是改需求。
  if (!remark) {
    setNotice('warn', '请先填写退回理由 —— 学生会在处理记录里看到它');
    if (input) input.focus();
    return;
  }

  if (!window.confirm('确定退回这张工单？\n\n' +
      '退回后它会回到「待接单」公共池，你不再是它的负责人，任何干事都能重新接。')) {
    return;
  }

  const btn = document.getElementById('s-return');
  if (btn) { btn.disabled = true; btn.textContent = '处理中…'; }
  setNotice(null, null);

  const r = await api.returnWorkOrder(state.detailId,
    { remark: remark, version: state.detailVersion });

  if (btn) { btn.disabled = false; btn.textContent = '退回待接单池'; }
  if (r.needLogin) { state.me = null; render(); return; }

  if (!r.ok) {
    if (r.code === 409) { await reloadAfterConflict(); return; }
    setNotice('error', r.message);
    return;
  }

  setNotice('ok', '已退回待接单池，负责人已清空。');
  await loadDetail(state.detailId);
}

/**
 * 管理员强制关闭待接单工单（PENDING → CLOSED，不可逆）。
 *
 * 和 `onCloseOrder`（干事完结）几乎一模一样，只差三件事：
 *   ① 要读一个必填的「关闭原因」输入框；
 *   ② 确认弹窗的措辞不同（管理员关的是"这张单不该存在"，不是"处理完了"）；
 *   ③ 成功提示不同。
 * 所以两段代码的骨架是刻意保持一致的 —— 409 统一交给 reloadAfterConflict()，
 * 其余错误原样显示后端 message。
 *
 * ⚠️ 前端也挡了一道"理由不能为空"：
 *    后端同样会挡（400「关闭原因不能为空」，全空格也算空），
 *    但让用户按一下就看到提示，比发一趟请求再回来要好。
 *    而且**不能只靠后端**：后端只回一句错误文案，前端连"该往哪个框里补字"都表达不出来。
 *
 * ⚠️ 409 这一条要特别说明：admin-close 的 409 有**两种完全不同的原因**，
 *    但前端用同一套善后处理，这是对的：
 *      · 「版本冲突，请刷新后重试（WO_VERSION_CONFLICT）」—— 别人刚改过这张单
 *      · 「管理员只能关闭待接单的工单」—— 状态已经不是 PENDING 了（刚被接了 / 被关了）
 *    两者的正确反应都是**重新加载最新状态**：重新加载之后，
 *    要么 version 变新了可以直接重试，要么卡片直接消失（状态不对时压根不渲染操作区）。
 *    这也是为什么不需要让用户读懂这两句话。
 */
async function onAdminCloseOrder() {
  const input = document.getElementById('a-close-remark');
  const remark = input ? input.value.trim() : '';

  if (!remark) {
    setNotice('warn', '请先填写关闭原因 —— 它会写进工单时间线，学生和干事都看得到');
    if (input) input.focus();
    return;
  }

  if (!window.confirm('确定强制关闭这张工单？\n\n' +
      '关闭后不可撤销：学生不能再追问、也不能确认解决，只能重新提交一张新单。')) {
    return;
  }

  const btn = document.getElementById('a-close-btn');
  if (btn) { btn.disabled = true; btn.textContent = '处理中…'; }
  setNotice(null, null);

  // 必须带 version（不传 400、对不上 409）；不用带 requestId（这个接口没有幂等包装）
  const r = await api.adminCloseWorkOrder(state.detailId,
    { remark: remark, version: state.detailVersion });

  if (btn) { btn.disabled = false; btn.textContent = '强制关闭'; }
  if (r.needLogin) { state.me = null; render(); return; }

  if (!r.ok) {
    if (r.code === 409) { await reloadAfterConflict(); return; }
    setNotice('error', r.message);
    return;
  }

  setNotice('ok', '工单已强制关闭。');
  await loadDetail(state.detailId);
}

/* ---------- 六之二、页面：合并工单（⑩ 第十一步） ---------- */

/**
 * 搜一批"可以当主工单"的候选。
 *
 * 用的是**工单列表接口**（GET /api/work-orders），不是管理端那个全维度搜索。
 * 因为列表接口按角色给的范围**正好**就是要的范围：
 *   干事   → 自己负责的 + 待接单池（= 后端允许他合并的那些）
 *   管理员 → 全部
 * 换成 #50 那个接口只会多出权限和字段两套逻辑，收益为零。
 *
 * ⚠️ 后端 keyword 参数的口径就是 `LIKE content`（只搜正文，不搜单号）——
 *    想按单号找，只能把单号填进正文才搜得到。这是列表接口的既有行为，
 *    这一轮不动它（前端也不假装能搜单号，placeholder 就写"搜主工单的内容"）。
 */
async function onMergeSearch() {
  const input = document.getElementById('m-keyword');
  const keyword = input ? input.value.trim() : '';

  state.mergeKeyword = keyword;
  setNotice(null, null);

  const box = document.getElementById('m-results');
  if (box) box.innerHTML = '<div class="empty">正在搜索…</div>';

  // pageSize 取 6：候选是给人肉眼挑的，一屏看完比翻页有用
  const r = await api.workOrders({ page: 1, pageSize: 6, keyword: keyword });
  if (r.needLogin) { state.me = null; render(); return; }

  if (!r.ok) {
    state.mergeHits = [];
    paintMergeResults();
    setNotice('error', r.message);
    return;
  }

  const list = ((r.data || {}).list) || [];
  // 自己不能当自己的主工单（后端会 400「不能把工单合并到自己」）——
  // 从候选里直接剔除，别让用户白点一下才知道
  state.mergeHits = list.filter((o) => o.workOrderId !== state.detailId);
  paintMergeResults();
}

/**
 * 选中一条候选当主工单。
 *
 * ⚠️ 这里**只改内存，不发请求** —— 真正按"确认合并"才动后端。
 *    这是刻意的：选主单是个可以反复改主意的动作，不该每点一次就产生一次网络请求。
 */
function onMergePick(id) {
  const hit = state.mergeHits.filter((o) => o.workOrderId === id)[0];
  if (!hit) return;

  // 只留"确认时用得到"的字段，别把整条列表对象塞进来 ——
  // 免得将来列表 DTO 加字段时误以为这里也会跟着变
  state.mergePicked = {
    workOrderId: hit.workOrderId,
    status: hit.status,
    statusLabel: hit.statusLabel,
    content: hit.content
  };
  setNotice(null, null);
  paintMergeResults();
}

/** 改主意了 —— 取消选择。同样只改内存。 */
function onMergeUnpick() {
  state.mergePicked = null;
  paintMergeResults();
}

/**
 * 确认合并。**不可逆**，所以必须过一道 window.confirm。
 *
 * ⚠️ 请求体只有 mainOrderId + version：
 *    · version 用**本单**的（主单的 version 全程不被碰）；
 *    · 不传 requestId（merge 没有幂等包装，塞了也被忽略）。
 *
 * ⚠️ 409 的善后和别的写操作完全一样：**一律重新加载最新状态**，不区分是哪一种 409
 *    （版本冲突 / 子单状态已变 / 主单已被合并）。因为三种情况的正确反应都是
 *    "先看清现状再说"，让用户去分辨三种文案纯属为难人。
 */
async function onMergeSubmit() {
  const p = state.mergePicked;
  if (!p) { setNotice('warn', '请先搜一张主工单，再选中它。'); return; }

  if (!window.confirm('确定把这张工单合并进 ' + p.workOrderId + ' 吗？\n\n' +
      '合并后这张单变成只读的「已合并」，学生不能再追问、也不能确认解决。这个操作不可撤销。')) {
    return;
  }

  const btn = document.getElementById('m-merge-btn');
  if (btn) { btn.disabled = true; btn.textContent = '合并中…'; }
  setNotice(null, null);

  const r = await api.mergeWorkOrder(state.detailId,
    { mainOrderId: p.workOrderId, version: state.detailVersion });

  if (btn) { btn.disabled = false; btn.textContent = '确认合并到 ' + p.workOrderId; }
  if (r.needLogin) { state.me = null; render(); return; }

  if (!r.ok) {
    if (r.code === 409) { await reloadAfterConflict(); return; }
    setNotice('error', r.message);
    return;
  }

  setNotice('ok', '这张工单已合并进 ' + p.workOrderId + '，后续请以主工单为准。');
  await loadDetail(state.detailId);
}

/* ---------- 七、页面：消息通知 ---------- */

/**
 * 消息通知（路由 #/notifications）。
 *
 * 三种角色共用这一个页面：后端只按 Token 里的人返回"发给我的通知"，
 * 所以学生看到的是"我的工单有新回复"，干事看到的是"有新工单待接单"，前端不用分叉。
 */
function viewNotifications() {
  const q = state.notifQuery;

  appEl.innerHTML = `
    ${topbarHtml()}
    <div class="wrap">
      <div id="notice-slot">${alertHtml()}</div>

      <div class="list-head">
        <h2 class="list-title">消息通知</h2>
        <button class="btn" id="n-readall" type="button">全部已读</button>
      </div>

      <div class="filters">
        <select class="input" id="n-filter">
          ${NOTIF_FILTERS.map(o =>
            `<option value="${o.value}"${o.value === q.isRead ? ' selected' : ''}>${o.label}</option>`).join('')}
        </select>
        <span class="spacer"></span>
        <button class="btn" id="n-refresh" type="button">刷新</button>
      </div>

      <div id="notif-list"></div>
      <div id="notif-pager"></div>
    </div>`;

  bindTopbar();
  document.getElementById('n-readall').addEventListener('click', onReadAll);
  document.getElementById('n-filter').addEventListener('change', onNotifFilter);
  document.getElementById('n-refresh').addEventListener('click', () => loadNotifs());
  // 事件委托：换页/改筛选只重画列表里的内容，监听器绑在容器上就不会丢
  document.getElementById('notif-list').addEventListener('click', onNotifClick);
  document.getElementById('notif-pager').addEventListener('click', onNotifPager);

  loadNotifs();
}

/** 拉一页通知回来。只重画"列表 + 分页"两块，不重画整页。 */
async function loadNotifs() {
  const listBox = document.getElementById('notif-list');
  const pagerBox = document.getElementById('notif-pager');
  if (!listBox) return;

  const q = state.notifQuery;
  listBox.innerHTML = '<div class="empty">正在加载…</div>';
  if (pagerBox) pagerBox.innerHTML = '';

  const r = await api.notifications({
    page: q.page,
    pageSize: q.pageSize,
    // '' = 全部，要**真的不传**这个参数（qs() 会把空串丢掉）；
    // 另外两个翻成真正的布尔值再发，语义最清楚。
    isRead: q.isRead === '' ? '' : (q.isRead === 'true')
  });

  if (r.needLogin) { state.me = null; render(); return; }
  if (!r.ok) {
    listBox.innerHTML = `<div class="empty">加载失败：${esc(r.message)}</div>`;
    return;
  }

  const d = r.data || {};
  const list = d.list || [];
  // 同样是"以后端返回的为准"：后端会把 page 纠正成 ≥1、pageSize 夹到 1~50
  state.notifsTotal = d.total || 0;
  state.notifsPage = d.page || q.page;
  state.notifsPageSize = d.pageSize || q.pageSize;

  if (!list.length) {
    listBox.innerHTML = '<div class="empty">' +
      (q.isRead === '' ? '还没有收到通知。' : '没有符合条件的通知，换个筛选试试。') +
      '</div>';
  } else {
    listBox.innerHTML = list.map(notifCardHtml).join('');
  }
  if (pagerBox) pagerBox.innerHTML = notifPagerHtml();
}

/** 一条通知 = 一张可点的卡片。未读的左边有蓝条 + 蓝点，一眼能扫出来。 */
function notifCardHtml(n) {
  return `
    <div class="card notif-card${n.isRead ? '' : ' unread'}"
         data-id="${esc(n.notifyId)}" data-target="${esc(n.targetId || '')}">
      <div class="notif-top">
        ${n.isRead ? '' : '<span class="udot"></span>'}
        <span class="notif-title">${esc(n.title || notifyTypeCn(n.type))}</span>
        <span class="spacer"></span>
        <span class="muted">${fmtTime(n.createTime)}</span>
      </div>
      ${n.content ? `<p class="notif-content">${esc(n.content)}</p>` : ''}
      <div class="notif-meta">
        <span class="tag">${esc(notifyTypeCn(n.type))}</span>
        ${n.targetId ? `<span class="muted">${esc(n.targetId)}</span>` : ''}
      </div>
    </div>`;
}

function notifPagerHtml() {
  const total = state.notifsTotal;
  if (!total) return '';
  const page = state.notifsPage;
  const pages = Math.max(1, Math.ceil(total / state.notifsPageSize));
  return `
    <div class="pager">
      <button class="btn" id="np-prev" type="button"${page <= 1 ? ' disabled' : ''}>上一页</button>
      <span class="muted">第 ${page} / ${pages} 页 · 共 ${total} 条</span>
      <button class="btn" id="np-next" type="button"${page >= pages ? ' disabled' : ''}>下一页</button>
    </div>`;
}

async function onNotifFilter() {
  // 换了筛选条件必须回第 1 页：不然你人在第 3 页去筛"只看未读"，
  // 第 3 页可能是空的 —— 看起来就像"没有未读通知"。
  state.notifQuery.page = 1;
  state.notifQuery.isRead = document.getElementById('n-filter').value;
  setNotice(null, null);
  await loadNotifs();
}

async function onNotifPager(e) {
  const btn = e.target.closest('button');
  if (!btn || btn.disabled) return;
  const q = state.notifQuery;
  if (btn.id === 'np-prev') q.page = Math.max(1, state.notifsPage - 1);
  else if (btn.id === 'np-next') q.page = state.notifsPage + 1;
  else return;
  setNotice(null, null);
  await loadNotifs();
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

/**
 * 点一条通知 = ①先标已读（把红点清掉）→ ②再跳到它指向的那张工单。
 * 顺序不能反：先跳走的话，这次点击就来不及标记已读了。
 */
async function onNotifClick(e) {
  const card = e.target.closest('.notif-card');
  if (!card) return;

  const id = card.dataset.id;
  const target = card.dataset.target;
  const wasUnread = card.classList.contains('unread');

  setNotice(null, null);
  const r = await api.markNotificationRead(id);
  if (r.needLogin) { state.me = null; render(); return; }

  // 标已读失败**不拦着用户看内容** —— 他点这条是想看那张工单，不是想看这个红点
  if (!r.ok) setNotice('warn', '标记已读失败：' + r.message);

  if (wasUnread && r.ok) {
    // 本地先减掉，红点立刻变；不然要干等下一次轮询（最多 15 秒）才动
    state.unreadCount = Math.max(0, state.unreadCount - 1);
    card.classList.remove('unread');
    const u = card.querySelector('.udot');
    if (u) u.remove();
    paintUnread();
  }

  // targetId 有值就跳详情；没有（比如纯系统提示）就原地刷新一下列表
  if (target) { go('/orders/' + target); return; }
  await loadNotifs();
}

/** 「全部已读」。 */
async function onReadAll() {
  // 先禁用按钮：这个动作会改一批数据，点两下会发两次请求
  const btn = document.getElementById('n-readall');
  if (btn) btn.disabled = true;
  setNotice(null, null);

  const r = await api.markAllNotificationsRead();
  if (btn) btn.disabled = false;

  if (r.needLogin) { state.me = null; render(); return; }
  if (!r.ok) { setNotice('error', r.message); return; }

  const n = (r.data && r.data.markedCount) || 0;
  // markedCount = 0 是"本来就没有未读"，属于正常情况，不是失败
  setNotice('ok', n > 0 ? ('已把 ' + n + ' 条通知标为已读') : '本来就没有未读通知。');
  state.unreadCount = 0;
  paintUnread();
  await loadNotifs();
}

/* ---------- 八、页面：修改密码 ---------- */

function viewPassword() {
  const phoneBound = !!(state.me && state.me.phoneBound);
  const maskedPhone = state.me && state.me.maskedPhone ? state.me.maskedPhone : '未绑定';
  appEl.innerHTML = `
    ${topbarHtml()}
    <div class="wrap">
      <div id="notice-slot">${alertHtml()}</div>
      <div class="card">
        <h2>修改密码</h2>
        <p class="sub">新密码要求 6–20 位，并且同时包含字母和数字。</p>
        <form id="pwd-form">
          <div class="field">
            <label for="oldPwd">当前密码</label>
            <input class="input" id="oldPwd" type="password" autocomplete="current-password" required>
          </div>
          <div class="field">
            <label for="newPwd">新密码</label>
            <input class="input" id="newPwd" type="password" autocomplete="new-password" required>
          </div>
          <div class="field">
            <label for="newPwd2">再输一次新密码</label>
            <input class="input" id="newPwd2" type="password" autocomplete="new-password" required>
          </div>
          <div class="row">
            <button class="btn btn-primary" id="pwd-btn" type="submit">确认修改</button>
            <button class="btn" id="pwd-cancel" type="button">返回</button>
          </div>
        </form>
      </div>

      <div class="card account-phone-card">
        <h2>${phoneBound ? '更换绑定手机' : '绑定手机号'}</h2>
        <p class="sub">
          当前手机号：<strong>${esc(maskedPhone)}</strong>。
          手机号用于新设备登录验证和密码找回，数据库中加密保存。
        </p>
        <div id="phone-notice-slot"></div>
        ${phoneBound ? `
          <div class="field">
            <label for="oldPhoneCode">原手机验证码</label>
            <div class="sms-code-row">
              <input class="input" id="oldPhoneCode" maxlength="6" inputmode="numeric"
                     placeholder="验证原手机号" autocomplete="one-time-code">
              <button class="btn" id="send-old-phone-code" type="button">发送到原手机</button>
            </div>
          </div>` : ''}
        <div class="field">
          <label for="newPhone">新手机号</label>
          <input class="input" id="newPhone" type="tel" inputmode="numeric"
                 maxlength="11" autocomplete="tel" placeholder="请输入新的 11 位手机号">
        </div>
        <div class="field">
          <label for="newPhoneCode">新手机验证码</label>
          <div class="sms-code-row">
            <input class="input" id="newPhoneCode" maxlength="6" inputmode="numeric"
                   placeholder="验证新手机号" autocomplete="one-time-code">
            <button class="btn" id="send-new-phone-code" type="button">发送到新手机</button>
          </div>
        </div>
        <div class="row">
          <button class="btn btn-primary" id="change-phone-btn" type="button">
            ${phoneBound ? '确认更换手机' : '绑定手机号'}
          </button>
        </div>
      </div>
    </div>`;

  document.getElementById('pwd-form').addEventListener('submit', onChangePassword);
  document.getElementById('pwd-cancel').addEventListener('click', () => go('/'));
  const oldPhoneBtn = document.getElementById('send-old-phone-code');
  if (oldPhoneBtn) oldPhoneBtn.addEventListener('click', () => onSendPhoneCode('OLD'));
  document.getElementById('send-new-phone-code')
    .addEventListener('click', () => onSendPhoneCode('NEW'));
  document.getElementById('change-phone-btn').addEventListener('click', onChangePhone);
  bindTopbar();
}

async function onSendPhoneCode(target) {
  const isNew = target === 'NEW';
  const btn = document.getElementById(isNew ? 'send-new-phone-code' : 'send-old-phone-code');
  const newPhone = isNew ? document.getElementById('newPhone').value.trim() : '';
  if (isNew && !/^1[3-9]\d{9}$/.test(newPhone)) {
    setPhoneNotice('error', '请输入正确的 11 位新手机号');
    return;
  }
  btn.disabled = true;
  btn.textContent = '发送中…';
  const r = await api.sendPhoneSmsCode(target, newPhone);
  if (!r.ok) {
    btn.disabled = false;
    btn.textContent = isNew ? '发送到新手机' : '发送到原手机';
    setPhoneNotice('error', r.message);
    return;
  }
  const debug = r.data && r.data.debugCode ? ` 验证码：${r.data.debugCode}` : '';
  setPhoneNotice('ok', r.message + debug);
  startSmsCountdown(btn);
}

function setPhoneNotice(type, message) {
  const slot = document.getElementById('phone-notice-slot');
  if (!slot) return;
  slot.innerHTML = type
    ? `<div class="alert alert-${type === 'error' ? 'error' : type}">${esc(message)}</div>`
    : '';
}

async function onChangePhone() {
  const newPhone = document.getElementById('newPhone').value.trim();
  const newSmsCode = document.getElementById('newPhoneCode').value.trim();
  const oldInput = document.getElementById('oldPhoneCode');
  const oldSmsCode = oldInput ? oldInput.value.trim() : '';
  if (!/^1[3-9]\d{9}$/.test(newPhone)) {
    setPhoneNotice('error', '请输入正确的 11 位新手机号');
    return;
  }
  if (!/^\d{6}$/.test(newSmsCode) || (oldInput && !/^\d{6}$/.test(oldSmsCode))) {
    setPhoneNotice('error', '请输入完整的 6 位短信验证码');
    return;
  }

  const btn = document.getElementById('change-phone-btn');
  btn.disabled = true;
  btn.textContent = '提交中…';
  const r = await api.changePhone(newPhone, oldSmsCode, newSmsCode);
  if (!r.ok) {
    btn.disabled = false;
    btn.textContent = oldInput ? '确认更换手机' : '绑定手机号';
    setPhoneNotice('error', r.message);
    return;
  }

  await api.logout();
  stopUnreadPolling();
  clearToken();
  state.me = null;
  state.unreadCount = 0;
  state.notice = { type: 'ok', text: '手机号已更新，请重新登录' };
  go('/');
}

async function onChangePassword(e) {
  e.preventDefault();
  const btn = document.getElementById('pwd-btn');
  const oldPwd = document.getElementById('oldPwd').value;
  const newPwd = document.getElementById('newPwd').value;
  const newPwd2 = document.getElementById('newPwd2').value;

  // 只做最基本的本地检查（两次是否一致）；长度和"字母+数字"交给服务端判，
  // 免得前端规则和后端规则各写一份、以后改了一边忘了另一边。
  if (newPwd !== newPwd2) {
    setNotice('error', '两次输入的新密码不一致');
    return;
  }

  btn.disabled = true;
  btn.textContent = '提交中…';
  setNotice(null, null);

  const r = await api.changePassword(oldPwd, newPwd);
  if (!r.ok) {
    btn.disabled = false;
    btn.textContent = '确认修改';
    setNotice('error', r.message);
    return;
  }

  state.needChangePassword = false;
  localStorage.removeItem(NOW_CHANGE_KEY);
  state.notice = { type: 'ok', text: '密码已修改成功' };
  go('/');
}

/* ---------- 九、页面：公告 ---------- */

/** 公告（路由 #/announcements）。只读 —— 后端已经把"展示中的、没过期的"筛好排好了。 */
async function viewAnnouncements(focusNoticeId) {
  // 游客没有"上一页"可回（他唯一能去的地方就是登录页），
  // 所以按钮文案跟着身份走 —— 别让人点一个写着"返回"的按钮，却被带到登录页。
  const backText = state.me ? '‹ 返回' : '‹ 去登录';

  appEl.innerHTML = `
    ${topbarHtml()}
    <div class="wrap">
      <div id="notice-slot">${alertHtml()}</div>
      <div class="row">
        <button class="btn" id="nn-back" type="button">${backText}</button>
      </div>
      <div id="ann-box"><div class="empty">正在加载…</div></div>
    </div>`;

  bindTopbar();
  document.getElementById('nn-back').addEventListener('click', () => go('/'));

  const box = document.getElementById('ann-box');
  const r = await api.announcements();
  if (r.needLogin) { onNeedLogin('公告加载失败，请重新登录后再看。'); return; }
  if (!r.ok) {
    box.innerHTML = '<div class="empty">加载失败：' + esc(r.message) + '</div>';
    return;
  }

  // data 是**数组**（公告接口没有分页），不是 { list: [...] }
  const list = r.data || [];
  box.innerHTML = list.length
    ? list.map(noticeCardHtml).join('')
    : '<div class="empty">当前没有公告。</div>';

  if (focusNoticeId && list.length) {
    const target = Array.from(box.querySelectorAll('[data-notice-id]'))
      .find(item => item.dataset.noticeId === focusNoticeId);
    if (target) {
      target.classList.add('jump-highlight');
      target.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
  }
}

/**
 * 一条公告。
 *
 * 🔴 content 走 esc() 转义 + `white-space: pre-wrap` 显示，也就是**当纯文本渲染**，
 *    绝不拼进 innerHTML。
 *    后端 AnnouncementItem 的注释写的是"支持富文本、前端按 HTML 渲染" —— 但那样等于把
 *    XSS 的口子开给"能发公告的账号"；而且系统里压根没有富文本编辑器，管理员本来就写不出
 *    粗体/超链接，纯文本渲染不会损失任何实际效果。**这条是用户拍板的。**
 *    pre-wrap 是为了保住管理员在文本框里敲的换行（不然一整篇会挤成一坨）。
 */
function noticeCardHtml(a) {
  return `
    <div class="card notice-card" data-notice-id="${esc(a.noticeId)}">
      <div class="notice-head">
        <h2 class="notice-title">${esc(a.title || '（无标题）')}</h2>
      </div>
      <div class="notice-meta">
        <span>${esc(a.publisherName || '')}</span>
        <span>·</span>
        <span>${fmtFull(a.publishTime)}</span>
        ${a.expireTime ? `<span>·</span><span>有效期至 ${fmtFull(a.expireTime)}</span>` : ''}
      </div>
      <p class="notice-body">${esc(a.content || '')}</p>
    </div>`;
}

/* ---------- 十、页面：常见问题 ---------- */

/**
 * 常见问题（路由 #/faqs）：分类 + 关键词筛选，点一条就地展开答案。
 *
 * 这个页面**没有分页** —— FAQ 接口返回的就是一个数组（契约里它连分页参数都没有）。
 */
async function viewFaqs(focusFaqId) {
  // 深链接必须从"全部问题"开始，否则上一次的筛选条件可能刚好把目标藏掉。
  if (focusFaqId) state.faqQuery = { category: '', keyword: '' };
  const q = state.faqQuery;
  // 游客没有"上一页"，返回按钮直接把他送回登录页（和公告页同一个写法）
  const backText = state.me ? '‹ 返回' : '‹ 去登录';

  appEl.innerHTML = `
    ${topbarHtml()}
    <div class="wrap">
      <div id="notice-slot">${alertHtml()}</div>
      <div class="row">
        <button class="btn" id="fq-back" type="button">${backText}</button>
      </div>

      <div class="filters">
        <select class="input" id="fq-category">
          <option value="">全部分类</option>
          ${CATEGORIES.map(c =>
            `<option value="${esc(c)}"${c === q.category ? ' selected' : ''}>${esc(c)}</option>`).join('')}
        </select>
        <input class="input" id="fq-keyword" type="search" placeholder="搜索问题关键词"
               value="${esc(q.keyword)}" autocomplete="off">
        <button class="btn btn-primary" id="fq-search" type="button">查询</button>
      </div>

      <div id="faq-list"></div>
    </div>`;

  bindTopbar();
  document.getElementById('fq-back').addEventListener('click', () => go('/'));
  document.getElementById('fq-search').addEventListener('click', onFaqSearch);
  document.getElementById('fq-category').addEventListener('change', onFaqSearch);
  document.getElementById('fq-keyword').addEventListener('keydown', (e) => {
    // 手机上键盘的"搜索/回车"键应该等于点"查询"
    if (e.key === 'Enter') { e.preventDefault(); onFaqSearch(); }
  });
  // 事件委托：列表重画也不会丢监听器
  document.getElementById('faq-list').addEventListener('click', onFaqClick);

  await loadFaqs();

  if (focusFaqId) {
    const box = document.getElementById('faq-list');
    const target = box && Array.from(box.querySelectorAll('.faq-item'))
      .find(item => item.dataset.id === focusFaqId);
    if (target) {
      target.classList.add('jump-highlight');
      target.scrollIntoView({ behavior: 'smooth', block: 'center' });
      // 复用原有点击逻辑：会向后端请求答案，同时只把这一条 FAQ 的浏览量加一。
      target.click();
      window.setTimeout(() => {
        target.scrollIntoView({ behavior: 'smooth', block: 'center' });
      }, 360);
    }
  }
}

async function onFaqSearch() {
  // ⚠️ 分类只能传**合法值**：后端对非法分类返回 400（不是"静默给个空列表"）。
  //    下拉里那个"全部分类"的 value 是空串，qs() 会把这个参数丢掉 = 不筛。
  //    分类枚举和提交工单那 5 个是同一份，所以直接复用 CATEGORIES。
  state.faqQuery.category = document.getElementById('fq-category').value;
  state.faqQuery.keyword = document.getElementById('fq-keyword').value.trim();
  setNotice(null, null);
  await loadFaqs();
}

async function loadFaqs() {
  const box = document.getElementById('faq-list');
  if (!box) return;

  const q = state.faqQuery;
  box.innerHTML = '<div class="empty">正在加载…</div>';

  const r = await api.faqs({ category: q.category, keyword: q.keyword });
  if (r.needLogin) { onNeedLogin('常见问题加载失败，请重新登录后再看。'); return; }
  if (!r.ok) {
    box.innerHTML = '<div class="empty">加载失败：' + esc(r.message) + '</div>';
    return;
  }

  const list = r.data || [];   // 数组，不是 { list: [...] }
  if (!list.length) {
    const filtered = !!(q.category || q.keyword);
    box.innerHTML = '<div class="empty">' +
      (filtered ? '没有找到相关的问题，换个关键词试试。' : '这里还没有常见问题。') +
      '</div>';
    return;
  }
  box.innerHTML = list.map(faqItemHtml).join('');
}

/** 一条 FAQ。这里**故意没有答案** —— 点开时才向后端要（列表接口不给 answer）。 */
function faqItemHtml(f) {
  return `
    <div class="card faq-item" data-id="${esc(f.faqId)}">
      <div class="faq-q">
        <span class="faq-mark">问</span>
        <span class="faq-q-text">${esc(f.question || '')}</span>
        <span class="faq-arrow">›</span>
      </div>
      <div class="faq-meta">
        <span class="tag">${esc(f.category || '未分类')}</span>
        <span class="muted faq-views">看过 ${f.viewCount || 0} 次</span>
      </div>
      <div class="faq-answer" hidden></div>
    </div>`;
}

/**
 * 点一条 FAQ：就地展开答案，再点一下收起。
 *
 * 为什么展开时还要单独请求一次后端（而不是页面加载时把答案全拉下来）：
 *   列表接口**故意不返回 answer** —— 契约把 viewCount 的自增放在详情接口里，
 *   要是列表就把答案发下来，详情永远没人调、浏览量这个字段就永远是 0。
 *   顺带：展开后拿返回的 viewCount 把那一行的"看过 N 次"刷成刚 +1 的新值。
 */
async function onFaqClick(e) {
  const item = e.target.closest('.faq-item');
  if (!item) return;
  const box = item.querySelector('.faq-answer');
  if (!box) return;

  // 已经展开 → 收起。顺手把内容丢掉，下次展开重新问后端（这样"看过 N 次"的 +1 才是准的）
  if (!box.hidden) {
    box.hidden = true;
    box.innerHTML = '';
    item.classList.remove('open');
    return;
  }

  box.hidden = false;
  item.classList.add('open');
  box.innerHTML = '<div class="empty">正在加载…</div>';

  const r = await api.faqDetail(item.dataset.id);
  if (r.needLogin) { onNeedLogin('答案加载失败，请重新登录后再看。'); return; }
  if (!r.ok) {
    box.innerHTML = '<div class="empty">' + esc(r.message) + '</div>';
    return;
  }

  const d = r.data || {};
  box.innerHTML = '<p class="faq-a-text">' + esc(d.answer || '（这条还没有填写答案）') + '</p>';

  const vc = item.querySelector('.faq-views');
  if (vc && typeof d.viewCount === 'number') vc.textContent = '看过 ' + d.viewCount + ' 次';
}

/* ---------- 十一、管理后台（⑩ 第 4 项） ---------- */

/**
 * 后台首页：一张**子导航**。
 *
 * 为什么要单独做一个壳、而不是把 6 个页面直接挂到顶部条的「更多」里：
 *   ① 那 6 个是"管理员专用"的功能，跟学生/干事也在用的公告、FAQ 混在一个下拉里，
 *      下拉会越来越长，而且非管理员看了只会得到一堆点了就 403 的按钮；
 *   ② 后面还有 5 个页面要做（敏感词 / 模板 / 统计 / 导出 / 日志），
 *      现在有这个壳，以后只往 ADMIN_NAV 里加一行，**顶部条和路由都不用再动**。
 *
 * ⚠️ ready: false 的项是"还没做"的：按钮 disabled，点不动，不骗人。
 */
const ADMIN_NAV = [
  { route: '/admin/users',     title: '用户管理',   desc: '查账号、禁用/启用、重置密码', ready: true },
  { route: '/admin/words',     title: '敏感词',     desc: '命中后给工单打标记的关键词',   ready: true },
  { route: '/admin/templates', title: '回复模板',   desc: '干事回复时直接套用的常用话术', ready: true },
  { route: '/admin/stats',     title: '数据统计',   desc: '工单量、处理时效、分类分布',   ready: true },
  { route: '/admin/export',    title: '数据导出',   desc: '按条件把工单导成 Excel',       ready: true },
  { route: '/admin/logs',      title: '操作日志',   desc: '谁在什么时候动了什么',         ready: true }
];

function viewAdmin() {
  appEl.innerHTML = `
    ${topbarHtml()}
    <div class="wrap">
      <div id="notice-slot">${alertHtml()}</div>
      <div class="row">
        <button class="btn" id="adm-back" type="button">‹ 返回</button>
      </div>

      <h2 class="list-title">管理后台</h2>
      <p class="muted">这一页只有管理员能进。</p>

      <div class="adm-nav">
        ${ADMIN_NAV.map(n => `
          <button class="card adm-nav-item${n.ready ? '' : ' is-off'}" type="button"
                  data-route="${esc(n.route)}"${n.ready ? '' : ' disabled'}>
            <span class="adm-nav-title">${esc(n.title)}</span>
            <span class="muted adm-nav-desc">${esc(n.desc)}</span>
            ${n.ready ? '' : '<span class="tag">还没做</span>'}
          </button>`).join('')}
      </div>
    </div>`;

  bindTopbar();
  document.getElementById('adm-back').addEventListener('click', () => go('/'));
  // 事件委托绑在**容器**上：以后往 ADMIN_NAV 加项不用改这里
  document.querySelector('.adm-nav').addEventListener('click', (e) => {
    const b = e.target.closest('.adm-nav-item');
    if (!b || b.disabled) return;
    go(b.dataset.route);
  });
}

/**
 * 用户管理页（契约 #34 / #35 / #36）。
 *
 * 三个筛选都只能传**固定枚举**（后端对非法值返回 400，不是"给个空列表"），
 * 所以角色和状态都用下拉、不让人手输 —— 唯一的自由输入是关键词。
 */
const ADMIN_USER_TYPES = [
  { value: '', label: '全部角色' },
  { value: 'STUDENT', label: '学生' },
  { value: 'STAFF', label: '干事' },
  { value: 'ADMIN', label: '管理员' }
];
const ADMIN_USER_STATUS = [
  { value: '', label: '全部状态' },
  { value: 'ACTIVE', label: '正常' },
  { value: 'DISABLED', label: '已禁用' }
];

function viewAdminUsers() {
  const q = state.adminUserQuery;

  appEl.innerHTML = `
    ${topbarHtml()}
    <div class="wrap">
      <div id="notice-slot">${alertHtml()}</div>
      <div class="row">
        <button class="btn" id="au-back" type="button">‹ 返回后台</button>
      </div>

      <h2 class="list-title">用户管理</h2>

      <div class="filters">
        <select class="input" id="au-type">
          ${ADMIN_USER_TYPES.map(o =>
            `<option value="${o.value}"${o.value === q.userType ? ' selected' : ''}>${o.label}</option>`).join('')}
        </select>
        <select class="input" id="au-status">
          ${ADMIN_USER_STATUS.map(o =>
            `<option value="${o.value}"${o.value === q.status ? ' selected' : ''}>${o.label}</option>`).join('')}
        </select>
        <input class="input" id="au-keyword" type="search" placeholder="搜索学号 / 姓名"
               value="${esc(q.keyword)}" autocomplete="off">
        <button class="btn btn-primary" id="au-search" type="button">查询</button>
      </div>

      <div id="au-list"></div>
      <div id="au-pager"></div>
    </div>`;

  bindTopbar();
  document.getElementById('au-back').addEventListener('click', () => go('/admin'));
  document.getElementById('au-search').addEventListener('click', onAdminUserSearch);
  document.getElementById('au-type').addEventListener('change', onAdminUserSearch);
  document.getElementById('au-status').addEventListener('change', onAdminUserSearch);
  document.getElementById('au-keyword').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); onAdminUserSearch(); }
  });
  document.getElementById('au-list').addEventListener('click', onAdminUserClick);
  document.getElementById('au-pager').addEventListener('click', onAdminUserPagerClick);

  loadAdminUsers();
}

/** 改筛选 / 敲回车：一律回到第 1 页（理由和工单列表那条一样，翻着页改条件会撞到空页）。 */
function onAdminUserSearch() {
  state.adminUserQuery.page = 1;
  state.adminUserQuery.userType = document.getElementById('au-type').value;
  state.adminUserQuery.status = document.getElementById('au-status').value;
  state.adminUserQuery.keyword = document.getElementById('au-keyword').value.trim();
  setNotice(null, null);
  return loadAdminUsers();
}

async function loadAdminUsers() {
  const listBox = document.getElementById('au-list');
  const pagerBox = document.getElementById('au-pager');
  if (!listBox) return;

  const q = state.adminUserQuery;
  listBox.innerHTML = '<div class="empty">正在加载…</div>';
  if (pagerBox) pagerBox.innerHTML = '';

  const r = await api.adminUsers({
    page: q.page,
    pageSize: q.pageSize,
    userType: q.userType,
    status: q.status,
    keyword: q.keyword
  });
  if (r.needLogin) { state.me = null; render(); return; }
  if (!r.ok) {
    listBox.innerHTML = '<div class="empty">加载失败：' + esc(r.message) + '</div>';
    return;
  }

  const d = r.data || {};
  const list = d.list || [];
  // 同样"以后端返回的为准"：后端会把 page 纠正成 ≥1、pageSize 夹到 1~50
  state.adminUsersTotal = d.total || 0;
  state.adminUsersPage = d.page || q.page;
  state.adminUsersPageSize = d.pageSize || q.pageSize;

  if (!list.length) {
    const filtered = !!(q.userType || q.status || q.keyword);
    listBox.innerHTML = '<div class="empty">' +
      (filtered ? '没有符合条件的用户，换个条件试试。' : '这里还没有用户。') + '</div>';
  } else {
    listBox.innerHTML = list.map(adminUserRowHtml).join('');
  }
  if (pagerBox) pagerBox.innerHTML = adminUserPagerHtml();
}

/** 一个用户 = 一张卡片（和工单列表保持同一种排版，不另起炉灶做表格）。 */
function adminUserRowHtml(u) {
  const disabled = u.status === 'DISABLED';
  const isMe = !!state.me && u.userId === state.me.userId;
  return `
    <div class="card adm-user" data-id="${esc(u.userId)}" data-name="${esc(u.userName)}">
      <div class="adm-user-top">
        <strong>${esc(u.userName || '（无姓名）')}</strong>
        <span class="tag">${esc(roleCn(u.userType))}</span>
        <span class="adm-state ${disabled ? 'is-off' : 'is-on'}">${disabled ? '已禁用' : '正常'}</span>
        ${isMe ? '<span class="tag">我自己</span>' : ''}
        <span class="spacer"></span>
      </div>
      <div class="adm-user-meta">
        <span>${esc(u.userId)}</span>
        <span>·</span>
        <span>最近登录：${u.lastLoginTime ? esc(fmtTime(u.lastLoginTime)) : '从没登录过'}</span>
      </div>
      <div class="row adm-user-ops">
        ${isMe
          // 后端也会挡（改自己 → 400），这里置灰是"少报一次必然的错"
          ? '<button class="btn" type="button" disabled title="不能改自己的状态">禁用 / 启用</button>'
          // ⚠️ 这里**故意不用红色**：禁用虽然影响别人，但它是**可逆**的
          //    （再点一下"启用"就回来了）。全站红色只留给"点了就回不去"的动作 ——
          //    现在是管理员强制关闭工单、合并工单两处（见 verify-frontend 的断言）。
          //    一个页面里到处是红按钮，红色就失去了"停一下"的警示作用。
          : `<button class="btn" type="button"
                     data-act="toggle" data-next="${disabled ? 'ACTIVE' : 'DISABLED'}">${disabled ? '启用' : '禁用'}</button>`}
        <button class="btn" type="button" data-act="reset">重置密码</button>
      </div>
    </div>`;
}

function adminUserPagerHtml() {
  const total = state.adminUsersTotal;
  if (!total) return '';
  const page = state.adminUsersPage;
  const pages = Math.max(1, Math.ceil(total / state.adminUsersPageSize));
  return `
    <div class="pager">
      <button class="btn" id="au-prev" type="button"${page <= 1 ? ' disabled' : ''}>上一页</button>
      <span class="muted">第 ${page} / ${pages} 页 · 共 ${total} 条</span>
      <button class="btn" id="au-next" type="button"${page >= pages ? ' disabled' : ''}>下一页</button>
    </div>`;
}

async function onAdminUserPagerClick(e) {
  const btn = e.target.closest('button');
  if (!btn || btn.disabled) return;
  if (btn.id === 'au-prev') state.adminUserQuery.page = Math.max(1, state.adminUsersPage - 1);
  else if (btn.id === 'au-next') state.adminUserQuery.page = state.adminUsersPage + 1;
  else return;
  setNotice(null, null);
  await loadAdminUsers();
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

/**
 * 卡片上的两个按钮：禁用/启用、重置密码。
 *
 * 🔴 两个都弹确认框（用户拍板：都是会影响别人的操作）：
 *   · 禁用 —— 对方手上的 Token **下一个请求**就 403（拦截器每请求实时查库）；
 *   · 重置 —— 对方原密码立刻登不上，而**已经登录的会话不受影响**（JWT 无状态，踢不下来）。
 *   确认文案里把这两句写清楚，管理员才知道自己点下去会发生什么。
 */
async function onAdminUserClick(e) {
  const btn = e.target.closest('button[data-act]');
  if (!btn || btn.disabled) return;
  const row = btn.closest('.adm-user');
  if (!row) return;

  const id = row.dataset.id;
  const name = row.dataset.name || id;

  if (btn.dataset.act === 'toggle') {
    const toDisable = btn.dataset.next === 'DISABLED';
    const ok = window.confirm(
      toDisable
        ? `确定禁用「${name}」（${id}）吗？\n\n禁用后他手上已登录的会话会立刻失效，下一个请求就被拦下来。`
        : `确定启用「${name}」（${id}）吗？\n\n启用后他可以正常登录和使用。`
    );
    if (!ok) return;

    const r = await api.adminUpdateUserStatus(id, { status: btn.dataset.next });
    if (r.needLogin) { state.me = null; render(); return; }
    if (!r.ok) {
      setNotice('err', r.message || '操作失败');
      return;
    }
    setNotice('ok', toDisable ? `已禁用「${name}」` : `已启用「${name}」`);
    await loadAdminUsers();
    return;
  }

  if (btn.dataset.act === 'reset') {
    const ok = window.confirm(
      `确定重置「${name}」（${id}）的密码吗？\n\n` +
      `新密码 = 学号后 6 位。\n` +
      `注意：他已经登录的会话不会被踢下线，要等他自己登出或 Token 过期。`
    );
    if (!ok) return;

    const r = await api.adminResetPassword(id);
    if (r.needLogin) { state.me = null; render(); return; }
    if (!r.ok) {
      setNotice('err', r.message || '重置失败');
      return;
    }
    setNotice('ok', `已重置「${name}」的密码（学号后 6 位）`);
    return;
  }
}

/**
 * 敏感词管理页（契约 #37~#40）。
 *
 * 🔴 先说清这个模块的定位，免得用错：敏感词**不是提交闸门**。
 *   学生提交的内容命中了也照常发出去，只是工单被打上 is_flagged 标记，
 *   管理员在后台能专门把这类单筛出来复核。所以"加错一个词"不会把谁卡住 ——
 *   它是可逆的管理配置，这也是下面两个操作**都不弹确认框**的理由
 *   （用户管理那页的禁用/重置会影响别人的账号，所以弹；这里不会）。
 *
 * 🔴 这个模块**没有单独的启用接口**：把一个词"再加一次"就等于启用
 *   （后端发现它已存在且是 DISABLED，会顺便恢复成 ACTIVE）。
 *   所以界面上的「启用」按钮打的是**添加**接口。
 */
const ADMIN_WORD_VIEWS = [
  { value: 'ALL', label: '全部（含已禁用）' },
  { value: 'ACTIVE', label: '只看启用中的' }
];

function viewAdminWords() {
  const f = state.adminWordFilter;

  appEl.innerHTML = `
    ${topbarHtml()}
    <div class="wrap">
      <div id="notice-slot">${alertHtml()}</div>
      <div class="row">
        <button class="btn" id="aw-back" type="button">‹ 返回后台</button>
      </div>

      <h2 class="list-title">敏感词</h2>
      <p class="muted">命中的工单会被打上标记供复核，<strong>不会拦截学生提交</strong>。</p>

      <div class="card adm-form">
        <div class="adm-form-row">
          <input class="input" id="aw-word" type="text" maxlength="100" autocomplete="off"
                 placeholder="输入一个敏感词（100 字以内）">
          <button class="btn btn-primary" id="aw-add" type="button">添加</button>
        </div>

        <details class="adm-batch">
          <summary>批量导入（支持从 Excel 直接粘贴）</summary>
          <textarea class="input adm-batch-input" id="aw-words" rows="4"
                    placeholder="词之间用逗号、顿号、分号或换行隔开"></textarea>
          <div class="row">
            <button class="btn btn-primary" id="aw-batch" type="button">批量导入</button>
          </div>
        </details>
      </div>

      <div class="filters">
        <input class="input" id="aw-keyword" type="search" placeholder="在结果里找词"
               value="${esc(f.keyword)}" autocomplete="off">
        <select class="input" id="aw-only">
          ${ADMIN_WORD_VIEWS.map(o =>
            `<option value="${o.value}"${o.value === (f.onlyActive ? 'ACTIVE' : 'ALL') ? ' selected' : ''}>${o.label}</option>`).join('')}
        </select>
      </div>

      <div id="aw-list"></div>
    </div>`;

  bindTopbar();
  document.getElementById('aw-back').addEventListener('click', () => go('/admin'));
  document.getElementById('aw-add').addEventListener('click', onAdminWordAdd);
  document.getElementById('aw-word').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); onAdminWordAdd(); }
  });
  document.getElementById('aw-batch').addEventListener('click', onAdminWordBatch);
  // ⚠️ `input` 事件**不冒泡**，只能直接绑在这个输入框上（不能靠委托）。
  //    好在这两个筛选控件不在被重画的 aw-list 里，绑一次就一直在。
  document.getElementById('aw-keyword').addEventListener('input', (e) => {
    state.adminWordFilter.keyword = e.target.value;
    paintAdminWords();
  });
  document.getElementById('aw-only').addEventListener('change', (e) => {
    state.adminWordFilter.onlyActive = e.target.value === 'ACTIVE';
    paintAdminWords();
  });
  document.getElementById('aw-list').addEventListener('click', onAdminWordClick);

  loadAdminWords();
}

async function loadAdminWords() {
  const box = document.getElementById('aw-list');
  if (!box) return;

  box.innerHTML = '<div class="empty">正在加载…</div>';
  const r = await api.adminSensitiveWords();
  if (r.needLogin) { state.me = null; render(); return; }
  if (!r.ok) {
    box.innerHTML = '<div class="empty">加载失败：' + esc(r.message) + '</div>';
    return;
  }
  // 后端一次性给全量（不分页），整份留在内存里给本地过滤用
  state.adminWords = r.data || [];
  paintAdminWords();
}

/** 按当前筛选条件算出要显示的那些词。**纯内存运算，不发请求。** */
function adminWordVisible() {
  const f = state.adminWordFilter;
  const kw = String(f.keyword || '').trim().toLowerCase();
  let rows = state.adminWords;
  if (f.onlyActive) {
    rows = rows.filter(w => w.status === 'ACTIVE');
  }
  if (kw) {
    rows = rows.filter(w => String(w.word || '').toLowerCase().indexOf(kw) >= 0);
  }
  return rows;
}

/** 只重画列表那一块 —— 改筛选条件时走这里，不重新请求、也不重建上面的输入框。 */
function paintAdminWords() {
  const box = document.getElementById('aw-list');
  if (!box) return;

  const rows = adminWordVisible();
  if (!rows.length) {
    const filtered = !!state.adminWordFilter.keyword.trim() || state.adminWordFilter.onlyActive;
    box.innerHTML = '<div class="empty">' +
      (filtered ? '没有符合筛选条件的词。' : '还没有敏感词，先在上面添加一个。') + '</div>';
    return;
  }
  box.innerHTML = '<p class="muted adm-count">共 ' + rows.length + ' 个词' +
    (state.adminWords.length !== rows.length ? '（全部 ' + state.adminWords.length + ' 个）' : '') +
    '</p>' + rows.map(adminWordRowHtml).join('');
}

function adminWordRowHtml(w) {
  const off = w.status === 'DISABLED';
  return `
    <div class="card adm-word" data-id="${esc(w.wordId)}" data-word="${esc(w.word)}">
      <div class="adm-user-top">
        <strong>${esc(w.word)}</strong>
        <span class="adm-state ${off ? 'is-off' : 'is-on'}">${off ? '已禁用' : '启用中'}</span>
        <span class="spacer"></span>
      </div>
      <div class="adm-user-meta">
        <span>#${esc(w.wordId)}</span>
        <span>·</span>
        <span>添加人：${esc(w.creatorName || '—')}</span>
        <span>·</span>
        <span>${w.createTime ? esc(fmtTime(w.createTime)) : ''}</span>
      </div>
      <div class="row adm-user-ops">
        ${off
          ? '<button class="btn" type="button" data-act="enable">启用</button>'
          : '<button class="btn" type="button" data-act="disable">禁用</button>'}
      </div>
    </div>`;
}

async function onAdminWordAdd() {
  const input = document.getElementById('aw-word');
  const word = (input.value || '').trim();
  if (!word) {
    setNotice('err', '请先输入要添加的词');
    return;
  }
  const r = await api.adminAddSensitiveWord({ word });
  if (r.needLogin) { state.me = null; render(); return; }
  if (!r.ok) {
    setNotice('err', r.message || '添加失败');
    return;
  }
  // 后端对"新增 / 已存在 / 已恢复启用"三种情况都回 code=0，只有 message 不同
  // ⇒ 直接把 message 显示出来，管理员才知道刚才到底发生了什么。
  setNotice('ok', r.message || '已添加');
  input.value = '';
  await loadAdminWords();
}

async function onAdminWordBatch() {
  const input = document.getElementById('aw-words');
  const words = (input.value || '').trim();
  if (!words) {
    setNotice('err', '请先粘贴或输入要导入的词');
    return;
  }
  const r = await api.adminBatchSensitiveWords({ words });
  if (r.needLogin) { state.me = null; render(); return; }
  if (!r.ok) {
    setNotice('err', r.message || '导入失败');
    return;
  }
  const d = r.data || {};
  // added + skipped == total 是后端保证的恒等式，照着说就不会自相矛盾
  setNotice('ok', '导入完成：共 ' + (d.total || 0) + ' 个，新增 ' + (d.added || 0) +
                 ' 个，跳过 ' + (d.skipped || 0) + ' 个（已存在或已启用）');
  input.value = '';
  await loadAdminWords();
}

async function onAdminWordClick(e) {
  const btn = e.target.closest('button[data-act]');
  if (!btn || btn.disabled) return;
  const row = btn.closest('.adm-word');
  if (!row) return;

  if (btn.dataset.act === 'disable') {
    const r = await api.adminDisableSensitiveWord(row.dataset.id);
    if (r.needLogin) { state.me = null; render(); return; }
    if (!r.ok) {
      setNotice('err', r.message || '禁用失败');
      return;
    }
    setNotice('ok', '已禁用「' + row.dataset.word + '」（停用后不再给新工单打标记）');
    await loadAdminWords();
    return;
  }

  // 「启用」= 把同一个词再添加一次（这个模块没有单独的启用接口，见函数上方的注释）
  const r = await api.adminAddSensitiveWord({ word: row.dataset.word });
  if (r.needLogin) { state.me = null; render(); return; }
  if (!r.ok) {
    setNotice('err', r.message || '启用失败');
    return;
  }
  setNotice('ok', r.message || '已启用');
  await loadAdminWords();
}

/**
 * 回复模板管理页（契约 #41~#44）。
 *
 * 三个要点：
 *  ① **一个表单兼顾新增和编辑**：点「编辑」把那条的值填进上面的表单、按钮变「保存修改」，
 *     保存时走 PUT（只发改动的字段）。不给编辑另写一套字段 —— 四个字段两份，
 *     早晚会出现"两处校验不一致"。
 *  ② **`TPL_CONTENT_MAX` 是纯前端限制**：content 在库里是 TEXT，后端只校验非空白，
 *     不校验长度。压到 1000 字是为了和工事回复框一致（模板本来就是往回复框里插的），
 *     免得插进去一提交反而超出回复上限。
 *  ③ 列表的筛选走**服务端**（后端支持 category + status，且 status 只有管理员传才生效），
 *     和敏感词页那个"后端不支持只能本地筛"的做法不同 —— 别照搬。
 */
const ADMIN_TPL_STATUS = [
  { value: 'ALL', label: '全部（含已禁用）' },
  { value: 'ACTIVE', label: '只看启用中的' },
  { value: 'DISABLED', label: '只看已禁用的' }
];
/** 内容框上限，和干事的回复框一致（见 api.replyWorkOrder 那边的 1000） */
const TPL_CONTENT_MAX = 1000;

function viewAdminTemplates() {
  const f = state.adminTplFilter;
  const e = state.adminTplEdit;

  appEl.innerHTML = `
    ${topbarHtml()}
    <div class="wrap">
      <div id="notice-slot">${alertHtml()}</div>
      <div class="row">
        <button class="btn" id="at-back" type="button">‹ 返回后台</button>
      </div>

      <h2 class="list-title">回复模板</h2>
      <p class="muted">干事回工单时可以一键套用这些话术。</p>

      <div class="card adm-form" id="at-form">
        <div class="adm-form-head">
          <strong>${e ? '正在编辑 ' + esc(e.templateId) : '新增模板'}</strong>
          ${e ? '<button class="lnk" id="at-cancel" type="button">取消编辑</button>' : ''}
        </div>

        <div class="adm-form-row">
          <select class="input" id="at-category">
            ${CATEGORIES.map(c =>
              `<option value="${esc(c)}"${e && e.category === c ? ' selected' : ''}>${esc(c)}</option>`).join('')}
          </select>
          <input class="input" id="at-title" type="text" maxlength="100" autocomplete="off"
                 placeholder="标题（100 字以内）" value="${e ? esc(e.title) : ''}">
        </div>

        <div class="field">
          <label for="at-content">内容</label>
          <textarea class="input adm-batch-input" id="at-content" maxlength="${TPL_CONTENT_MAX}"
                    placeholder="例如：您好，学时将在活动结束后的 3-5 个工作日内录入，请耐心等待。">${e ? esc(e.content) : ''}</textarea>
          <div class="counter"><span id="at-content-count">0</span> / ${TPL_CONTENT_MAX}</div>
        </div>

        <div class="adm-form-row">
          <input class="input" id="at-sort" type="number" min="0" step="1"
                 value="${e ? esc(e.sortNum) : 0}" placeholder="排序号（小的排前面）">
          <button class="btn btn-primary" id="at-save" type="button">${e ? '保存修改' : '新增'}</button>
        </div>
      </div>

      <div class="filters">
        <select class="input" id="at-f-category">
          <option value="">全部分类</option>
          ${CATEGORIES.map(c =>
            `<option value="${esc(c)}"${c === f.category ? ' selected' : ''}>${esc(c)}</option>`).join('')}
        </select>
        <select class="input" id="at-f-status">
          ${ADMIN_TPL_STATUS.map(o =>
            `<option value="${o.value}"${o.value === f.status ? ' selected' : ''}>${o.label}</option>`).join('')}
        </select>
      </div>

      <div id="at-list"></div>
    </div>`;

  bindTopbar();
  document.getElementById('at-back').addEventListener('click', () => go('/admin'));
  document.getElementById('at-save').addEventListener('click', onAdminTplSave);
  const cancel = document.getElementById('at-cancel');
  if (cancel) cancel.addEventListener('click', () => {
    state.adminTplEdit = null;
    setNotice(null, null);
    render();
  });
  document.getElementById('at-f-category').addEventListener('change', onAdminTplFilter);
  document.getElementById('at-f-status').addEventListener('change', onAdminTplFilter);
  document.getElementById('at-list').addEventListener('click', onAdminTplClick);

  // 内容框的字数计数：和详情页那几个框共用 bindCounter（input 事件不冒泡，只能直接绑）
  bindCounter('at-content', 'at-content-count');
  // 编辑模式回填时 textarea 里已经有字，但那是渲染时写进去的 HTML、不触发 input ⇒ 手动补一次
  syncCounter('at-content', 'at-content-count');

  loadAdminTemplates();
}

async function loadAdminTemplates() {
  const box = document.getElementById('at-list');
  if (!box) return;

  const f = state.adminTplFilter;
  box.innerHTML = '<div class="empty">正在加载…</div>';

  const r = await api.templates({ category: f.category, status: f.status });
  if (r.needLogin) { state.me = null; render(); return; }
  if (!r.ok) {
    box.innerHTML = '<div class="empty">加载失败：' + esc(r.message) + '</div>';
    return;
  }
  state.adminTplList = r.data || [];

  if (!state.adminTplList.length) {
    const filtered = !!(f.category || f.status !== 'ALL');
    box.innerHTML = '<div class="empty">' +
      (filtered ? '没有符合条件的模板。' : '还没有模板，先在上面新增一个。') + '</div>';
    return;
  }
  box.innerHTML = '<p class="muted adm-count">共 ' + state.adminTplList.length + ' 个模板</p>' +
    state.adminTplList.map(adminTplCardHtml).join('');
}

function adminTplCardHtml(t) {
  const off = t.status === 'DISABLED';
  return `
    <div class="card adm-word adm-tpl" data-id="${esc(t.templateId)}">
      <div class="adm-user-top">
        <strong>${esc(t.title)}</strong>
        <span class="tag">${esc(t.category)}</span>
        <span class="adm-state ${off ? 'is-off' : 'is-on'}">${off ? '已禁用' : '启用中'}</span>
        <span class="spacer"></span>
        <span class="muted adm-tpl-sort">排序 ${esc(t.sortNum)}</span>
      </div>
      <p class="adm-tpl-body">${esc(t.content)}</p>
      <div class="adm-user-meta">
        <span>${esc(t.templateId)}</span>
        <span>·</span>
        <span>创建人：${esc(t.creatorName || '—')}</span>
        <span>·</span>
        <span>${t.createTime ? esc(fmtTime(t.createTime)) : ''}</span>
      </div>
      <div class="row adm-user-ops">
        <button class="btn" type="button" data-act="edit">编辑</button>
        ${off
          ? '<button class="btn" type="button" data-act="enable">启用</button>'
          : '<button class="btn" type="button" data-act="disable">禁用</button>'}
      </div>
    </div>`;
}

function onAdminTplFilter() {
  state.adminTplFilter.category = document.getElementById('at-f-category').value;
  state.adminTplFilter.status = document.getElementById('at-f-status').value;
  setNotice(null, null);
  return loadAdminTemplates();
}

async function onAdminTplSave() {
  const category = document.getElementById('at-category').value;
  const title = document.getElementById('at-title').value.trim();
  const content = document.getElementById('at-content').value.trim();
  const sortRaw = document.getElementById('at-sort').value.trim();
  const sortNum = sortRaw === '' ? 0 : Number(sortRaw);

  // 前端先拦一道：这几个错后端都会报 400，但用户不想为了知道"标题不能空"跑一趟服务器
  if (!title) { setNotice('err', '标题不能为空'); return; }
  if (!content) { setNotice('err', '内容不能为空'); return; }
  if (!Number.isInteger(sortNum) || sortNum < 0) {
    setNotice('err', '排序号要填 0 或正整数（它决定模板在列表里的先后）');
    return;
  }

  const e = state.adminTplEdit;
  const r = e
    ? await api.adminUpdateTemplate(e.templateId, { category, title, content, sortNum })
    : await api.adminCreateTemplate({ category, title, content, sortNum });

  if (r.needLogin) { state.me = null; render(); return; }
  if (!r.ok) {
    setNotice('err', r.message || '保存失败');
    return;
  }
  // ⚠️ 后端在"一个字段都没变"时也回 code=0，message 是"没有需要修改的字段" ——
  //    直接显示它，别自己编一句"保存成功"，不然用户以为改到了。
  state.adminTplEdit = null;
  setNotice('ok', r.message || (e ? '已保存' : '已新增'));
  render();
}

async function onAdminTplClick(e) {
  const btn = e.target.closest('button[data-act]');
  if (!btn || btn.disabled) return;
  const card = btn.closest('.adm-tpl');
  if (!card) return;
  const id = card.dataset.id;
  const act = btn.dataset.act;

  if (act === 'edit') {
    const t = state.adminTplList.find(x => x.templateId === id);
    if (!t) return;
    // 只是把值搬进表单，**不发请求**；真正的保存要再点一次「保存修改」
    state.adminTplEdit = {
      templateId: t.templateId,
      category: t.category,
      title: t.title,
      content: t.content,
      sortNum: t.sortNum
    };
    setNotice(null, null);
    render();
    window.scrollTo({ top: 0, behavior: 'smooth' });   // 表单在页面顶部，得让人看见
    return;
  }

  if (act === 'disable') {
    const r = await api.adminDisableTemplate(id);
    if (r.needLogin) { state.me = null; render(); return; }
    if (!r.ok) { setNotice('err', r.message || '禁用失败'); return; }
    setNotice('ok', '已禁用 ' + id);
    return loadAdminTemplates();
  }

  if (act === 'enable') {
    // 「启用」= 部分更新，只把 status 拉回 ACTIVE（其余字段一个都不动）
    const r = await api.adminUpdateTemplate(id, { status: 'ACTIVE' });
    if (r.needLogin) { state.me = null; render(); return; }
    if (!r.ok) { setNotice('err', r.message || '启用失败'); return; }
    setNotice('ok', '已启用 ' + id);
    return loadAdminTemplates();
  }
}

/* ---------- 数据统计（⑩ 第 4 项第四块）---------- */

/**
 * 四个快捷时间范围。
 *
 * 为什么没有"自定义"这个按钮、却能自定义：用户只要动了日期框，preset 就变成 'custom'，
 * 四个快捷按钮的高亮**全部消失** —— 这本身就是"你现在用的是自定义区间"的提示，
 * 不必再放一个按钮占位置。
 */
const STATS_PRESETS = [
  { key: 'today', label: '今天' },
  { key: '7d', label: '近 7 天' },
  { key: '30d', label: '近 30 天' },
  { key: 'all', label: '全部' }
];

/**
 * Date → 'YYYY-MM-DD'。
 *
 * 🔴 **不能图省事"转成 ISO 字符串再截前 10 位"**：那个字符串是 **UTC** 日期，
 *    在东八区，"今天凌晨 8 点之前"会被算成昨天 —— 用户点「今天」却查到昨天，
 *    差一天而且界面看着完全正常，极难发现。
 *    这里用 getFullYear/getMonth/getDate 取**本地**年月日，才是用户眼里的"今天"。
 */
function fmtDateInput(d) {
  const p = n => (n < 10 ? '0' : '') + n;
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
}

/** 把快捷范围翻译成 { startDate, endDate }；'all' 返回两个空串（= 不传参数 = 全量） */
function presetRange(key) {
  if (key === 'all') return { startDate: '', endDate: '' };
  const start = new Date();
  const end = new Date();
  // "近 7 天"是**含今天**的 7 天，所以往前推 6 天；今天这个区间起止都是今天。
  if (key === '7d') start.setDate(start.getDate() - 6);
  else if (key === '30d') start.setDate(start.getDate() - 29);
  return { startDate: fmtDateInput(start), endDate: fmtDateInput(end) };
}

function viewAdminStats() {
  const r = state.adminStatsRange;

  appEl.innerHTML = `
    ${topbarHtml()}
    <div class="wrap">
      <div id="notice-slot">${alertHtml()}</div>
      <div class="row">
        <button class="btn" id="as-back" type="button">‹ 返回后台</button>
      </div>

      <h2 class="list-title">数据统计</h2>

      <div class="card">
        <div class="row stats-presets" id="as-presets">
          ${STATS_PRESETS.map(p =>
            `<button class="btn${state.adminStatsPreset === p.key ? ' btn-primary' : ''}" type="button"
                     data-preset="${p.key}">${p.label}</button>`).join('')}
        </div>
        <div class="filters stats-dates">
          <input class="input" id="as-start" type="date" value="${esc(r.startDate)}" aria-label="起始日期">
          <span class="muted">至</span>
          <input class="input" id="as-end" type="date" value="${esc(r.endDate)}" aria-label="截止日期">
          <button class="btn btn-primary" id="as-query" type="button">查询</button>
        </div>
        <p class="muted stats-hint">不选日期就是全部时间。截止日那一天是整天都算的。</p>
      </div>

      <div id="as-body"><div class="empty">正在加载…</div></div>
    </div>`;

  bindTopbar();
  document.getElementById('as-back').addEventListener('click', () => go('/admin'));
  document.getElementById('as-presets').addEventListener('click', onStatsPreset);
  document.getElementById('as-query').addEventListener('click', onStatsQuery);
  document.getElementById('as-start').addEventListener('change', onStatsDateTyped);
  document.getElementById('as-end').addEventListener('change', onStatsDateTyped);

  loadAdminStats();
}

/** 点快捷范围：换 preset、算出日期、重画（让日期框跟着变），然后拉数据。 */
function onStatsPreset(e) {
  const btn = e.target.closest('button[data-preset]');
  if (!btn) return;
  state.adminStatsPreset = btn.dataset.preset;
  state.adminStatsRange = presetRange(btn.dataset.preset);
  setNotice(null, null);
  render();                       // 重画一次，日期框才会显示新的区间
}

/** 手动改日期：preset 变成 custom ⇒ 四个快捷按钮自动全部取消高亮。 */
function onStatsDateTyped() {
  state.adminStatsPreset = 'custom';
  state.adminStatsRange.startDate = document.getElementById('as-start').value;
  state.adminStatsRange.endDate = document.getElementById('as-end').value;
  paintPresetActive();            // 只改按钮高亮，不重画整页（免得刚填的日期被打断）
}

function paintPresetActive() {
  const box = document.getElementById('as-presets');
  if (!box) return;
  Array.prototype.forEach.call(box.querySelectorAll('button[data-preset]'), (b) => {
    const on = b.dataset.preset === state.adminStatsPreset;
    b.classList.toggle('btn-primary', on);
  });
}

async function onStatsQuery() {
  const start = document.getElementById('as-start').value;
  const end = document.getElementById('as-end').value;
  // 先自己拦一道：起始晚于截止后端会报 400，没必要让用户为此跑一趟服务器。
  // ⚠️ 'YYYY-MM-DD' 直接比大小就是对的 —— 这种格式的字典序恰好等于日期先后。
  if (start && end && start > end) {
    setNotice('err', '起始日期不能晚于截止日期');
    return;
  }
  state.adminStatsPreset = 'custom';
  state.adminStatsRange.startDate = start;
  state.adminStatsRange.endDate = end;
  paintPresetActive();
  setNotice(null, null);
  await loadAdminStats();
}

async function loadAdminStats() {
  const box = document.getElementById('as-body');
  if (box) box.innerHTML = '<div class="empty">正在加载…</div>';

  const r = state.adminStatsRange;
  // 空串会被 qs() 丢掉 —— 正好等于"不传这个参数"= 统计全量
  const params = { startDate: r.startDate, endDate: r.endDate };

  // 两个接口互不依赖，并行发（串行等两趟白白多花一个来回）
  const [over, work] = await Promise.all([
    api.adminStatisticsOverview(params),
    api.adminStaffWorkload(params)
  ]);
  if (over.needLogin || work.needLogin) { state.me = null; render(); return; }

  if (!over.ok) {
    if (box) box.innerHTML = '<div class="empty">加载失败：' + esc(over.message) + '</div>';
    return;
  }
  state.adminStats = over.data || {};
  state.adminWorkload = (work.ok && work.data) ? work.data : [];
  if (!work.ok) {
    setNotice('err', '干事工作量没拉到：' + (work.message || ''));
  }
  paintAdminStats();
}

function paintAdminStats() {
  const box = document.getElementById('as-body');
  if (!box) return;

  const d = state.adminStats || {};
  const total = d.totalTickets || 0;
  // 后端给的是小数（0.12 = 12%），这里 ×100 并留一位小数
  const dupPct = Math.round((d.duplicateRate || 0) * 1000) / 10;

  box.innerHTML = `
    <div class="stat-cards">
      <div class="card stat-card">
        <span class="stat-num">${total}</span>
        <span class="muted">工单总量（张）</span>
      </div>
      <div class="card stat-card">
        <span class="stat-num">${dupPct}%</span>
        <span class="muted">重复单占比</span>
      </div>
      <div class="card stat-card">
        <span class="stat-num">${fmtMinutes(d.avgResponseMinutes)}</span>
        <span class="muted">平均响应时长</span>
      </div>
      <div class="card stat-card">
        <span class="stat-num">${fmtMinutes(d.avgCloseMinutes)}</span>
        <span class="muted">平均办结时长</span>
      </div>
    </div>

    <h3 class="stats-h">状态分布</h3>
    <div class="card">
      ${statusBarListHtml(d.statusDistribution || {}, total)}
    </div>

    <h3 class="stats-h">分类分布</h3>
    <div class="card">
      ${CATEGORIES.map(c => statBarHtml(c, (d.categoryDistribution || {})[c] || 0, total, 'sb-cat')).join('')}
    </div>

    <h3 class="stats-h">干事工作量</h3>
    ${workloadHtml()}`;
}

/**
 * 状态分布那 5 条。
 *
 * 为什么用 `FLOW + MERGED` 这个固定顺序、而不是直接遍历后端返回的对象：
 *   后端保证两个分布都**给满 5 个键**，但**对象的键顺序是随机的**（HashMap）——
 *   直接遍历的话，每次刷新顺序都可能变，看着像在跳。固定顺序 + 固定颜色才稳定。
 */
function statusBarListHtml(dist, total) {
  return FLOW.concat(['MERGED']).map(s =>
    statBarHtml(STATUS_LABEL[s] || s, dist[s] || 0, total, 'sb-' + s.toLowerCase())
  ).join('');
}

/** 一条横向条形：标签 + 条 + 「数量（占比）」。占比按**总量**算，不是按本项最大值。 */
function statBarHtml(label, count, total, cls) {
  const pct = total > 0 ? (count * 100 / total) : 0;
  const pctText = total > 0 ? (Math.round(pct * 10) / 10) + '%' : '—';
  return `
    <div class="sb-row">
      <span class="sb-label">${esc(label)}</span>
      <div class="sb-track"><div class="sb-fill ${cls}" style="width:${pct.toFixed(1)}%"></div></div>
      <span class="sb-num">${count}<span class="muted"> ${pctText}</span></span>
    </div>`;
}

/**
 * 分钟数给人看的写法。
 *
 * ⚠️ 0 显示成「—」而不是「0 分钟」：后端在"没有可统计的样本"时就是给 0
 *    （比如这段时间一张单都没完结），显示「0 分钟」会被读成"快得离谱"。
 */
function fmtMinutes(m) {
  const n = Math.max(0, Math.round(Number(m) || 0));
  if (n <= 0) return '—';
  if (n < 60) return n + ' 分钟';
  if (n < 1440) {
    const h = Math.floor(n / 60), mm = n % 60;
    return mm ? h + ' 小时 ' + mm + ' 分' : h + ' 小时';
  }
  const day = Math.floor(n / 1440), h = Math.round((n % 1440) / 60);
  return h ? day + ' 天 ' + h + ' 小时' : day + ' 天';
}

function workloadHtml() {
  const list = state.adminWorkload;
  if (!list.length) {
    return '<div class="card"><div class="empty">这段时间里还没有人接过单。</div></div>';
  }
  return `
    <div class="card">
      <div class="wl-row wl-head">
        <span>干事</span><span>单量</span><span>已完结</span><span>平均办理</span>
      </div>
      ${list.map(w => `
        <div class="wl-row">
          <span>${esc(w.handlerName || w.handlerId || '—')}</span>
          <span>${w.total || 0}</span>
          <span>${w.closedCount || 0}</span>
          <span>${fmtMinutes(w.avgCloseMinutes)}</span>
        </div>`).join('')}
    </div>`;
}

/* ---------- 管理后台：数据导出（契约 §10.5 / #47）---------- */

/**
 * 数据导出页。
 *
 * 这一页和前面几个管理页有个**本质区别**，先说清楚（不然代码看着会觉得绕）：
 *
 *   前面那些页面做的事是"拉回 JSON → 渲染成 HTML"。
 *   这一页做的事是"**把一大坨字节变成一次浏览器下载**"，而且——
 *
 *   🔴 后端**失败的时候，HTTP 状态码也是 200**（错误写在 JSON 的 code 里）。
 *      我们下载用的那个公共函数只认 HTTP 状态码，所以失败时它会把那段错误 JSON
 *      原样交给我们，`ok` 还是 true。
 *      如果我们不管三七二十一就 saveBlob()，用户会得到一个几十字节的假 Excel：
 *      打开是乱码，而且**全程一句报错都没有**。
 *
 *      ⇒ 所以 onExportRun() 里最关键的其实是那三行"看 Content-Type 再决定"。
 *
 * 筛选条件为什么和「我的工单」列表页长得一样：
 *   后端这两个接口**共用同一个查询对象**（有意的设计，源码注释里写着原因：
 *   "列表能筛、导出筛不出来"是这类系统最典型的坑，两处各写一份 SQL、改 A 忘 B，
 *   用户会拿到一份"看着像筛过、其实没筛"的表格 —— 而且不会报错，只是安静地给错数据）。
 *   所以界面上给的筛选维度，和列表页保持一致，导出来的条数 = 列表里看到的条数。
 */
function viewAdminExport() {
  const r = state.adminExportRange;
  const f = state.adminExportFilter;

  appEl.innerHTML = `
    ${topbarHtml()}
    <div class="wrap">
      <div id="notice-slot">${alertHtml()}</div>
      <div class="row">
        <button class="btn" id="ex-back" type="button">‹ 返回后台</button>
      </div>

      <h2 class="list-title">数据导出</h2>
      <p class="muted">把工单导成 Excel（.xlsx）。一条工单一行，用 Excel / WPS 都能直接打开。</p>

      <div class="card">
        <div class="row stats-presets" id="ex-presets">
          ${STATS_PRESETS.map(p =>
            `<button class="btn${state.adminExportPreset === p.key ? ' btn-primary' : ''}" type="button"
                     data-preset="${p.key}">${p.label}</button>`).join('')}
        </div>

        <div class="filters stats-dates">
          <input class="input" id="ex-start" type="date" value="${esc(r.startDate)}" aria-label="起始日期">
          <span class="muted">至</span>
          <input class="input" id="ex-end" type="date" value="${esc(r.endDate)}" aria-label="截止日期">
          <select class="input" id="ex-status" aria-label="状态">
            ${STATUS_FILTERS.map(o =>
              `<option value="${o.value}"${o.value === f.status ? ' selected' : ''}>${o.label}</option>`).join('')}
          </select>
          <select class="input" id="ex-category" aria-label="分类">
            <option value="">全部分类</option>
            ${CATEGORIES.map(c =>
              `<option value="${esc(c)}"${c === f.category ? ' selected' : ''}>${esc(c)}</option>`).join('')}
          </select>
          <input class="input" id="ex-keyword" type="text" maxlength="50" autocomplete="off"
                 placeholder="关键词（单号 / 内容）" value="${esc(f.keyword)}">
          <button class="btn btn-primary" id="ex-run" type="button">导出 Excel</button>
        </div>

        <p class="muted stats-hint">
          什么都不选 = 导出全部工单。截止日那一天整天都算。
          点「导出 Excel」后浏览器会直接保存文件（文件名带时间戳，导两次不会互相覆盖）。
        </p>
      </div>

      <div class="card">
        <p class="muted">导出的表里有两列状态：中文的（给人看）和英文代码的（做二次统计时好对）。</p>
        <p class="muted">每一次**成功**的导出，后端都会记一条操作日志（操作人、时间、筛了什么条件、多少行）。</p>
      </div>
    </div>`;

  bindTopbar();
  document.getElementById('ex-back').addEventListener('click', () => go('/admin'));
  document.getElementById('ex-presets').addEventListener('click', onExportPreset);
  document.getElementById('ex-start').addEventListener('change', onExportDateTyped);
  document.getElementById('ex-end').addEventListener('change', onExportDateTyped);
  document.getElementById('ex-status').addEventListener('change', onExportFilterChanged);
  document.getElementById('ex-category').addEventListener('change', onExportFilterChanged);
  document.getElementById('ex-run').addEventListener('click', onExportRun);
  // 关键词框**不监听**任何事件：它不需要即时反应，点导出时现读一次就行
  // （每敲一个字就干点什么，反而会让"我只改了一半"的状态被记进 state）。
}

/** 点快捷范围：换 preset、算出日期、重画（日期框才会跟着变）。和统计页同一套手感。 */
function onExportPreset(e) {
  const btn = e.target.closest('button[data-preset]');
  if (!btn) return;
  state.adminExportPreset = btn.dataset.preset;
  state.adminExportRange = presetRange(btn.dataset.preset);
  setNotice(null, null);
  render();                       // 重画一次，日期框才会显示新的区间
}

/** 手动改日期 ⇒ preset 变成 custom，四个快捷按钮自动全部取消高亮（= "你在用自定义区间"）。 */
function onExportDateTyped() {
  state.adminExportPreset = 'custom';
  state.adminExportRange.startDate = document.getElementById('ex-start').value;
  state.adminExportRange.endDate = document.getElementById('ex-end').value;
  paintExportPresetActive();      // 只改按钮高亮，不重画整页（免得刚填的日期被打断）
}

/**
 * 状态 / 分类下拉变了。
 *
 * ⚠️ 顺手把上一次的提示清掉：它是"已下载 工单导出-xxx.xlsx"这种话，
 *    条件一改，那句话就和眼前的界面**对不上**了（用户会以为新条件已经导过了）。
 */
function onExportFilterChanged() {
  state.adminExportFilter.status = document.getElementById('ex-status').value;
  state.adminExportFilter.category = document.getElementById('ex-category').value;
  setNotice(null, null);
}

function paintExportPresetActive() {
  const box = document.getElementById('ex-presets');
  if (!box) return;
  Array.prototype.forEach.call(box.querySelectorAll('button[data-preset]'), (b) => {
    b.classList.toggle('btn-primary', b.dataset.preset === state.adminExportPreset);
  });
}

/**
 * 下载的文件名：`工单导出-20261011-153045.xlsx`。
 *
 * 后端其实也起了一个名字（`work-orders-...`，放在 Content-Disposition 响应头里），
 * 但那个名字要靠读响应头才拿得到，而我们的下载通道只回传了字节和 Content-Type。
 * **权衡后决定前端自己起名** —— 不为了一个文件名去动那个已经被验证过的公共下载函数
 * （改了它就得连带回归附件下载那条路）。
 *
 * 带时间戳的理由：导两次不会互相覆盖。浏览器遇到重名会自动改成 "xxx(1).xlsx"，
 * 看着乱，而且用户分不清哪个是新的。
 *
 * ⚠️ 和 fmtDateInput 一样，**不能用"日期转成字符串再截前几位"那种省事写法**：
 *    那种串是 UTC 的，在东八区按下按钮却会差 8 小时（凌晨按下，"今天"会变成昨天）。
 *    这里老老实实取本地年月日时分秒。
 */
function exportFileName() {
  const d = new Date();
  const p = n => (n < 10 ? '0' : '') + n;
  return '工单导出-' + d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate())
    + '-' + p(d.getHours()) + p(d.getMinutes()) + p(d.getSeconds()) + '.xlsx';
}

/**
 * 真的去导出。
 *
 * 🔴 这一页最容易踩的坑全在下面这段分流里：**后端失败时 HTTP 状态码也是 200**，
 *    错误写在一段 JSON 的 code 里。而下载通道只看 HTTP 状态码 ⇒ 失败时它照样
 *    说"成功了"，然后把那段 JSON 当文件给我们。不在这里看一眼 Content-Type，
 *    用户就会下载到一个几十字节的假 xlsx，打开是乱码，**而且一句报错都没有**。
 */
async function onExportRun() {
  setNotice(null, null);

  // 从界面上把当前条件读全（关键词没监听 change，所以在这里现读）
  const kwEl = document.getElementById('ex-keyword');
  const f = {
    startDate: state.adminExportRange.startDate,
    endDate: state.adminExportRange.endDate,
    status: document.getElementById('ex-status').value,
    category: document.getElementById('ex-category').value,
    keyword: kwEl ? kwEl.value.trim() : ''
  };
  // 记回 state：万一这次要重新渲染（比如 401 被送回登录页再回来），条件还在
  state.adminExportFilter.status = f.status;
  state.adminExportFilter.category = f.category;
  state.adminExportFilter.keyword = f.keyword;

  // 先自己拦一道：起始晚于截止后端会报 400，没必要让用户为此专门跑一趟服务器。
  // ⚠️ 'YYYY-MM-DD' 这种格式**直接比大小就是对的** —— 它的字典序恰好等于日期先后。
  if (f.startDate && f.endDate && f.startDate > f.endDate) {
    setNotice('error', '起始日期不能晚于截止日期');
    return;
  }

  // 生成期间禁掉按钮：几百上千行要转成 xlsx，慢的时候能有好几秒，用户会想再点一次
  const btn = document.getElementById('ex-run');
  if (btn) { btn.disabled = true; btn.textContent = '正在生成…'; }

  const r = await api.exportWorkOrders(f);

  if (btn) { btn.disabled = false; btn.textContent = '导出 Excel'; }

  if (r.needLogin) { onNeedLogin('登录已失效，请重新登录'); return; }
  if (!r.ok) { setNotice('error', r.message); return; }

  // ---- 到这里 HTTP 是 200。但"200"不代表拿到的是文件，必须看内容类型 ----
  const ct = (r.contentType || '').toLowerCase();
  if (ct.indexOf('json') >= 0) {
    // 后端把错误写成了 JSON（参数不合法 400 / 不是管理员 403），HTTP 却是 200。
    // 把 message 掏出来当人话提示；万一连 JSON 都解不开，就用兜底那句话。
    let msg = '导出失败，请检查筛选条件后重试';
    try {
      const j = JSON.parse(await r.blob.text());
      if (j && j.message) msg = j.message;
    } catch (e) { /* 保持兜底提示 */ }
    setNotice('error', msg);
    return;
  }

  // ---- 确实是文件：起个名，交给浏览器保存 ----
  const name = exportFileName();
  saveBlob(r.blob, name);
  setNotice('ok', '已开始下载 ' + name);
}

/* ---------- 管理后台：操作日志（契约 §10.6 / #48、#49）---------- */

/**
 * 操作日志页：**一页装两个接口**，顶部分段切换（用户拍板）。
 *
 * 两个接口长得很像（都是分页 + 几个筛选），但答的是**两个完全不同的问题**：
 *   · 状态日志 `/status-logs`    → 查 `t_status_log`：**这张单**经历了什么（业务时间线）
 *   · 操作日志 `/operation-logs` → 查 `t_operation_log`：**谁**动过系统（查账追责）
 * 所以合在一页用分段切换，而不是塞进两个几乎一模一样的页面（那样改一处要改两遍）。
 *
 * ⚠️ 两个 tab 的筛选条件、页码**各存一套**（见 state 里那两组 `adminLog*`）——
 *    切到"操作日志"翻到第 3 页、再切回来，「状态日志」还停在原来的页码和工单号上。
 */
const LOG_ACTION_LABEL = {
  SUBMIT: '提交',
  ACCEPT: '受理',
  REPLY: '回复',
  FOLLOW_UP: '追问',
  RETURN: '退回',
  MANUAL_CLOSE: '干事完结',
  ADMIN_CLOSE: '管理员关闭',
  CONFIRM: '确认解决',
  MERGE: '合并'
};

/**
 * 操作类型按"属于哪块业务"分组（原生 `<optgroup>` 就支持，不用自己写控件）。
 *
 * ⚠️ 为什么这里**敢**写死一份清单：因为后端**不校验 operationType**
 *    （这列是 varchar，类型会随功能增长，硬编码白名单只会变成维护负担），
 *    传一个库里没有的值不报错、只是安静地回**空列表**。
 *    ⇒ 所以界面上绝不能让用户手输类型：把 `LOGIN` 打成 `login` 就什么都查不到，
 *      而他只会以为「这段时间没有登录记录」，查半天查不出问题在哪。
 *
 * ⚠️ 代价是老问题：**以后后端加了新操作类型，这里要手动补一行**
 *    （清单来源：`SELECT DISTINCT operation_type FROM t_operation_log`，共 29 种，2026-10-11）。
 */
const OP_TYPE_GROUPS = [
  {
    label: '工单',
    items: [
      ['SUBMIT', '提交工单'], ['ACCEPT', '受理工单'], ['REPLY', '回复工单'],
      ['FOLLOW_UP', '学生追问'], ['CONFIRM', '确认解决'], ['RETURN', '退回工单'],
      ['MANUAL_CLOSE', '干事完结'], ['ADMIN_CLOSE', '管理员强制关闭'],
      ['MERGE', '合并工单'], ['RE_CONSULTATION', '再次咨询']
    ]
  },
  {
    label: '账号',
    items: [
      ['REGISTER', '学生注册'], ['LOGIN', '登录'], ['LOGOUT', '登出'],
      ['CHANGE_PASSWORD', '修改密码'], ['RESET_PASSWORD', '重置密码'],
      ['CHANGE_PHONE', '修改手机号'],
      ['DISABLE_USER', '禁用账号'], ['ENABLE_USER', '启用账号'], ['RESET_PASSWORD', '重置密码']
    ]
  },
  {
    label: '内容管理',
    items: [
      ['ADD_SENSITIVE_WORD', '新增敏感词'], ['BATCH_ADD_SENSITIVE_WORD', '批量导入敏感词'],
      ['DELETE_SENSITIVE_WORD', '禁用敏感词'],
      ['CREATE_FAQ', '新增 FAQ'], ['UPDATE_FAQ', '修改 FAQ'], ['DELETE_FAQ', '删除 FAQ'],
      ['CREATE_ANNOUNCEMENT', '发布公告'], ['UPDATE_ANNOUNCEMENT', '修改公告'],
      ['ARCHIVE_ANNOUNCEMENT', '归档公告'],
      ['CREATE_TEMPLATE', '新增模板'], ['UPDATE_TEMPLATE', '修改模板'],
      ['DELETE_TEMPLATE', '禁用模板']
    ]
  },
  { label: '其它', items: [['EXPORT', '导出数据']] }
];

/** 摊平成「码 → 中文」，给列表渲染用；查不到就原样显示英文码（不至于一片空白）。 */
const OP_TYPE_LABEL = {};
OP_TYPE_GROUPS.forEach(g => g.items.forEach(it => { OP_TYPE_LABEL[it[0]] = it[1]; }));

/** 操作对象的类型。后端只给英文码，这里翻成中文标签。 */
const TARGET_TYPE_LABEL = {
  WORK_ORDER: '工单',
  USER: '用户',
  SENSITIVE_WORD: '敏感词',
  ANNOUNCEMENT: '公告',
  TEMPLATE: '模板',
  FAQ: 'FAQ'
};

function viewAdminLogs() {
  const isOp = state.adminLogTab === 'operation';
  const q = state.adminLogQuery;
  const oq = state.adminLogOpQuery;

  appEl.innerHTML = `
    ${topbarHtml()}
    <div class="wrap">
      <div id="notice-slot">${alertHtml()}</div>
      <div class="row">
        <button class="btn" id="al-back" type="button">‹ 返回后台</button>
      </div>

      <h2 class="list-title">操作日志</h2>
      <p class="muted">「状态日志」看这张单经历了什么，「操作日志」看谁在什么时候动了什么。</p>

      <div class="card">
        <div class="row stats-presets" id="al-tabs">
          <button class="btn${isOp ? '' : ' btn-primary'}" type="button" data-tab="status">状态日志</button>
          <button class="btn${isOp ? ' btn-primary' : ''}" type="button" data-tab="operation">操作日志</button>
        </div>

        ${isOp ? `
        <div class="filters stats-dates">
          <input class="input" id="al-op-operator" type="text" maxlength="20" autocomplete="off"
                 placeholder="操作人工号" value="${esc(oq.operatorId)}">
          <select class="input" id="al-op-type" aria-label="操作类型">
            <option value="">全部操作类型</option>
            ${OP_TYPE_GROUPS.map(g => `
              <optgroup label="${esc(g.label)}">
                ${g.items.map(it =>
                  `<option value="${it[0]}"${it[0] === oq.operationType ? ' selected' : ''}>${esc(it[1])}</option>`).join('')}
              </optgroup>`).join('')}
          </select>
          <input class="input" id="al-op-start" type="date" value="${esc(oq.startDate)}" aria-label="起始日期">
          <span class="muted">至</span>
          <input class="input" id="al-op-end" type="date" value="${esc(oq.endDate)}" aria-label="截止日期">
          <button class="btn btn-primary" id="al-op-go" type="button">查询</button>
        </div>
        <p class="muted stats-hint">全部留空 = 最近的操作。截止日那一天整天都算。</p>
        ` : `
        <div class="filters stats-dates">
          <input class="input" id="al-wo" type="text" maxlength="20" autocomplete="off"
                 placeholder="按工单号查（如 WO20261005000001）" value="${esc(q.workOrderId)}">
          <button class="btn btn-primary" id="al-wo-go" type="button">查询</button>
        </div>
        <p class="muted stats-hint">留空 = 全部工单的流转记录（最新的排最前）。</p>
        `}
      </div>

      <div id="al-body"><div class="empty">正在加载…</div></div>
    </div>`;

  bindTopbar();
  document.getElementById('al-back').addEventListener('click', () => go('/admin'));
  document.getElementById('al-tabs').addEventListener('click', onLogTabClick);
  // 列表 + 分页器都在 #al-body 里，所以只绑这一个容器（事件委托），点什么都接得住
  document.getElementById('al-body').addEventListener('click', onLogBodyClick);

  const goBtn = document.getElementById(isOp ? 'al-op-go' : 'al-wo-go');
  if (goBtn) goBtn.addEventListener('click', onLogSearch);

  // 输入框里按回车 = 点查询。工单号是拿来复制的，纯键盘更顺手。
  // ⚠️ keydown 和 input 不一样，它**会冒泡**，所以这里可以逐个绑、不用委托
  //    （详情页那几个字数框踩过"input 不冒泡"的坑，是另一回事）。
  ['al-wo', 'al-op-operator'].forEach((id) => {
    const el = document.getElementById(id);
    if (el) el.addEventListener('keydown', (ev) => { if (ev.key === 'Enter') onLogSearch(); });
  });

  loadAdminLogs();
}

/** 切分段：换 tab 就重画一次（输入框里的值从 state 回填，不会丢）。 */
function onLogTabClick(e) {
  const btn = e.target.closest('button[data-tab]');
  if (!btn || btn.dataset.tab === state.adminLogTab) return;
  state.adminLogTab = btn.dataset.tab;
  // 展开态是"我正在看这一条"，切过去以后那条根本不在列表里了，留着没意义
  state.adminLogExpanded = '';
  setNotice(null, null);
  render();
}

/**
 * 点「查询」：把界面上的条件读回 state、**页码归 1**，再拉一次。
 *
 * ⚠️ 页码必须归 1：条件变窄之后，原来的第 5 页很可能是空的 ——
 *    用户看到"没有记录"，会以为真没数据，其实只是他还停在第 5 页。
 */
async function onLogSearch() {
  setNotice(null, null);

  if (state.adminLogTab === 'operation') {
    const oq = state.adminLogOpQuery;
    oq.operatorId = inputVal('al-op-operator');
    oq.operationType = document.getElementById('al-op-type').value;
    oq.startDate = document.getElementById('al-op-start').value;
    oq.endDate = document.getElementById('al-op-end').value;
    // 后端对这两种情况都回 400，没必要让用户专门跑一趟服务器。
    // ⚠️ 'YYYY-MM-DD' 直接比大小就是对的（字典序恰好等于日期先后）。
    if (oq.startDate && oq.endDate && oq.startDate > oq.endDate) {
      setNotice('error', '起始日期不能晚于截止日期');
      return;
    }
    oq.page = 1;
  } else {
    const q = state.adminLogQuery;
    q.workOrderId = inputVal('al-wo');
    q.page = 1;
  }
  state.adminLogExpanded = '';
  await loadAdminLogs();
}

/** 取一个输入框的值并 trim（节点不在了就给空串，免得切页面时报错）。 */
function inputVal(id) {
  const el = document.getElementById(id);
  return el ? el.value.trim() : '';
}

/** 按当前 tab 决定拉哪个接口。 */
async function loadAdminLogs() {
  if (state.adminLogTab === 'operation') return loadAdminOpLogs();
  return loadAdminStatusLogs();
}

/* ---------- ① 状态日志 ---------- */

async function loadAdminStatusLogs() {
  const box = document.getElementById('al-body');
  if (box) box.innerHTML = '<div class="empty">正在加载…</div>';

  const q = state.adminLogQuery;
  // 空串会被 qs() 丢掉 = 不筛这一维 = 全部工单
  const r = await api.adminStatusLogs({
    workOrderId: q.workOrderId, page: q.page, pageSize: q.pageSize
  });
  if (r.needLogin) { onNeedLogin('登录已失效，请重新登录'); return; }
  if (!r.ok) {
    if (box) box.innerHTML = '<div class="empty">加载失败：' + esc(r.message) + '</div>';
    return;
  }
  const d = r.data || {};
  state.adminLogList = d.list || [];
  state.adminLogTotal = d.total || 0;
  // ⚠️ 页数要拿**后端返回的** page/pageSize 算，不能拿请求值算（page=0 会被后端兜成 1）
  state.adminLogPage = d.page || 1;
  state.adminLogPageSize = d.pageSize || 20;
  paintStatusLogs();
}

function paintStatusLogs() {
  const box = document.getElementById('al-body');
  if (!box) return;

  if (!state.adminLogList.length) {
    box.innerHTML = '<div class="empty">' +
      (state.adminLogQuery.workOrderId
        ? '这张工单没有流转记录（工单号写错的话也是这个提示）。'
        : '还没有任何状态流转记录。') + '</div>';
    return;
  }
  box.innerHTML =
    '<p class="muted adm-count">共 ' + state.adminLogTotal + ' 条</p>' +
    state.adminLogList.map(statusLogRowHtml).join('') +
    logPagerHtml();
}

/**
 * 一条状态日志。
 *
 * ⚠️ `beforeStatus` 可能是 **null** —— 一张单的第一跳（学生提交）前面没有"旧状态"。
 *    后端给的就是 null，这里画成「—」，**绝不能渲染成字符串 "null"**
 *    （导出那页专门为此加过一条断言，是同一个道理）。
 * ⚠️ 状态的中文用已有的 `STATUS_LABEL`，别再维护一份 —— 两份早晚会不一致。
 */
function statusLogRowHtml(l) {
  const from = l.beforeStatus ? (STATUS_LABEL[l.beforeStatus] || l.beforeStatus) : '—';
  const to = l.afterStatus ? (STATUS_LABEL[l.afterStatus] || l.afterStatus) : '—';
  const act = LOG_ACTION_LABEL[l.action] || l.action || '—';
  return `
    <div class="card log-row">
      <div class="log-top">
        <span class="log-act">${esc(act)}</span>
        <span class="log-arrow">${esc(from)} → ${esc(to)}</span>
        <span class="spacer"></span>
        <span class="log-time">${esc(fmtFull(l.operationTime))}</span>
      </div>
      <div class="log-sum">
        工单 ${esc(l.workOrderId || '—')} · 操作人
        ${esc(l.operatorName || l.operatorId || '—')}（${esc(roleCn(l.operatorRole) || '—')}）
      </div>
      ${l.remark ? `<div class="log-sum log-remark">理由：${esc(l.remark)}</div>` : ''}
    </div>`;
}

/* ---------- ② 操作日志 ---------- */

async function loadAdminOpLogs() {
  const box = document.getElementById('al-body');
  if (box) box.innerHTML = '<div class="empty">正在加载…</div>';

  const oq = state.adminLogOpQuery;
  const r = await api.adminOperationLogs({
    operatorId: oq.operatorId,
    operationType: oq.operationType,
    startDate: oq.startDate,
    endDate: oq.endDate,
    page: oq.page,
    pageSize: oq.pageSize
  });
  if (r.needLogin) { onNeedLogin('登录已失效，请重新登录'); return; }
  if (!r.ok) {
    if (box) box.innerHTML = '<div class="empty">加载失败：' + esc(r.message) + '</div>';
    return;
  }
  const d = r.data || {};
  state.adminLogOpList = d.list || [];
  state.adminLogOpTotal = d.total || 0;
  state.adminLogOpPage = d.page || 1;
  state.adminLogOpPageSize = d.pageSize || 20;
  paintOpLogs();
}

function paintOpLogs() {
  const box = document.getElementById('al-body');
  if (!box) return;

  if (!state.adminLogOpList.length) {
    // ⚠️ 0 条**不一定是出错**：operationType 是不校验取值的，选了库里没有的类型也会安静地空。
    //    所以提示要往"换个条件试试"上引，而不是甩一句"加载失败"。
    box.innerHTML = '<div class="empty">没有符合这些条件的操作记录，换个条件再试试。</div>';
    return;
  }
  box.innerHTML =
    '<p class="muted adm-count">共 ' + state.adminLogOpTotal + ' 条</p>' +
    state.adminLogOpList.map(opLogRowHtml).join('') +
    logPagerHtml();
}

/**
 * 一条操作日志：**精简列 + 点开看详情**（用户拍板）。
 *
 * 收起来的不等于"不重要"，而是"不用每次都看"：IP、原始英文码、日志号这些
 * 放在展开区 —— 一屏能扫十几条，真要核对某一条时点开就有。
 *
 * ⚠️ `operationContent` 可能**很长**（导出的筛选条件、批量导入的那串敏感词都写在这），
 *    卡片里用 `.log-clip` 截到几行，完整内容在展开区用 `<pre>` 显示（保留换行）。
 */
function opLogRowHtml(l) {
  const open = state.adminLogExpanded === l.logId;
  const act = OP_TYPE_LABEL[l.operationType] || l.operationType || '—';
  const target = TARGET_TYPE_LABEL[l.targetType] || l.targetType || '';
  const content = l.operationContent || '';
  return `
    <div class="card log-row log-clickable" data-log="${esc(l.logId)}">
      <div class="log-top">
        <span class="log-act">${esc(act)}</span>
        ${target ? `<span class="tag">${esc(target)}</span>` : ''}
        <span class="spacer"></span>
        <span class="log-time">${esc(fmtFull(l.operationTime))}</span>
      </div>
      <div class="log-sum">
        操作人 ${esc(l.userName || l.userId || '—')}（${esc(roleCn(l.userRole) || '—')}）
        ${l.targetId ? ' · 对象 ' + esc(l.targetId) : ''}
      </div>
      ${content ? `<div class="log-sum log-clip">${esc(content)}</div>` : ''}
      ${open ? `
      <div class="log-detail">
        <div>日志号：${esc(l.logId || '—')}</div>
        <div>原始类型：${esc(l.operationType || '—')} / ${esc(l.targetType || '—')}</div>
        <div>IP 地址：${esc(l.ipAddress || '—')}</div>
        <div>完整内容：</div>
        <pre class="log-pre">${esc(content || '（无）')}</pre>
      </div>` : `
      <div class="log-hint">点这一条看详情（IP、原始类型、完整内容）</div>`}
    </div>`;
}

/**
 * 列表里的点击：**先判分页按钮，再判"点开了哪一条"**。
 *
 * 为什么合成一个处理器：#al-body 里既装着卡片又装着分页器，绑一个委托就够，
 * 分成两个反而要小心"点分页时别误展开某一条"。
 */
async function onLogBodyClick(e) {
  const pg = e.target.closest('#al-pager button');
  if (pg) { await onLogPagerClick(e); return; }

  const row = e.target.closest('[data-log]');
  if (!row) return;
  const id = row.dataset.log;
  state.adminLogExpanded = (state.adminLogExpanded === id) ? '' : id;
  // 只重画列表（顺手把分页器也重画了），**不重画整页** ——
  // 免得把上面的筛选条件、日期框整个重新渲染一遍
  paintOpLogs();
}

/* ---------- 分页器（两个 tab 共用） ---------- */

/**
 * 「上一页 / 下一页」。两个 tab 共用一份，靠当前 tab 决定翻哪一套页码。
 * 页数用**后端返回的** total / pageSize 算（后端会把 pageSize 夹到 1~50）。
 */
function logPagerHtml() {
  const isOp = state.adminLogTab === 'operation';
  const total = isOp ? state.adminLogOpTotal : state.adminLogTotal;
  if (!total) return '';
  const page = isOp ? state.adminLogOpPage : state.adminLogPage;
  const size = isOp ? state.adminLogOpPageSize : state.adminLogPageSize;
  const pages = Math.max(1, Math.ceil(total / size));
  return `
    <div class="pager" id="al-pager">
      <button class="btn" id="al-prev" type="button"${page <= 1 ? ' disabled' : ''}>上一页</button>
      <span class="muted">第 ${page} / ${pages} 页 · 共 ${total} 条</span>
      <button class="btn" id="al-next" type="button"${page >= pages ? ' disabled' : ''}>下一页</button>
    </div>`;
}

async function onLogPagerClick(e) {
  const btn = e.target.closest('button');
  if (!btn || btn.disabled) return;

  const isOp = state.adminLogTab === 'operation';
  const cur = isOp ? state.adminLogOpPage : state.adminLogPage;
  if (btn.id === 'al-prev') {
    const to = Math.max(1, cur - 1);
    if (isOp) state.adminLogOpQuery.page = to; else state.adminLogQuery.page = to;
  } else if (btn.id === 'al-next') {
    const to = cur + 1;
    if (isOp) state.adminLogOpQuery.page = to; else state.adminLogQuery.page = to;
  } else {
    return;
  }
  setNotice(null, null);
  await loadAdminLogs();
  // 翻页后滚回顶部，否则会停在"上一页的底部"，看到的是新一页的中间
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

/* ---------- 十二、路由：决定显示哪个页面 ---------- */

function render() {
  // 每次切页前先把上一页的点线动画停掉：
  // 页面内容会被整体替换，旧 canvas 从 DOM 上脱落后，它那圈
  // requestAnimationFrame 还会一直跑（画在一块没人看的画布上），
  // 切几次页面就多几圈 —— 这是最隐蔽的卡顿来源。
  destroyPlexus();

  // 没登录 → 只有**白名单里这两个**能进（用户拍板：公告和 FAQ 不登录也能看），
  // 其余一律回登录页。
  //
  // ⚠️ 这份名单必须和后端 `JwtInterceptor.OPTIONAL_AUTH_PREFIXES` **一一对应**
  //    （后端放行的是 `/api/faqs` 和 `/api/announcements` 两个前缀）：
  //    · 这里多了 → 页面进得去、接口 401，游客看到一片空白；
  //    · 这里少了 → 接口调得通但页面被挡在登录页，等于白放行。
  //    ⚠️ 用**白名单**而不是"排除登录页"的黑名单：以后加新页面，默认是不放行的，
  //       不会被顺手一起放出去。
  if (!state.me) {
    const publicRoute = currentRoute();
    if (publicRoute === '/register') {
      document.title = '学生注册 · 第二课堂学时答疑';
      viewRegister();
      return;
    }
    if (publicRoute === '/forgot-password') {
      document.title = '找回密码 · 第二课堂学时答疑';
      viewForgotPassword();
      return;
    }
    if (publicRoute === '/announcements' || publicRoute.indexOf('/announcements/') === 0) {
      const focusId = publicRoute.indexOf('/announcements/') === 0
        ? decodeURIComponent(publicRoute.slice('/announcements/'.length))
        : '';
      document.title = '公告 · 第二课堂学时答疑';
      viewAnnouncements(focusId);
      return;
    }
    if (publicRoute === '/faqs' || publicRoute.indexOf('/faqs/') === 0) {
      const focusId = publicRoute.indexOf('/faqs/') === 0
        ? decodeURIComponent(publicRoute.slice('/faqs/'.length))
        : '';
      document.title = '常见问题 · 第二课堂学时答疑';
      viewFaqs(focusId);
      return;
    }
    document.title = '登录 · 第二课堂学时答疑';
    viewLogin();
    return;
  }

  const route = currentRoute();

  // 首页资讯卡使用带 ID 的深链接；进入完整页面后自动定位并高亮目标。
  if (route.indexOf('/announcements/') === 0) {
    const id = decodeURIComponent(route.slice('/announcements/'.length));
    document.title = '公告 · 第二课堂学时答疑';
    viewAnnouncements(id);
    return;
  }
  if (route.indexOf('/faqs/') === 0) {
    const id = decodeURIComponent(route.slice('/faqs/'.length));
    document.title = '常见问题 · 第二课堂学时答疑';
    viewFaqs(id);
    return;
  }

  // 详情页的地址是带单号的（#/orders/WO20261005000001），所以得**先**按前缀判一下：
  // 下面的 switch 只能精确匹配固定字符串，接不住这种"半固定"的地址。
  if (route.indexOf('/orders/') === 0) {
    const id = decodeURIComponent(route.slice(8));   // '/orders/' 正好 8 个字符
    if (!id) { go('/'); return; }                    // #/orders/ 这种空单号，直接回列表
    document.title = '工单详情 · 第二课堂学时答疑';
    viewOrderDetail(id);
    return;
  }

  // 「再次咨询」的表单地址：#/submit/from/WO20261005000001
  // ⚠️ 老单号必须放在**地址里**而不是内存里 —— 按 F5 刷新会清空内存，
  //    那样用户填完描述一点提交，就会变成"新建一张普通工单"（而且新单看起来也正常，
  //    他不一定能察觉）。放进地址，刷新、前进、后退都还在。
  // 注意它必须排在下面 switch 的 '/submit' 之前判断：switch 是精确匹配，
  // 接不住这种带尾巴的地址。
  if (route.indexOf('/submit/from/') === 0) {
    const id = decodeURIComponent(route.slice(13));   // '/submit/from/' 正好 13 个字符
    if (!id) { go('/submit'); return; }               // 尾巴是空的 → 退回普通提交页
    document.title = '再次咨询 · 第二课堂学时答疑';
    viewSubmit(id);
    return;
  }

  switch (route) {
    case '/password':
      document.title = '修改密码 · 第二课堂学时答疑';
      viewPassword();
      break;
    case '/submit':
      document.title = '提交工单 · 第二课堂学时答疑';
      viewSubmit();
      break;
    case '/notifications':
      document.title = '消息通知 · 第二课堂学时答疑';
      viewNotifications();
      break;
    case '/announcements':
      document.title = '公告 · 第二课堂学时答疑';
      viewAnnouncements();
      break;
    case '/faqs':
      document.title = '常见问题 · 第二课堂学时答疑';
      viewFaqs();
      break;
    // ---- 管理后台：非管理员一律送回工单列表 ----
    // 后端才是真正的防线（Service 里那句 role 校验会返回 403）；
    // 前端挡这一下是为了"别让他看见一堆点了必定报错的按钮"。
    case '/admin':
      if (!isAdmin()) { go('/'); return; }
      document.title = '管理后台 · 第二课堂学时答疑';
      viewAdmin();
      break;
    case '/admin/users':
      if (!isAdmin()) { go('/'); return; }
      document.title = '用户管理 · 第二课堂学时答疑';
      viewAdminUsers();
      break;
    case '/admin/words':
      if (!isAdmin()) { go('/'); return; }
      document.title = '敏感词 · 第二课堂学时答疑';
      viewAdminWords();
      break;
    case '/admin/templates':
      if (!isAdmin()) { go('/'); return; }
      document.title = '回复模板 · 第二课堂学时答疑';
      viewAdminTemplates();
      break;
    case '/admin/stats':
      if (!isAdmin()) { go('/'); return; }
      document.title = '数据统计 · 第二课堂学时答疑';
      viewAdminStats();
      break;
    case '/admin/export':
      if (!isAdmin()) { go('/'); return; }
      document.title = '数据导出 · 第二课堂学时答疑';
      viewAdminExport();
      break;
    case '/admin/logs':
      if (!isAdmin()) { go('/'); return; }
      document.title = '操作日志 · 第二课堂学时答疑';
      viewAdminLogs();
      break;
    default:
      // 地址栏里是别的（或什么都没有）→ 一律显示"我的工单"列表，
      // 也就是登录后的首页。以后有别的页面，就在这里加一个 case。
      document.title = listTitle() + ' · 第二课堂学时答疑';
      viewMyOrders();
      break;
  }
}

/* ---------- 十二、启动 ---------- */

async function boot() {
  // 刷新页面时，浏览器里可能还留着上次的 Token —— 先拿去问后端"这个 Token 还有效吗"。
  // 有效就直接进主页（不用重新登录）；无效就清掉。
  if (getToken()) {
    const r = await api.me();
    if (r.ok) {
      state.me = r.data;
    } else {
      clearToken();
      localStorage.removeItem(NOW_CHANGE_KEY);
    }
  }
  window.addEventListener('hashchange', render);
  // 「点别处收起下拉」这个监听器绑在 document 上，**只能绑一次** ——
  // 所以它在这里绑，而不是在每次渲染都会跑的 bindTopbar() 里（那会越积越多）。
  bindMoreOutsideClick();
  render();
  // 登录着才轮询未读通知。没登录（正停在登录页）时没必要每隔 15 秒去打一次接口。
  if (state.me) startUnreadPolling();
}

boot();
