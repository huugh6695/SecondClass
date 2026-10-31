"""
验收：前端静态资源与版本号一致性

═══ 这个脚本解决一个真实踩过的坑 ═══

Spring Boot 托管静态资源时**不发 `Cache-Control`**，只发 `Last-Modified`。
浏览器拿不到明确的缓存指令，就会用「启发式缓存」—— 自己算一个有效期，
可能几小时。结果就是：

    服务器上的文件明明更新了 → 用户刷新 → 看到的还是旧界面

排查时非常迷惑：查服务器文件 MD5，和本地**完全一致**；换个无痕窗口，又是新的。
最后才发现是缓存。

═══ 根治办法：给资源加版本号 ═══

    <link rel="stylesheet" href="css/app.css?v=20261027">
    <script type="module" src="js/app.js?v=20261027"></script>
    js/app.js 里：import { api } from './api.js?v=20261027';

`?v=` 一变，URL 就变了，浏览器就当新文件重新下载。
**但三处必须一起改** —— 少改一处，那个文件就还是缓存里的旧的。

  ① index.html 的 css
  ② index.html 的 js
  ③ app.js 里 import 的 api.js   ← 最容易忘的就是这个

本脚本的核心断言就是"三处必须相同"，顺便校验格式是 8 位日期。

═══ 用法 ═══

    python verify/verify-frontend.py 8117
"""

import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from lib import Checker, http, login_required, run, ADMIN_ID, ADMIN_PWD

# 匹配 href/src="xxx.css?v=20261027" 这种
RE_LINK = re.compile(r'(?:href|src)="([^"]*\.(?:css|js))\?v=(\d{8})"')
# 匹配 app.js 里的 import ... from './api.js?v=20261027'
RE_IMPORT = re.compile(r"from\s+['\"]([^'\"]*api\.js)\?v=(\d{8})['\"]")
# 匹配 ?v=日期 这种
RE_ANY_V = re.compile(r"\?v=(\d{8})")


def text_of(path):
    st, payload, headers = http("GET", path, raw=True)
    return st, payload.decode("utf-8", "replace"), headers


