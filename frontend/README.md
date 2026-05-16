# 遥感影像智能解译与时空资产管理平台前端

本目录是项目初版前端工程，用于和 Spring Boot 后端进行联调演示。

前端目标不是一次性做复杂界面，而是先跑通核心业务闭环：

```txt
登录 -> 影像上传 -> 影像列表 -> 影像详情 -> 地图浏览 -> 空间查询 -> 创建解译任务 -> 查看任务状态
```

## 技术栈

| 类型 | 技术 |
| --- | --- |
| 前端框架 | Vue 3 |
| 构建工具 | Vite |
| 开发语言 | TypeScript |
| 组件库 | Element Plus |
| 状态管理 | Pinia |
| 请求库 | Axios |
| 路由 | Vue Router |
| 地图 | OpenLayers，实际包名为 `ol` |

## 本地运行

进入前端目录：

```bash
cd frontend
```

安装依赖：

```bash
npm install
```

启动开发服务：

```bash
npm run dev
```

访问地址：

```txt
http://localhost:5173
```

构建检查：

```bash
npm run build
```

## 后端联调

后端接口地址：

```txt
http://localhost:8080/api
```

前端已在 `vite.config.ts` 中配置代理：

```txt
/api -> http://localhost:8080
```

因此前端代码中统一请求：

```txt
/api/auth/login
/api/images/search
/api/tasks
```

不要在组件中直接写完整后端地址，也不要在组件中直接写 Axios 请求。

后端服务可在项目根目录启动：

```bash
docker compose up -d
```

GeoServer 地址：

```txt
http://localhost:8081/geoserver
```

默认登录账号：

```txt
用户名：admin
密码：admin123
```

## 当前已实现页面

| 路由 | 页面 | 状态 |
| --- | --- | --- |
| `/login` | 登录页 | 已接入 `POST /api/auth/login` |
| `/dashboard` | 工作台 | 已完成基础展示 |
| `/images` | 影像列表 | 已接入 `GET /api/images/search` |
| `/images/upload` | 影像上传 | 已接入 `POST /api/images/upload` |
| `/images/:id` | 影像详情 | 已接入详情、缩略图预签名地址、公开私有切换 |
| `/map` | 地图浏览 | 已接入 OpenLayers 底图和 WMS 图层预留 |
| `/spatial-query` | 空间查询 | 已支持矩形框选、bbox 查询和结果展示 |
| `/tasks/create` | 创建任务 | 已接入 `POST /api/tasks` |
| `/tasks` | 任务列表 | 已接入分页查询和自动刷新 |
| `/tasks/:id` | 任务详情 | 已接入详情、日志和运行中轮询 |

## 目录结构

```txt
src/
├─ api/                 # 接口封装，组件不要直接写 axios
│  ├─ request.ts        # Axios 实例、令牌、错误处理、Result<T> 解包
│  ├─ auth.ts           # 登录与当前用户
│  ├─ file.ts           # 文件预签名地址
│  ├─ image.ts          # 影像资产接口
│  └─ task.ts           # 解译任务接口
├─ components/
│  └─ layout/           # 主布局、侧边栏、顶部栏
├─ composables/
│  └─ map/              # OpenLayers 地图逻辑封装
├─ router/              # 路由配置和登录守卫
├─ stores/              # Pinia 状态
├─ styles/              # 全局样式
├─ types/               # 接口类型定义
└─ views/               # 页面组件
```

## 接口封装规范

所有请求必须通过 `src/api` 模块封装。

推荐写法：

```ts
export function searchImagesApi(params: ImageSearchParams) {
  return request.get<unknown, PageResult<ImageListItem>>('/images/search', { params })
}
```

组件中只调用封装函数：

```ts
const page = await searchImagesApi({ pageNum: 1, pageSize: 10 })
```

## 登录与令牌

登录成功后，令牌保存在：

```txt
localStorage.rs_access_token
```

`api/request.ts` 会自动添加请求头：

```txt
Authorization: Bearer <token>
```

未登录访问业务页面时，会跳转到 `/login`。

## 地图模块说明

地图相关逻辑放在：

```txt
src/composables/map/
```

当前封装：

| 文件 | 作用 |
| --- | --- |
| `useOlMap.ts` | 初始化地图、底图、坐标、缩放、WMS 图层 |
| `useSpatialDraw.ts` | 矩形框选并生成 bbox |
| `useImageFootprintLayer.ts` | 绘制影像 footprintWkt |

空间查询使用后端现有接口：

```txt
GET /api/images/search?bbox=minLng,minLat,maxLng,maxLat
```

## 任务参数说明

任务创建接口：

```txt
POST /api/tasks
```

NDVI 参数：

```json
{
  "imageId": 1,
  "taskType": "NDVI",
  "params": {
    "redBand": 3,
    "nirBand": 4
  }
}
```

NDWI 参数：

```json
{
  "imageId": 1,
  "taskType": "NDWI",
  "params": {
    "greenBand": 2,
    "nirBand": 4
  }
}
```

变化检测参数：

```json
{
  "imageId": 2,
  "taskType": "CHANGE_DETECTION",
  "params": {
    "beforeObjectKey": "raw/2026/05/before.tif",
    "afterObjectKey": "raw/2026/05/after.tif",
    "band": 1,
    "threshold": 0.2
  }
}
```

## 推荐开发顺序

当前下一步建议：

1. 在任务详情页接入 GeoServer 发布接口。
2. 将发布后的 WMS 图层加载到地图中预览。
3. 增加结果预览页面。
4. 优化影像列表缩略图显示。
5. 抽取状态标签和格式化函数，减少页面重复代码。

## 注意事项

- 前端目前服务于后端实习项目演示，优先保证业务闭环清晰。
- 不要把地图逻辑直接堆在页面组件中。
- 不要在组件里直接写 Axios。
- 不要一次性扩展过多页面，按模块逐步完成。
- 如果命令行显示中文乱码，优先检查源文件本身是否为 UTF-8。
