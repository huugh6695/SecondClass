# 学生自助注册与手机号认证

## 用户流程

1. 学生填写学号、姓名、手机号并获取短信验证码。
2. 学生设置自己的密码，注册接口固定创建 `STUDENT`，请求体不能指定管理员角色。
3. 注册成功后自动登录，并信任当前浏览器设备 180 天。
4. 同一设备日常使用“学号 + 密码”登录。
5. 新设备完成密码校验后，还需验证绑定手机号。
6. 忘记密码和更换手机号均通过短信验证。
7. 密码重置或更换手机号后，旧 JWT 失效、全部可信设备被撤销。

管理员账号仍然通过数据库种子、部署导入或其他后台运维方式创建，不能调用学生注册接口。

## 新增接口

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/auth/register/sms-code` | 发送注册验证码 |
| POST | `/api/auth/register` | 学生注册并自动登录 |
| POST | `/api/auth/login/sms/verify` | 新设备短信验证登录 |
| POST | `/api/auth/password/reset/sms-code` | 发送找回密码验证码 |
| POST | `/api/auth/password/reset` | 验证后重置密码 |
| POST | `/api/auth/phone/sms-code` | 发送原/新手机号验证码，需登录 |
| PUT | `/api/auth/phone` | 双重验证后更换手机号，需登录 |

## 数据库升级

已有数据库执行一次：

```bash
mysql -uroot -p --default-character-set=utf8mb4 second_classroom \
  < deploy/migrate-auth-registration.sql
```

全新数据库直接使用更新后的 `schema.sql`。

## 本地开发

默认配置为：

```yaml
app.sms.provider: log
app.sms.expose-dev-code: true
```

验证码会写入后端日志，同时通过 `debugCode` 返回给本地页面。生产环境必须设置
`SMS_EXPOSE_DEV_CODE=false`，不能把验证码返回浏览器。

## 生产短信网关

生产使用 `SMS_PROVIDER=webhook`。应用向 `SMS_WEBHOOK_URL` 发送：

```json
{
  "phone": "13900001234",
  "code": "123456",
  "scene": "REGISTER",
  "expiresInSeconds": 300
}
```

如果配置了 `SMS_WEBHOOK_TOKEN`，请求会携带：

```http
Authorization: Bearer <SMS_WEBHOOK_TOKEN>
```

网关返回任意 2xx 表示发送成功。网关需要自行对接阿里云、腾讯云或学校短信平台。

## 安全约束

- 验证码有效期 5 分钟，60 秒发送冷却，单个验证码最多尝试 5 次。
- 同一手机号每天最多发送 10 次。
- 同一来源 IP 每小时最多请求 30 次验证码。
- 同一账号 15 分钟内连续失败 10 次后暂停登录尝试。
- 手机号使用 AES-GCM 加密保存，手机号查重使用 HMAC，不保存明文。
- 登录设备令牌以 HMAC 摘要保存在 Redis，180 天未使用会失效。
- 学生注册接口不接受角色参数，也不存在公开的管理员注册入口。
- 生产环境必须使用独立的 `SMS_PHONE_ENCRYPTION_KEY` 和 `SMS_PHONE_HASH_SECRET`。
