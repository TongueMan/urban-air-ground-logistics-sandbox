# Ubuntu 22.04 公网部署说明

本文面向 2 核 vCPU、4 GB 内存、20 GB 磁盘的 Ubuntu 22.04 云服务器。生产结构固定为：

```text
公网 80/443
  → 宿主机 Nginx（域名、HTTPS、限流、真实 IP）
  → 127.0.0.1:8088
  → 前端容器 Nginx
  → 后端、MySQL、MQTT 容器网络
```

MySQL、MQTT 和后端均不直接开放公网端口。`docker-compose.prod.yml` 已按 2 核 4 GB 服务器设置资源上限，并限制每个容器的 Docker 日志为最多 3 个 10 MB 文件。

## 1. 准备公网地址与域名

按量付费服务器同样可以使用 DNS。建议先给实例绑定稳定的弹性公网 IP（EIP），再将域名的 `A` 记录指向该地址。中国大陆节点使用域名公开提供网站时，通常还需要完成ICP备案。

阿里云安全组最终只需放行：

- TCP 80：证书申请和 HTTPS 跳转；
- TCP 443：正式网站；
- TCP 22：SSH，建议限制为管理员固定来源 IP。

不要放行 1883、3306、8095 和 8088。

## 2. 创建生产环境文件

在服务器的项目目录执行：

```bash
cp .env.example .env.local
chmod 600 .env.local
```

至少填写以下值：

```dotenv
FRONTEND_BAIDU_MAP_AK=浏览器端百度地图AK
MYSQL_PASSWORD=独立随机强密码
MYSQL_ROOT_PASSWORD=另一份独立随机强密码
DEMO_COOKIE_SECRET=至少32位随机字符串
FLEET_DEV_PRICING_ENABLED=false
```

可以使用 `openssl rand -hex 32` 分别生成密码和 Cookie 签名密钥。`.env.local` 已被 Git 忽略，不得上传或提交。

浏览器端百度地图 AK 会出现在网页中，这属于正常行为，但必须在百度控制台将 Referer 白名单限制到正式域名。`BAIDU_ROUTE_AK` 是可选的服务端路线规划 AK，应限制为服务器公网 IP。

## 3. 启动并进行本机检查

生产配置会在缺少关键密码、Cookie 密钥或浏览器地图 AK 时直接拒绝启动：

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.local config -q
docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.local up -d --build
docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.local ps
curl --fail http://127.0.0.1:8088/healthz
```

业务端口只监听服务器回环地址。域名尚未配置时，可从本机使用 SSH 隧道检查：

```bash
ssh -L 8088:127.0.0.1:8088 ubuntu@服务器公网IP
```

然后在本机访问 `http://127.0.0.1:8088`。

### 已有网站且不能改动宿主机 Nginx 时

如果服务器上的 80/443 端口和宿主机 Nginx 已属于其他项目，可以使用随项目提供的独立公网端口覆盖配置：

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.public-port.yml --env-file .env.local up -d --build
```

在云厂商安全组中仅额外放行 TCP 8088，即可通过 `http://服务器公网IP:8088` 访问。前端容器已内置按 IP 的请求与连接限流；1883、3306 和 8095 仍不得开放。该方式不会占用或修改宿主机 Nginx，适合与现有网站隔离共存，但使用公网 IP 时只有 HTTP。取得独立域名后，仍建议通过独立域名和 HTTPS 对外提供服务。

## 4. 首次配置域名和证书

安装宿主机 Nginx 与 Certbot：

```bash
sudo apt update
sudo apt install -y nginx certbot python3-certbot-nginx
```

先复制 HTTP 引导配置，将示例域名替换为真实域名：

```bash
sudo cp deploy/nginx/skyfleet-bootstrap.conf.example /etc/nginx/sites-available/skyfleet
sudo sed -i 's/__DOMAIN__/game.example.com/g' /etc/nginx/sites-available/skyfleet
sudo ln -s /etc/nginx/sites-available/skyfleet /etc/nginx/sites-enabled/skyfleet
sudo nginx -t
sudo systemctl reload nginx
```

DNS 生效后申请证书：

```bash
sudo certbot certonly --nginx -d game.example.com
```

再用最终 HTTPS 与限流配置替换引导配置：

```bash
sudo cp deploy/nginx/skyfleet.conf.example /etc/nginx/sites-available/skyfleet
sudo sed -i 's/__DOMAIN__/game.example.com/g' /etc/nginx/sites-available/skyfleet
sudo nginx -t
sudo systemctl reload nginx
sudo certbot renew --dry-run
```

如果 `/etc/nginx/sites-enabled/default` 仍占用默认站点，可以在确认 `skyfleet` 配置测试通过后移除该符号链接。

## 5. 上线检查

```bash
curl --fail https://game.example.com/healthz
curl --fail https://game.example.com/api/demo/sessions/current
docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.local ps
docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.local logs --tail=100 backend frontend
```

还应在浏览器确认地图、任务生成、SSE 实时推进、任务结束和再次生成任务均正常。请求超过外层 Nginx 限额时会返回 HTTP 429；当前默认值适合小规模公开体验，后续可根据日志和 CPU 使用率调整。

## 6. 更新与回滚

更新代码：

```bash
git pull --ff-only
docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.local up -d --build
```

更新前建议备份 MySQL 命名卷或导出数据库。若新版本异常，切回上一个已验证的 Git 提交后重新构建；不要删除 MySQL 与 MQTT 命名卷。