def main():
    c = Checker("前端静态资源与版本号")

    # ══ 第 1 组：入口页能打开 ══
    st, html, headers = text_of("/")
    c.eq(st, 200, "GET / → HTTP 200")
    c.contains(headers.get("Content-Type", ""), "text/html", "返回的是 HTML")
    c.contains(html, '<div id="app">', "有挂载点 <div id=\"app\">（内容全由 JS 渲染）")
    c.contains(html, "第二课堂", "页面标题里有项目名")

    # ══ 第 2 组：HTML 引用的资源都带版本号 ══
    refs = RE_LINK.findall(html)
    c.gt(len(refs), 0, "HTML 里至少引用了 1 个带 ?v= 的资源")

    css_versions = []
    js_versions = []
    for path, ver in refs:
        if path.endswith(".css"):
            css_versions.append(ver)
            c.check(len(ver) == 8, "CSS 的版本号是 8 位日期（%s）" % ver, "8 位数字", ver)
        else:
            js_versions.append(ver)

    c.eq(len(css_versions), 1, "HTML 里引用 1 个 CSS（且带版本号）")
    c.eq(len(js_versions), 1, "HTML 里引用 1 个入口 JS（且带版本号）")

    # ══ 第 3 组：★核心★ 三处版本号必须一致 ══
    st, app_js, _ = text_of("/js/app.js")
    c.eq(st, 200, "GET /js/app.js → HTTP 200")

    imports = RE_IMPORT.findall(app_js)
    c.gt(len(imports), 0, "app.js 里 import 了 api.js，且带 ?v= 版本号")

    all_versions = set(css_versions) | set(js_versions)
    for path, ver in imports:
        all_versions.add(ver)

    c.eq(len(all_versions), 1,
         "★ index.html 的 css、js，以及 app.js 里 import 的 api.js，"
         "三处版本号**完全一致**（实测 %s）" % sorted(all_versions))

    if len(all_versions) > 1:
        print("\n  ⚠️  版本号不一致说明有一处改了、另一处忘了 —— 那个文件会继续被浏览器缓存。")
        print("     改前端时这三处要一起改：")
        print("       ① index.html  <link ... css/app.css?v=...>")
        print("       ② index.html  <script ... js/app.js?v=...>")
        print("       ③ app.js      import ... './api.js?v=...'\n")

    # 版本号应该是个"像日期"的数字（2020~2099 年）
    for v in all_versions:
        c.check(v[:4].isdigit() and 2020 <= int(v[:4]) <= 2099,
                "版本号 %s 看起来是有效的年份开头" % v, "2020-2099", v[:4])

    # ══ 第 4 组：带版本号能正常取到资源 ══
    # ⚠️ HTML 里写的是**相对路径**（`css/app.css`），拼 URL 时必须补前导 `/`，
    #    否则拼成 `http://127.0.0.1:8117css/app.css`，会得到一个莫名的 502。
    for path, ver in refs:
        full = path if path.startswith("/") else "/" + path
        st, payload, headers = http("GET", "%s?v=%s" % (full, ver), raw=True)
        c.eq(st, 200, "GET %s?v=%s → 200（带参数不影响取文件）" % (full, ver))
        c.gt(len(payload), 0, "%s 有内容（%d 字节）" % (full, len(payload)))

    for path, ver in imports:
        full = path if path.startswith("/") else "/js/" + os.path.basename(path)
        st, payload, headers = http("GET", "%s?v=%s" % (full, ver), raw=True)
        c.eq(st, 200, "GET %s?v=%s → 200" % (full, ver))
        c.gt(len(payload), 0, "%s 有内容（%d 字节）" % (full, len(payload)))

    # ══ 第 5 组：资源类型正确 ══
    st, css, headers = text_of("/css/app.css")
    c.eq(st, 200, "GET /css/app.css → 200")
    c.contains(headers.get("Content-Type", ""), "text/css", "CSS 的 Content-Type 正确")
    c.gt(len(css), 1000, "CSS 有实际内容（%d 字节）" % len(css))
    c.contains(css, ":root", "CSS 里有 :root 变量定义（主题色集中管理）")

    st, js, headers = text_of("/js/app.js")
    c.contains(headers.get("Content-Type", ""), "javascript", "JS 的 Content-Type 正确")
    c.gt(len(js), 1000, "入口 JS 有实际内容（%d 字节）" % len(js))
    c.contains(js, "render", "JS 里有 render（hash 路由的入口）")

    # ══ 第 6 组：三文件结构完整（不能少任何一个）══
    for p in ["/", "/css/app.css", "/js/app.js", "/js/api.js"]:
        st, payload, _ = http("GET", p, raw=True)
        c.eq(st, 200, "四个前端文件之一可访问：%s（%d 字节）" % (p, len(payload)))

    # ══ 第 7 组：API 的错误格式统一（不是 Spring 默认错误页）══
    #
    # 🔴 这里必须带 Token。
    #    拦截器挂在 /api/** 上，**先于** 路由匹配执行：
    #    没带 Token 的话，不管路径存不存在，都会被拦成 401「未登录或 Token 格式错误」。
    #    那样验到的就不是"404 长什么样"，而是"没登录长什么样" —— 断言会莫名其妙地红。
    adm = login_required(ADMIN_ID, ADMIN_PWD)

    st, body = http("GET", "/api/not-exist-path", token=adm)
    c.eq(body.get("code"), 404, "不存在的 API → code 404")
    c.contains(body.get("message"), "接口不存在", "提示语是「接口不存在」（不是 Spring 的 Whitelabel 页）")

    st, body = http("POST", "/api/work-orders/1", token=adm)
    c.eq(body.get("code"), 405, "方法不支持 → code 405")
    c.contains(body.get("message"), "方法", "提示语指向「请求方法不支持」")

    return c


if __name__ == "__main__":
    run(main)
