/* =============================================================
   api.js —— 前端唯一一个"跟后端说话"的文件。

   为什么要单独一层（和我讲后端时一样的分层思路）：
     页面里到处直接写 fetch 的话，Token 怎么带、错误怎么读、401 怎么办，
     就要在每个页面重复一遍，改一次要改十处。
     全部收在这里，页面只管调 api.login(...) / api.myWorkOrders(...)。

   ⚠️ 用这个后端前必须知道的一条约定：
     业务失败时 **HTTP 状态码仍然是 200**，真正的错误写在返回 body 的 code 里
     （只有 401/403 是拦截器真的改了 HTTP 状态码）。
     所以这里必须**两层都看**：先看 resp.ok，再看 json.code。
     只看 resp.ok 的话，所有业务错误都会被当成成功 —— 这是最容易踩的坑。
   ============================================================= */

const TOKEN_KEY = 'sc_token';
const DEVICE_KEY = 'sc_device_token';

/** 前后端同源（都由这个 Spring Boot 服务提供），所以直接用相对路径，不用写域名。 */
const BASE = '';

/* ---------- Token 的存取 ---------- */

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || '';
}

export function setToken(token) {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
}

export function getDeviceToken() {
  return localStorage.getItem(DEVICE_KEY) || '';
}

export function setDeviceToken(token) {
  if (token) localStorage.setItem(DEVICE_KEY, token);
}

/* ---------- 底层：发一个请求，把结果统一成 { ok, message, data, httpStatus } ---------- */

export async function request(method, url, body) {
  const headers = {};
  const token = getToken();
  if (token) {
    headers['Authorization'] = 'Bearer ' + token;
  }

  let payload;
  if (body instanceof FormData) {
    // 上传文件：**绝对不要**自己设 Content-Type。
    // 浏览器需要自动加上 multipart 的分隔符（boundary），
    // 手写 Content-Type 会把 boundary 丢掉，后端就解不出文件了。
    payload = body;
  } else if (body !== undefined && body !== null) {
    headers['Content-Type'] = 'application/json';
    payload = JSON.stringify(body);
  }

  let resp;
  try {
    resp = await fetch(BASE + url, { method, headers, body: payload });
  } catch (e) {
    // fetch 只在"根本没连上"时抛异常（断网、服务没起、被代理拦了）
    return { ok: false, message: '网络连接失败，请检查网络后重试', httpStatus: 0, data: null };
  }

  // 401：Token 没有 / 过期了 / 已经登出过（登出黑名单）
  if (resp.status === 401) {
    clearToken();
    return { ok: false, message: '登录已失效，请重新登录', httpStatus: 401, data: null, needLogin: true };
  }

  // 403：账号被管理员禁用了
  if (resp.status === 403) {
    let msg = '账号已被禁用，请联系管理员';
    try {
      const j = await resp.json();
      if (j && j.message) msg = j.message;
    } catch (e) { /* 读不出就用默认话术 */ }
    return { ok: false, message: msg, httpStatus: 403, data: null };
  }

  // 其他非 2xx：503（探活失败那种）、500（兜底错误页）…
  if (!resp.ok) {
    return { ok: false, message: '服务器返回 HTTP ' + resp.status, httpStatus: resp.status, data: null };
  }

  // ---- 到这里 HTTP 是 200，但业务可能仍然是失败的，还要再解一层 ----
  let json;
  try {
    json = await resp.json();
  } catch (e) {
    return { ok: false, message: '服务器返回的内容不是合法 JSON', httpStatus: resp.status, data: null };
  }

  if (json.code === 0) {
    return { ok: true, message: json.message || '', data: json.data, httpStatus: 200, code: 0 };
  }
  return {
    ok: false,
    message: json.message || ('请求失败（code=' + json.code + '）'),
    httpStatus: 200,
    data: json.data,
    // 把后端的业务错误码也带出去。调用方少数时候需要按码分流，
    // 比如「确认解决」遇到 409 说明工单被别处改过了 → 要重新加载详情而不是干瞪眼。
    // 靠 message 文案去判断太脆（后端改一个字就失效）。
    code: json.code
  };
}

/* ---------- 下载专用：/api/files/{id} 吐的是文件字节，不是 JSON ---------- */

/**
 * 为什么下载附件不能直接用 <a href="/api/files/xxx"> ？
 *   因为那个接口需要 Token，而浏览器直接跳链接时**不会带 Authorization 头**，
 *   必然 401。所以只能先 fetch 下来拿到字节，再在前端"假装成一个文件"让浏览器保存。
 */
export async function requestBlob(url) {
  const headers = {};
  const token = getToken();
  if (token) headers['Authorization'] = 'Bearer ' + token;

  let resp;
  try {
    resp = await fetch(BASE + url, { headers });
  } catch (e) {
    return { ok: false, message: '网络连接失败' };
  }
  if (resp.status === 401) {
    clearToken();
    return { ok: false, message: '登录已失效，请重新登录', needLogin: true };
  }
  if (!resp.ok) {
    return { ok: false, message: '下载失败（HTTP ' + resp.status + '）' };
  }
  return { ok: true, blob: await resp.blob(), contentType: resp.headers.get('Content-Type') || '' };
}

/** 把已经拿到的字节变成一次"下载"动作。 */
export function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename || 'download';
  document.body.appendChild(a);
  a.click();
  a.remove();
  // 用完要释放，否则这块内存在页面关掉之前一直被占着
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}

/* =============================================================
   具体接口。以后加功能就在这里往下追加。
   ============================================================= */

