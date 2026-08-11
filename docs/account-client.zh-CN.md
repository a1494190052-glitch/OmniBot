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

`develop` 调试版本和 `production` 正式版本均默认使用账号品牌域名：

```properties
OMNIBOT_BASE_URL=https://account.omnimind.com.cn
```

打包时仍可通过同名构建属性覆盖该默认值，以便切换部署环境。反向代理负责把 `/v1/auth/*` 和 `/v1/me/*` 转发到内部账号服务。不要在客户端配置 New API 的真实内部地址。

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

主聊天文本请求已经读取这个模式：平台模式携带账号 Access Token 访问品牌网关；BYOK 模式继续使用设备上的模型提供商配置。图片生成、语音和模型列表等独立链路仍沿用原有配置，后续可按计费策略逐项接入。
