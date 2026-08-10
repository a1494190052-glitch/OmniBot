# OmniBot 账号客户端

这一层负责连接 OmniBot 与 `omni-account`，目前位于 `baselib/account`。

## 当前流程

```text
“我的 → 账号与 AI 服务”界面
          │
          ▼
AccountRepository
  ├─ 注册、登录、退出
  ├─ Access Token 失效后自动刷新
  └─ 读取或修改 platform / byok 模式
          │
          ▼
AccountApiClient ──HTTPS──> omni-account

账号 Access/Refresh Token ──> Android Keystore 加密存储
用户自己的模型 API Key   ──> 原有设备本地模型配置
```

账号服务器只接收 `platform` 或 `byok` 这个选择，不接收用户自己的模型 API Key。

## 服务地址

App 使用构建属性 `OMNIBOT_BASE_URL`：

```properties
OMNIBOT_BASE_URL=https://你们对外的品牌域名
```

正式版应填写 HTTPS 品牌域名，由反向代理把 `/v1/auth/*` 和 `/v1/me/*` 转发到内部账号服务。不要在客户端配置 New API 的真实内部地址。

开发环境如果暂时未配置该属性，账号功能保持禁用，App 其他现有功能仍可运行。

## 已接入的接口

- `POST /v1/auth/email-codes`
- `POST /v1/auth/register`
- `POST /v1/auth/login`
- `POST /v1/auth/refresh`
- `POST /v1/auth/logout`
- `GET /v1/me`
- `GET /v1/me/ai-settings`
- `PUT /v1/me/ai-settings`

Flutter 账号中心已经支持：

- 请求注册验证码、注册并自动登录
- 邮箱密码登录与退出当前设备
- 查看登录邮箱和平台余额
- 切换平台额度 / 用户自备 API Key
- BYOK 模式跳转到原有模型提供商配置页

下一步需要让实际 AI 请求也读取这个模式：平台模式携带账号 Access Token 访问品牌网关；BYOK 模式继续使用设备上的模型提供商配置。