/**
 * 把 { page:1, status:'', keyword:'abc' } 拼成 "?page=1&keyword=abc"。
 *
 * 为什么要单独写一个：值为**空**的项必须**丢掉**，不能拼成 status= 发出去。
 * 后端把 ``status=``（空串）当成"要筛空状态"，而合法的状态里没有空串，
 * 于是直接 400 报错。所以"没选就不传"这件事必须在这里统一处理，
 * 否则每加一个筛选条件都要在每个页面里重复判断一遍。
 */
function qs(params) {
  const parts = [];
  Object.keys(params || {}).forEach(function (k) {
    const v = params[k];
    if (v === undefined || v === null || v === '') return;
    parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(v));
  });
  return parts.length ? '?' + parts.join('&') : '';
}

export const api = {

  /* ---------- 认证（⑩ 第一步只用到这 4 个）---------- */

  /** 登录。成功时 data = { token, userId, userName, role, needChangePassword } */
  login(userId, password, deviceToken) {
    return request('POST', '/api/auth/login', { userId, password, deviceToken });
  },

  verifyLoginSms(challengeId, smsCode) {
    return request('POST', '/api/auth/login/sms/verify', { challengeId, smsCode });
  },

  sendRegisterSmsCode(userId, userName, phone) {
    return request('POST', '/api/auth/register/sms-code', { userId, userName, phone });
  },

  register(userId, userName, phone, smsCode, password, agreedToPrivacy) {
    return request('POST', '/api/auth/register', {
      userId, userName, phone, smsCode, password, agreedToPrivacy
    });
  },

  sendResetSmsCode(userId) {
    return request('POST', '/api/auth/password/reset/sms-code', { userId });
  },

  resetPassword(userId, smsCode, newPassword) {
    return request('POST', '/api/auth/password/reset', { userId, smsCode, newPassword });
  },

  /** 当前登录人。data = { userId, userName, role, openId, subscribeStatus } */
  me() {
    return request('GET', '/api/auth/me');
  },

  /** 登出。注意：后端改成"永不失败"了 —— 所以调用方不管成功失败都要清本地 Token。 */
  logout() {
    return request('POST', '/api/auth/logout');
  },

  /** 改密码。新密码要求 6-20 位且同时含字母和数字（服务端校验）。 */
  changePassword(oldPassword, newPassword) {
    return request('PUT', '/api/auth/password', { oldPassword, newPassword });
  },

  sendPhoneSmsCode(target, newPhone) {
    return request('POST', '/api/auth/phone/sms-code', { target, newPhone });
  },

  changePhone(newPhone, oldSmsCode, newSmsCode) {
    return request('PUT', '/api/auth/phone', { newPhone, oldSmsCode, newSmsCode });
  },

  /* ---------- 工单（⑩ 第二步：我的工单列表）---------- */

  /**
   * 工单列表。成功时 data = { total, page, pageSize, list: [...] }。
   *
   * ⚠️ 这里**不传 userId / role** —— 后端是从 Token 里读的。
   *    前端要是能自己指定"看谁的工单"，那权限就形同虚设了。
   *    所以"学生只看自己的、干事看待接单+自己的、管理员看全部"这件事，
   *    完全由后端根据 Token 决定，前端只管传筛选条件。
   *
   * @param params { page, pageSize, status, category, keyword }，没填的项会被 qs() 丢掉
   */
  workOrders(params) {
    return request('GET', '/api/work-orders' + qs(params));
  },

  /**
   * 工单详情（⑩ 第四步）。
   * 成功时 data 里除了工单本身的字段，还多三块明细：
   *   · messages      —— 消息时间线（每条自带 attachments）
   *   · attachments   —— **工单级**附件（提交时带上来的那些）
   *   · statusHistory —— 状态流转历史
   *
   * 权限由后端判：学生只能看自己的单、干事只能看"待接单 / 自己接的"、管理员全看。
   * 没权限时后端返回 403/404，前端只管显示消息。
   */
  workOrderDetail(id) {
    return request('GET', '/api/work-orders/' + encodeURIComponent(id));
  },

  /* ---------- 提交工单 & 附件上传（⑩ 第三步）---------- */

  /**
   * 提交工单。成功时 data = { workOrderId, status, isDuplicate, duplicateOfOrderId }。
   *
   * @param body { category, content, requestId, attachmentIds }
   *   · requestId —— 前端生成的 uuid，用来做幂等。**重试同一个动作必须沿用同一个值**，
   *     否则用户点两下就可能建出两张单。什么时候该换新的，见 app.js 里 onSubmit 的注释。
   *   · attachmentIds —— 先调 uploadFile() 拿到的 id 列表；没有附件就传空数组。
   */
  submitWorkOrder(body) {
    return request('POST', '/api/work-orders', body);
  },

  /**
   * 上传一个附件。成功时 data = { attachmentId, fileUrl, fileName, fileSize }。
   *
   * 为什么参数是 FormData、不是普通对象：
   *   文件必须走 multipart/form-data，而其中的分隔符（boundary）只能由**浏览器自己**生成。
   *   request() 里已经做了"body 是 FormData 就绝不手写 Content-Type"这一条 ——
   *   手写会把 boundary 抹掉，后端就解不出文件。
   *
   * ⚠️ 字段名必须叫 `file`（后端是 @RequestParam("file")）。
   */
  uploadFile(file) {
    const fd = new FormData();
    fd.append('file', file);
    return request('POST', '/api/upload', fd);
  },

  /* ---------- 通知中心（⑩ 第五步：消息通知）---------- */

  /**
   * 我的通知列表。成功时 data = { total, page, pageSize, list: [...] }。
   *
   * 后端**故意**把这个结构做得和工单列表一模一样，所以上面那套分页逻辑可以照搬过来用。
   *
   * @param params { page, pageSize, isRead }
   *   · isRead —— false = 只看未读，true = 只看已读，**不传**（空串）= 全部。
   *     ⚠️ 注意"全部"要用空串（qs() 会把它丢掉、等于不传这个参数），
   *        不能传字符串 'false' 来蒙混，否则后端会真的按"只看未读"筛。
   */
  notifications(params) {
    return request('GET', '/api/notifications' + qs(params));
  },

  /** 未读通知数（顶部那个红点）。成功时 data = { unreadCount: 5 }。 */
  notificationUnreadCount() {
    return request('GET', '/api/notifications/unread-count');
  },

  /**
   * 把一条通知标为已读。成功时 data = { notifyId, markedCount }。
   * 只能标自己的：标别人的会得到 404（后端故意用 404 而不是 403，免得暴露"这条存在"）。
   */
  markNotificationRead(notifyId) {
    return request('POST', '/api/notifications/' + encodeURIComponent(notifyId) + '/read');
  },

  /**
   * 把**所有**通知标为已读。data = { notifyId: null, markedCount }。
   * ⚠️ markedCount = 0 表示"本来就没有未读"，是正常情况，**不是错误**，别当失败处理。
   */
  markAllNotificationsRead() {
    return request('POST', '/api/notifications/read-all');
  },

  /**
   * 把"某一张工单"的未读一次清掉 —— 进工单详情页时调。
   *
   * ⚠️ 它一次做**两件事**（后端在同一个事务里做的，为了"红点一致清除"）：
   *    ① 该工单的**消息**标已读  → 清掉工单列表卡片上那个红点
   *    ② 该工单相关的**通知**标已读 → 清掉顶部「消息」红点里的那几条
   *    所以调完之后，顶部未读数是"少了几条"，通常**不会**归零 ——
   *    想知道准确数字只能重新调一次 notificationUnreadCount()。
   */
  markWorkOrderMessagesRead(id) {
    return request('POST', '/api/work-orders/' + encodeURIComponent(id) + '/messages/read');
  },

  /* ---------- 学生端两个写操作（⑩ 第七步：追问 / 确认解决）---------- */

  /**
   * 学生追问。成功时 data = { messageId, workOrderId, status, statusLabel,
   *                          staffUnreadCount, version }。
   *
   * 业务前置：工单得处于「已回复(REPLIED)」—— 追问的意思是"答复我不满意，接着说"。
   * 效果：状态**退回 PROCESSING**（干事得重新回复一遍）、干事的未读数 +1。
   * ⚠️ 这是契约的设计，不是 bug：追问 = 重新打开。
   *
   * ⚠️ 请求体 { content, requestId, attachmentIds } —— **没有 version**。
   *    契约文档的示例里写了 version，但后端 WorkOrderFollowUpRequest 根本没有这个字段
   *    （实测：不传成功；多传了也会被忽略）。这个接口的并发保护是在 SQL 里用
   *    `AND status='REPLIED'` 兜的，不靠乐观锁版本号。
   */
  followUp(id, body) {
    return request('POST', '/api/work-orders/' + encodeURIComponent(id) + '/follow-up', body);
  },

  /**
   * 学生确认解决 → 工单关闭（CLOSED，同时写 finish_time）。**不可逆**。
   * 成功时 data = { workOrderId, status, statusLabel, action: 'CONFIRM',
   *                finishTime, version }。
   *
   * ⚠️ **必须**传 version（就是详情接口返回的那个）：
   *    · 不传 / 传 null → 业务码 400「缺少 version 参数」
   *    · 传的版本对不上 → 业务码 409「版本冲突…（WO_VERSION_CONFLICT）」
   *      意思是"你看到的工单已经被人改过了"，前端应当重新加载详情再让用户确认，
   *      而不是把这个错误甩给用户看不懂。
   */
  confirmWorkOrder(id, version) {
    return request('POST', '/api/work-orders/' + encodeURIComponent(id) + '/confirm',
      { version: version });
  },

  /**
   * 学生「再次咨询」：拿一张**已完结**的老单再提一张新单（⑩ 第九步）。
   * 成功时 data = { workOrderId（新单）, sourceOrderId（老单） }。
   *
   * ⚠️ 它和「提交工单」长得几乎一样（请求体字段完全相同），但**别混用**：
   *    · 提交工单 `POST /api/work-orders`      → 无中生有一张新单
   *    · 再次咨询 `POST /{老单号}/re-consult`  → 新单会写 `source_order_id = 老单号`、
   *      `relation_type = 'RE_CONSULTATION'`，前端才能画出"这张单是从哪来的"
   *
   * ⚠️ **老单只被读、不被改**（它已经是 CLOSED 终态），所以请求体里**没有 version**。
   *
   * ⚠️ 报错码有三个要单独处理：
   *    · `code=403` 只有老单的**提交人本人**能再次咨询
   *    · `code=409` 老单不是 CLOSED（「仅已完结工单可再次咨询」），或 requestId 被别的内容占了
   *    · `code=429` ⚠️ **7 天内同一张老单只能再来一次**（`RE_CONSULTATION_LIMIT`）。
   *      这是全站唯一一个 429 —— 前端要专门认它，给一句人话（不能把
   *      「RE_CONSULTATION_LIMIT」这种英文码甩给用户）。
   *      ⚠️ 前端**无法提前预判**这个限制：列表和详情接口都不返回"最近是否咨询过"，
   *         只能点下去才知道（用户拍板接受的取舍）。
   *
   * @param sourceOrderId 老单号
   * @param body { category, content, requestId, attachmentIds } —— 分类**可以和老单不同**
   */
  reConsult(sourceOrderId, body) {
    return request('POST',
      '/api/work-orders/' + encodeURIComponent(sourceOrderId) + '/re-consult', body);
  },

  /* ---------- FAQ / 公告（⑩ 第六步：公开信息页）---------- */

  /**
   * FAQ（常见问题）列表。成功时 data 是一个**数组**，不是 { total, list } 这种分页对象 ——
   * 契约里这个接口压根没有分页参数，FAQ 总量只有几十条。
   *
   * @param params { category, keyword }
   *   ⚠️ category 只能传**合法分类**（学时录入/审核驳回/活动咨询/规则疑问/其他）。
   *      后端对非法分类返回 400，不是"静默给个空列表"。
   *      "全部"要靠空串让 qs() 把这个参数丢掉 —— 千万别传 '全部' 这种中文进去。
   *   ⚠️ 列表项里**没有 answer**：答案只在详情接口给（顺带把浏览量 +1），
   *      所以要"看答案"就必须再调一次 faqDetail()。
   */
  faqs(params) {
    return request('GET', '/api/faqs' + qs(params));
  },

  /**
   * FAQ 详情（比列表多一个 answer）。
   *
   * ⚠️ 每调一次，后端就把 viewCount +1 —— 这是**刻意**的：
   *    列表不发答案，逼着"想看答案"必须走这里，浏览量这个字段才有意义。
   *    所以前端不能在页面加载时批量预取所有答案，否则一屏就把浏览量刷爆了。
   */
  faqDetail(faqId) {
    return request('GET', '/api/faqs/' + encodeURIComponent(faqId));
  },

  /**
   * 公告列表。同样是**数组**。
   *
   * 后端只会返回「展示中(ACTIVE) 且没有过期」的公告，并且已经按 sortNum 升序排好
   * （sortNum 相同才看发布时间倒序）—— 前端不用再筛一遍、也不用再排一遍。
   * 管理端才用得上 ?status=ALL / ARCHIVED 把归档的翻出来，这里是学生端，不传。
   */
  announcements() {
    return request('GET', '/api/announcements');
  },

  /* ---------- 干事端四个写操作（⑩ 第八步：接单 / 回复 / 完结 / 退回）---------- */

  /**
   * 干事接单（受理）。成功时 data = { workOrderId, status, statusLabel,
   *                                handlerId, handlerName, acceptTime, version }。
   *
   * 效果：状态 PENDING → PROCESSING、把"当前处理人"写成本人、version +1。
   *
   * ⚠️ 后端 SQL 的条件是 `status='PENDING' AND handler_id IS NULL`，
   *    所以两个人同时点「接单」，**只有一个能成功**，另一个拿到 409。
   *    这个并发保护是数据库给的，前端不用自己写锁。
   *
   * @param version 详情接口返回的 version。
   *   后端允许不传（不传就不加版本条件），但**建议传** —— 传了我们才能知道
   *   "我看到的这版单子"和"库里的"是不是同一版。
   *   ⚠️ 传错了，后端报的是 409「该工单已被受理或状态不允许受理」，
   *      而不是像 close/return 那样的「版本冲突」文案（两种失败在实现里合并成同一句）。
   */
  acceptWorkOrder(id, version) {
    return request('POST', '/api/work-orders/' + encodeURIComponent(id) + '/accept',
      { version: version });
  },

  /**
   * 干事回复。成功时 data = { messageId, workOrderId, status, statusLabel, replyTime, version }。
   *
   * 效果：插一条 REPLY 消息 + 状态 → REPLIED + 学生未读数 +1 + version +1。
   * 学生追问后状态会退回 PROCESSING，所以这个接口可能被调用多次（一问一答）。
   *
   * ⚠️ 请求体 { content, requestId } —— **没有 version**。
   *    契约文档的示例里写了 version，但后端 WorkOrderReplyRequest **根本没有这个字段**
   *    （实测：传 version=999 照样成功，字段被完全忽略）。
   *    它的并发保护是在 SQL 里用 `AND status IN ('PROCESSING','REPLIED')` 兜的。
   *
   * ⚠️ 和契约的第二个出入：文档说"状态必须是 PROCESSING，否则 409"，
   *    但实现**故意也允许 REPLIED** —— 因为干事对同一条单"补充回复"是正常业务
   *    （源码注释原话：「干事对同一条单补充回复时，before 其实已经是 REPLIED 了」）。
   *    只挡 PENDING（「请先受理」）和 CLOSED/MERGED（「已关闭或已合并」）。
   *
   * ⚠️ 幂等：这个接口外面套了 IdempotencyStore，而它的请求指纹 =
   *    SHA-256(userId + "REPLY" + workOrderId + content)。
   *    所以 **同一个 requestId 必须配同一段 content**：
   *      · 同号 + 同内容 → 直接返回第一次的结果（这才是我们要的"重试"）
   *      · 同号 + 不同内容 → 409「请求ID已被其他请求占用（IDEMPOTENCY_CONFLICT）」
   *    实践上的做法就是：**失败不换号，成功才换号**（内容没变过，所以不会踩坑）。
   *
   * @param body { content, requestId }
   */
  replyWorkOrder(id, body) {
    return request('POST', '/api/work-orders/' + encodeURIComponent(id) + '/reply', body);
  },

  /**
   * 干事端「AI 回复建议」。**只生成草稿，不发送、不写库、不改状态**。
   * 成功时 data = { workOrderId, mode, reason, suggestion, sources[], elapsedMs, model }。
   *
   * mode 有两种，前端都要能显示：
   *   AI          —— 真模型生成的草稿
   *   SEARCH_ONLY —— 没调模型（未开启/没配密钥/模型挂了），suggestion 是相关 FAQ 答案的拼接
   * 这两种**都是 code=0**：辅助功能不可用不算业务失败，不要按错误提示用户。
   */
  aiSuggestion(id) {
    return request('POST', '/api/work-orders/' + encodeURIComponent(id) + '/ai-suggestion', {});
  },

  /**
   * 干事手动完结 → 工单关闭（CLOSED，同时写 finish_time）。**不可逆**。
   * 成功时 data = { workOrderId, status, statusLabel, action: 'MANUAL_CLOSE',
   *                finishTime, version }。
   *
   * 允许的状态：PROCESSING 或 REPLIED（学生已经确认过的单，干事不必再管）。
   * 只挡 PENDING（没人接，谈不上完结）和已 CLOSED/MERGED 的单。
   *
   * ⚠️ **必须**传 version，和 confirmWorkOrder 完全一样：
   *    · 不传 → 400「缺少 version 参数」
   *    · 版本对不上 → 409「版本冲突，请刷新后重试（WO_VERSION_CONFLICT）」
   * 这个接口**没有**幂等包装（accept/close/return 都没有），所以不用传 requestId。
   */
  closeWorkOrder(id, version) {
    return request('POST', '/api/work-orders/' + encodeURIComponent(id) + '/close',
      { version: version });
  },

  /**
   * 退回工单 → 丢回待接单池。
   * 成功时 data = { workOrderId, status, statusLabel, remark, version }。
   *
   * 效果：状态回到 PENDING、**把 handler_id / handler_name 清空**、version +1。
   * 也就是说这张单又变成"谁都能接"了，所以只允许从 PROCESSING 退回 ——
   * 已经从 REPLIED 的单不能退回（学生已经拿到答复了，反悔要走别的流程）。
   *
   * ⚠️ **必须**传 version（不传 → 400「缺少 version 参数」；对不上 → 409 版本冲突）。
   * ⚠️ remark（退回理由）后端**不校验**、可以不传，但前端会强制要求填 ——
   *    因为系统消息会把理由原样展示给学生，没理由的话学生只看到一句
   *    「XX 退回工单」，一头雾水。
   *
   * @param body { remark, version }
   */
  returnWorkOrder(id, body) {
    return request('POST', '/api/work-orders/' + encodeURIComponent(id) + '/return', body);
  },

  /**
   * 管理员强制关闭（⑩ 第十步）。**不可逆**。
   * 成功时 data = { workOrderId, status: 'CLOSED', statusLabel, action: 'ADMIN_CLOSE',
   *                finishTime, version }。
   *
   * 和干事的 closeWorkOrder 是**同一个后端方法**（closeOrder），
   * 差别只有三处：谁能调（仅 ADMIN）、允许的前置状态（仅 PENDING）、remark 必填。
   * 所以返回结构一模一样，只有 action 从 'MANUAL_CLOSE' 变成 'ADMIN_CLOSE'。
   *
   * ⚠️ **必须**传 version：不传 → 400「缺少 version 参数」；
   *    对不上 → 409「版本冲突，请刷新后重试（WO_VERSION_CONFLICT）」。
   *
   * ⚠️ **remark 必填**（后端会校验，空串和"全空格"都算空 → 400「关闭原因不能为空」）。
   *    而且它**有隐含长度上限**：理由最终要写进 t_status_log.remark，
   *    那一列是 VARCHAR(500) —— 实测传 660 个字直接 500「数据库操作失败」。
   *    所以前端把输入框压在 200 字（见 adminActionCardHtml 的 maxlength），留足余量。
   *
   * ⚠️ 这个接口**没有**幂等包装（和 accept/close/return 一样），不用传 requestId：
   *    实测带上一个 requestId 也照常成功，说明它完全没被用到。
   *
   * @param body { remark, version }
   */
  adminCloseWorkOrder(id, body) {
    return request('POST', '/api/work-orders/' + encodeURIComponent(id) + '/admin-close', body);
  },

  /**
   * 合并重复工单（⑩ 第十一步）。**不可逆**：本单会变成「已合并」只读终态。
   * 成功时 data = { workOrderId, status: 'MERGED', statusLabel: '已合并',
   *                mainOrderId, mergeId, mergeTime, version }。
   *
   * ⚠️ 方向别写反：URL 里的 {id} 是**被合并的子单**（正在看的这张），
   *    body 里的 mainOrderId 才是**要并进去的主工单**。合并后"死"掉的是 URL 里那张。
   *    后端返回的 mainOrderId 就是放进来的那个值（它原样回显，方便前端核对）。
   *
   * ⚠️ 请求体**只有** mainOrderId + version 两个字段（见 WorkOrderMergeRequest）：
   *    · 没有 remark —— 后端不写合并理由，t_status_log 的 remark 传 null；
   *    · 没有 requestId —— 这个接口**没有幂等包装**（全项目只有 submit / reply /
   *      follow-up / re-consult 四个有），实测塞一个进去会被**完全忽略**。
   *
   * ⚠️ version 必须传，而且是**子单**的 version（主单的 version 全程没被碰过）：
   *    不传 → 400「缺少 version 参数」；对不上 → 409「版本冲突…（WO_VERSION_CONFLICT）」。
   *
   * ⚠️ 后端依次会拦（前端不重复判断，一律"刷新后重试"）：
   *    自己合并自己 → 400；角色不是 STAFF/ADMIN → 403；
   *    干事碰到自己不管的、且不是待接单的单 → 403；
   *    子单是 CLOSED/MERGED → 409；主单是已被合并过的单 → 409。
   *    ⚠️ 反过来：**主单允许是一张已完结（CLOSED）的单** —— 后端只挡 MERGED，
   *       这是契约没禁止的现状（界面上给用户一句提醒，不做硬拦）。
   *
   * @param body { mainOrderId, version }
   */
  mergeWorkOrder(id, body) {
    return request('POST', '/api/work-orders/' + encodeURIComponent(id) + '/merge', body);
  },

  /* ============================================================
   * 管理后台（契约 §10）
   * 这几个接口**只有管理员能用**，其它角色一律 403（拦截器只管"有没有 Token"，
   *  真正的角色校验在 Service 里那句 `if (!"ADMIN".equals(userType))`）。
   * 前端也顺手挡一道（顶部条不给入口、路由直接送回工单列表），
   *  目的不是"安全"（后端才是防线），而是别让学生/干事看到一堆点了就报错的按钮。
   * ============================================================ */

  /**
   * 用户列表（#34）。返回 PageResult：{ total, page, pageSize, list }。
   *
   * ⚠️ 三个筛选参数**只能传合法值**：`userType` ∈ STUDENT/STAFF/ADMIN、
   *    `status` ∈ ACTIVE/DISABLED —— 传别的会 **400**（不是"静默给个空列表"）。
   *    所以前端的下拉只能给这几个固定选项，别让人手输。
   *    （好消息：qs() 会把空串丢掉 = 不筛，正好对应"全部"。）
   *
   * ⚠️ pageSize 后端会夹到 1~50（传 999 会被压成 50），page=0 会兜底成 1；
   *    所以页数要拿**后端返回的** page/pageSize 算，不能拿请求值算。
   *
   * ⚠️ 列表项里**没有 password**（bcrypt 哈希绝不能随列表漏出去）—— 前端别去猜这个字段。
   *
   * @param params { page, pageSize, userType, status, keyword }
   */
  adminUsers(params) {
    return request('GET', '/api/admin/users' + qs(params));
  },

  /**
   * 启用 / 禁用账号（#35）。data 回一条更新后的 UserListItem（status 已是新值）。
   *
   * 🔴 **不能改自己**：后端会返回 400（否则管理员一下个请求就 403，再也进不来改回来）。
   *    前端在自己那一行把按钮置灰，是"少报一次必然的错"，不是安全防线。
   *
   * ⚠️ 禁用**立即生效**：对方手上的 Token 下一个请求就会被拦截器判 403
   *    （每请求都实时查 t_user.status，不用等 Token 过期）⇒ 弹确认框是有道理的。
   *
   * @param body { status: 'ACTIVE' | 'DISABLED' }
   */
  adminUpdateUserStatus(id, body) {
    return request('PUT', '/api/admin/users/' + encodeURIComponent(id) + '/status', body);
  },

  /**
   * 重置密码（#36）。**无请求体**，data 是 null。
   *
   * ⚠️ 新密码 = **该账号学号/工号的后 6 位**；响应里**不带明文**
   *    （照契约口径：管理员只需要知道规则）。所以前端的确认框要把这条规则写出来 ——
   *    不然管理员点完不知道该告诉对方什么。
   *
   * ⚠️ 重置**不会**让对方已发出的 Token 失效（JWT 无状态）：
   *    已登录的人还能继续用，直到他自己登出或 Token 过期。想"踢下线"得靠黑名单，现在没有。
   */
  adminResetPassword(id) {
    return request('POST', '/api/admin/users/' + encodeURIComponent(id) + '/reset-password');
  },

  /**
   * 敏感词列表（#37）。返回一个**数组**（不是 PageResult）——
   * 照契约不分页、也**不过滤状态**（被禁用的词也在里面，
   * 否则管理员禁错了想恢复都找不到那个词）。排序是创建时间倒序。
   *
   * ⚠️ 所以"只看启用中的""按关键词找"这些过滤**只能前端做**（见 app.js 的本地过滤），
   *    别指望往这个接口传参数 —— 它不接受任何查询参数。
   */
  adminSensitiveWords() {
    return request('GET', '/api/admin/sensitive-words');
  },

  /**
   * 新增一个敏感词（#38）。data 是一条 SensitiveWordItem（含新的 wordId）。
   *
   * ⚠️ 三种结果**都是 code=0**，只能用 `message` 区分（前端直接把 message 显示给用户即可）：
   *    · "敏感词新增成功"
   *    · "该词已存在，未重复添加"
   *    · "该词之前被禁用，已重新启用"   ← **这就是"启用"操作**：这个模块没有单独的启用接口，
   *                                      把同一个词再加一次就等于恢复启用。
   *
   * ⚠️ word 会 trim，且**不能超过 100 字**（库里是 varchar(100)，超了后端返回 400，
   *    不会悄悄截断）。重复添加同一个词不会报错，也不会浪费一个新的 wordId。
   */
  adminAddSensitiveWord(body) {
    return request('POST', '/api/admin/sensitive-words', body);
  },

  /**
   * 批量导入（#39）。data = { total, added, skipped }，恒等式 added + skipped == total。
   *
   * @param body { words: '词1,词2,词3' }
   *
   * ⚠️ 分隔符很宽松：英文逗号、**中文逗号、顿号、分号、换行**都认 ——
   *    设计意图就是让人从 Excel 里直接粘一串过来，不用手工改标点。
   * ⚠️ 串内的重复会先去重（否则"跳过几个"变得没法解释）；
   *    空串、全是分隔符、或混入超长词 → 400。
   */
  adminBatchSensitiveWords(body) {
    return request('POST', '/api/admin/sensitive-words/batch', body);
  },

  /**
   * 禁用敏感词（#40，软删除：状态变成 DISABLED，行还在库里）。
   *
   * ⚠️ 幂等：重复禁用同一个 id 照样返回 code=0。
   * ⚠️ 删掉的词**仍然出现在列表里**（不是"看不见了"）—— 要恢复就把同一个词再添加一次。
   */
  adminDisableSensitiveWord(wordId) {
    return request('DELETE', '/api/admin/sensitive-words/' + encodeURIComponent(wordId));
  },

  /* ---------- 回复模板（契约 §10.3 / #41~#44）---------- */

  /**
   * 模板列表（#41）。**这个接口不是管理员专属** —— 学生、干事、管理员都能读
   * （干事回工单时要套用模板，所以必须给干事；学生也能看到 FAQ 式的标准答复）。
   *
   * ⚠️ 默认**只返回启用中**的 ACTIVE 模板，按 `sortNum` 升序
   *    （不是按创建时间 —— 这个排序是给"常用话术排前面"用的）。
   *
   * ⚠️ `status=ALL` / `DISABLED` 这两个值**只有管理员传了才生效**：
   *    学生/干事传了也会被忽略（照样只拿到 ACTIVE），这是后端刻意做的防越权。
   *    ⇒ 管理页（管理员）可以用它来看已禁用的模板，干事端的"插入模板"下拉则不要传。
   *
   * @param params { category, status }
   */
  templates(params) {
    return request('GET', '/api/templates' + qs(params));
  },

  /**
   * 新增模板（#42）。data 是新建的 TemplateItem（templateId = TPL + 4 位序号）。
   *
   * ⚠️ 四个字段都会被校验，任意一个不合法都是 **400**：
   *    · category 必须是那 5 个分类之一（枚举列，不是自由文本）；
   *    · title 非空、≤ 100 字；content 非空；sortNum 不能为负。
   * @param body { category, title, content, sortNum }
   */
  adminCreateTemplate(body) {
    return request('POST', '/api/admin/templates', body);
  },

  /**
   * 修改模板（#43）：**部分更新** —— 只传要改的字段，没传的字段保持原样。
   * （所以"只改内容"就只发 `{content}` 一个字段。）
   *
   * ⚠️ 一个字段都不传 → **也是 code=0**，但 message 是"没有需要修改的字段"，
   *    什么都不做。前端别指望靠 code 判断"有没有真的改到"。
   * ⚠️ 想**复活**一个被禁用（软删除）的模板：传 `{ status: 'ACTIVE' }`。
   *    status 传别的值 → 400；模板不存在 → 404。
   *
   * @param body { category?, title?, content?, sortNum?, status? }
   */
  adminUpdateTemplate(id, body) {
    return request('PUT', '/api/admin/templates/' + encodeURIComponent(id), body);
  },

  /**
   * 禁用模板（#44，软删除：status 变 DISABLED，行还在）。
   * 幂等（重复禁用照样 code=0）；不存在 → 404。
   * 禁用后它就不出现在默认列表里了，但管理员用 `?status=ALL` 还能看到并复活。
   */
  adminDisableTemplate(id) {
    return request('DELETE', '/api/admin/templates/' + encodeURIComponent(id));
  },

  /* ---------- 数据统计（契约 §10.4 / #45~#46）---------- */

  /**
   * 统计概览（#45）。data 六个字段：
   * `{ totalTickets, duplicateRate, statusDistribution, categoryDistribution,
   *    avgResponseMinutes, avgCloseMinutes }`
   *
   * ⚠️ `duplicateRate` 是**小数**（0.12 = 12%），不是一个 12 —— 显示时要自己 ×100。
   * ⚠️ 两个 distribution 是**对象/字典**（键是英文状态枚举 / 中文分类名），
   *    而且**不管有没有数据都固定给满 5 个键**（值可能是 0）—— 所以前端图例可以定死，
   *    不会因为"今天没这个状态"就少一块。
   * ⚠️ 两个平均时长是**整数分钟**（不是 null）；空区间时是 0。
   *
   * @param params { startDate, endDate }  都是 'YYYY-MM-DD'；**不传 = 统计全量**
   *
   * 🔴 日期三个坑：
   *    ① `endDate` 那一天是**整天都算**的（不是"到当天 0 点为止"）；
   *    ② 起始晚于截止 → **400**；格式不是 `YYYY-MM-DD` → **400**（后端刻意用 String 接，
   *       否则框架会在参数绑定阶段抛异常变成 500）；
   *    ③ 所以前端就用 `<input type="date">`，别让用户手打日期 —— 它给的值天然是 `YYYY-MM-DD`。
   */
  adminStatisticsOverview(params) {
    return request('GET', '/api/admin/statistics/overview' + qs(params));
  },

  /**
   * 干事工作量（#46）。data 是**数组**（没有分页）：每条
   * `{ handlerId, handlerName, total, closedCount, avgCloseMinutes }`。
   *
   * ⚠️ 只统计**有负责人**的工单（handler_id 不为空），所以没接过单的人不会出现。
   * ⚠️ 排序是"单量降序、并列时按工号升序" —— 顺序稳定，前端直接照着渲染即可。
   * ⚠️ `avgCloseMinutes` 只算**已完结**的单；一个干事要是接了单还没完结，这里是 **0**（不是 null）。
   *
   * @param params { startDate, endDate }  同上，不传 = 全量
   */
  adminStaffWorkload(params) {
    return request('GET', '/api/admin/statistics/staff-workload' + qs(params));
  },

  /* ---------- 数据导出（契约 §10.5 / #47）---------- */

  /**
   * 导出工单为 Excel。⚠️ **成功时回来的不是一个 Result 信封，是一个 .xlsx 文件**。
   *
   * 所以这里走的是 requestBlob（和附件下载同一个通道），返回的是
   * `{ ok, blob, contentType }` —— 拿到的是**字节**，不是 data/message。
   *
   * 🔴 用它的地方必须知道这三件事，少一件就会"安静地出错"：
   *
   *   ① **失败时 HTTP 状态码仍然是 200**，错误写在一段 JSON 的 code 里
   *      （参数不合法 400 / 不是管理员 403）。而 requestBlob 只看 HTTP 状态 ——
   *      于是那段 JSON 会被当成一个"文件"返回给你，`ok` 还是 true。
   *      ⇒ **必须自己看 contentType**：含 `json` 就是错误消息（要 `blob.text()` 出来
   *        解析 message 给用户看），是 xlsx 才是真文件。
   *      不做这一步的后果：用户下载到一个几十字节的假 xlsx，打开是乱码，
   *      而且全程**一句报错都没有**，只能来问你"导出的文件打不开"。
   *
   *   ② **不能用 request()**：它拿到响应就 `resp.json()`，对着一堆二进制字节会解析失败，
   *      直接给你一句"服务器返回的内容不是合法 JSON" —— 文件就永远拿不到。
   *
   *   ③ **文件名不在响应体里**：后端把文件名放在 Content-Disposition 响应头
   *      （`attachment; filename="work-orders-yyyyMMdd-HHmmss.xlsx"`），而 requestBlob
   *      只回传了字节和 Content-Type。前端想自己起名就自己起（见 app.js 的 exportFileName），
   *      要读后端那个名字得先给 requestBlob 加一项返回值。
   *
   * @param params { startDate, endDate, status, category, keyword }
   *   · startDate / endDate —— 'YYYY-MM-DD'，**截止日整天都算**；不传 = 不限
   *   · status —— 单个状态；接口其实也认**逗号分隔的多个**（'PENDING,CLOSED'），
   *     但界面上是单选下拉，所以前端只发一个
   *   · category —— 那 5 个中文分类之一
   *   · keyword —— 文档 §10.5 没列这个参数（后端是有意的超集，和 #50 工单搜索共用一套筛选）
   *   空串会被 qs() 丢掉 = 不筛这一维，正好对应"全部"
   *
   * ⚠️ 导出**不分页**，传 page/pageSize 也不会生效（后端刻意忽略）。
   * ⚠️ 每次**成功**导出，后端都会写一条 EXPORT 审计日志（谁、什么时候、按什么条件、多少行）；
   *    失败的导出不写 —— 所以"没导出来"这件事不会在日志里留下痕迹。
   */
  exportWorkOrders(params) {
    return requestBlob('/api/admin/export/work-orders' + qs(params));
  },

  /* ---------- 日志查询（契约 §10.6 / #48、#49）---------- */

  /**
   * 状态流转日志（#48）。查的是 `t_status_log` —— **工单的状态**怎么变的（业务时间线）。
   *
   * ⚠️ 和下面的操作日志**不是一回事，别混**：
   *    · 这个答的是"这张单经历了什么" → 详情页那条进度条的原始数据；
   *    · operation-logs 答的是"谁动过系统" → 查账追责用。
   *    同一个动作常常两边都记一笔，但"记的目的"不一样（后台表注释里写了）。
   *
   * 返回 PageResult `{ total, page, pageSize, list }`，单条字段：
   * `{ logId, workOrderId, beforeStatus, afterStatus, action,
   *    operatorId, operatorName, operatorRole, remark, operationTime }`
   *
   * @param params { workOrderId, page, pageSize }
   *   · workOrderId —— 按工单号筛；**不传 = 全部工单的日志**（量很大，所以界面上给了个输入框）
   *   · pageSize 默认 20、**上限 50**（传 999 会被压到 50）；page<1 兜底成 1
   *
   * ⚠️ **`beforeStatus` 可能是 `null`** —— 这不代表数据坏了：一张单的第一跳
   *    （学生提交）前面没有"旧状态"可写。前端画成「— → 待接单」，别渲染成 "null"。
   */
  adminStatusLogs(params) {
    return request('GET', '/api/admin/logs/status-logs' + qs(params));
  },

  /**
   * 操作审计日志（#49）。查的是 `t_operation_log` —— **有谁**动过系统（登录、提交、
   * 接单、管 FAQ、导出……前面每一轮写的那些写操作，都往这张表里记过一笔）。
   *
   * 单条字段：`{ logId, userId, userName, userRole, operationType, targetType,
   *             targetId, operationContent, ipAddress, operationTime }`
   *
   * @param params { operatorId, operationType, startDate, endDate, page, pageSize }
   *   · operatorId —— 按操作人**工号/学号**筛（后端拿它比 `user_id`，不是姓名）
   *   · operationType —— 见下面那条 ⚠️
   *   · startDate / endDate —— 'YYYY-MM-DD'，**截止日整天都算**；不传 = 不限
   *
   * ⚠️ **operationType 后端故意不校验取值**（这列是 varchar，类型会随功能长，
   *    硬编码白名单只会变成维护负担）。所以传一个库里没有的类型**不是错误**，
   *    而是安静地回一个**空列表**（code 还是 0）。
   *    ⇒ 前端不能让人手输类型（把 `LOGIN` 打成 `login` 就什么都查不到、还以为是没数据），
   *      必须给下拉。当前库里有 29 种（清单见 app.js 的 OP_TYPE_GROUPS）。
   *
   * ⚠️ 日期两个坑（和统计页一样）：格式不对 → 400；起始晚于截止 → 400。
   *    界面上用 `<input type="date">`，它给的值天然是 'YYYY-MM-DD'。
   */
  adminOperationLogs(params) {
    return request('GET', '/api/admin/logs/operation-logs' + qs(params));
  }
};
