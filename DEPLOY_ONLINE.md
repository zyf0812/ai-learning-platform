# 免费部署指南

推荐使用 [Railway](https://railway.app) 免费部署（无需信用卡，每月 500 小时免费额度）。

## 部署步骤

### 1. 注册 Railway

打开 [https://railway.app](https://railway.app)，用 GitHub 账号登录。

### 2. 连接仓库

点击 **New Project** → **Deploy from GitHub repo** → 选择 `zyf0812/ai-learning-platform` → 点 **Deploy**

### 3. 添加 PostgreSQL

在 Dashboard 点 **New** → **Database** → 选择 **PostgreSQL** → 会自动创建并生成连接地址

### 4. 配置环境变量

在项目的 Variables 面板添加以下变量：

| 变量名 | 值 |
|--------|-----|
| `DB_URL` | Railway 自动生成的 PostgreSQL 连接地址（可点 Postgres 实例查看） |
| `DB_USERNAME` | Railway 自动生成的用户名 |
| `DB_PASSWORD` | Railway 自动生成的密码 |
| `REDIS_HOST` | 暂时写 `localhost`（Redis 非必需，可后续加上） |
| `REDIS_PORT` | `6379` |
| `REDIS_PASSWORD` | `123456` |
| `JWT_SECRET` | 随便填一个长字符串，如 `your-random-secret-key-here-at-least-32-chars` |
| `DEEPSEEK_API_KEY` | 你的 DeepSeek API 密钥 |
| `ZHIPU_API_KEY` | 你的智谱 API 密钥 |

### 5. 修改启动命令

在项目的 **Settings** → **Deploy** 设置：

| 设置项 | 值 |
|--------|-----|
| **Root Directory** | `backend` |
| **Build Command** | `mvn package -DskipTests` |
| **Start Command** | `java -jar target/doc-exam-backend-1.0.0.jar` |

### 6. 部署

Railway 会自动检测到 push，自动构建和部署。

部署成功后，Railway 会生成一个 `https://xxx.up.railway.app` 的域名，你的 API 地址就是 `https://xxx.up.railway.app/api`

### 7. 更新前端环境变量

在 Railway 的 Frontend 项目（或本地开发时）设置：

```
NEXT_PUBLIC_API_URL=https://xxx.up.railway.app
```

## 注意事项

- 免费实例会休眠（15 分钟无访问），再次访问时会自动唤醒（约 10-20 秒）
- 数据库持久化，重启后数据不会丢失
- 如果使用自定义域名，需要在 Railway 的 Settings 中配置

## 替代方案

| 平台 | 免费额度 | 备注 |
|------|---------|------|
| [Railway](https://railway.app) | $5/月免费 | 推荐，支持 Java 17 |
| [Render](https://render.com) | 750h/月 | 需要绑信用卡验证 |
| [Fly.io](https://fly.io) | 少量免费 | 需要绑信用卡 |
