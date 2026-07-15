---
name: fullstack-react-springboot
summary: 用于创建、扩展、重构、审查、调试、测试和部署 React + TypeScript 前端与 Java Spring Boot 后端组成的全栈项目。适用于 Vite、React Router、TanStack Query、表单、认证、Spring MVC、Spring Security、JPA、数据库迁移、Docker Compose、CI/CD 以及前后端联调任务。
description: 当 Codex 需要处理 React + TypeScript 与 Java Spring Boot 组成的全栈项目时使用。包括创建前端页面和组件、搭建 Vite、配置 React Router、实现 API Client、表单、认证、Spring REST API、Spring Security、JPA、数据库迁移、Docker Compose、CI、测试和端到端联调。不要用于 React Native、Angular、Next.js 全栈项目或非 Java 后端，除非用户明确要求迁移到本技术栈。
---

# React + Spring Boot 全栈工程 Skill

## 目标

构建一个真实可运行、可验证、可维护的全栈系统，而不是只有静态页面、Mock 数据或看起来完成的 UI。

最终系统应尽量具备：

- React + TypeScript 前端；
- Spring Boot REST 后端；
- 明确且稳定的 API 契约；
- 合理的数据库持久化方案；
- 不提交 secret 的多环境配置；
- 前后端双重校验；
- 统一错误处理；
- 分层测试与真实联调；
- 可复现的本地启动方式；
- 实际执行过的 build、test 和 smoke test。

优先保证正确性、可维护性、安全性和可验证性，不要为了快速生成大量代码而牺牲工程质量。

---

## 1. 总体执行规则

### 1.1 修改前先检查仓库

在创建或修改代码之前，必须：

1. 查看仓库目录结构；
2. 阅读根目录 `README.md`、相关 `AGENTS.md`、构建文件和配置文件；
3. 检查现有 Java、Spring Boot、Node、包管理器、React、TypeScript 和数据库版本；
4. 检查现有 Controller、DTO、Entity、Service、Repository 和测试；
5. 检查启动脚本、Docker Compose、环境变量和 CI；
6. 识别项目已有的命名、异常、认证、日志和测试规范；
7. 优先复用现有架构，除非它明显错误或用户明确要求重构。

不要因为熟悉某个模板，就擅自用模板覆盖现有架构。

### 1.2 不要虚构事实

禁止：

- 虚构不存在的 API endpoint；
- 根据类名猜测端口；
- 根据前端需要擅自改变后端业务状态；
- 假设 Gateway 已经实现；
- 假设认证方式是 JWT；
- 假设支付成功响应等于订单最终状态成功；
- 声称测试通过却没有实际运行。

必须以真实 Controller、DTO、配置、日志和脚本为准。

### 1.3 按垂直业务切片实现

优先一次完成一个完整用户流程：

```text
用户操作
  -> React 页面或表单
  -> 类型安全的 API Client
  -> Spring Controller
  -> Service 业务规则
  -> Repository / Database
  -> Response 或 Error
  -> UI 成功、加载或失败状态
  -> 自动化测试
  -> 真实 smoke test
```

不要先生成所有页面，然后留下大量未连接的按钮和 Mock API。

### 1.4 控制改动范围

除非需求明确需要，不要擅自添加：

- Redux Toolkit；
- 微服务；
- Kafka；
- Redis；
- Kubernetes；
- 大型 UI 组件库；
- 复杂设计系统；
- GraphQL；
- WebSocket；
- 通用但暂时无用的抽象层。

优先使用最简单、能够满足当前需求的方案，并保留合理扩展点。

### 1.5 必须实际验证

完成任务后至少执行适用的命令：

```bash
# 前端
npm run lint
npm run typecheck
npm test
npm run build

# 后端
./mvnw test
./mvnw package
```

如果项目使用 Gradle，则运行对应的 Gradle Wrapper 命令。

涉及前后端联调时，还必须验证至少一条真实用户流程。

---

## 2. 默认技术选择

优先遵循仓库已有技术栈。新项目默认可采用以下方案。

### 2.1 前端

- React；
- TypeScript；
- Vite；
- React Router；
- 原生 `fetch` 或项目已有 HTTP Client；
- TanStack Query：管理 server state；
- React Hook Form：处理中等或复杂表单；
- Zod：前端 schema validation；
- Vitest；
- React Testing Library；
- Playwright：关键端到端流程。

### 2.2 后端

- 与 Spring Boot 版本兼容的 Java LTS；
- Spring Boot；
- Maven Wrapper 或 Gradle Wrapper；
- Spring Web MVC；
- Bean Validation；
- Spring Data JPA；
- Flyway 或 Liquibase；
- Spring Security：仅在项目需要认证授权时；
- JUnit 5；
- Mockito；
- Spring Boot Test；
- Testcontainers。

### 2.3 基础设施

- 小团队、个人项目、面试项目：一个仓库，`frontend/` 与后端服务分开；
- Docker Compose 只负责本地依赖，除非项目明确要求所有服务容器化；
- 前端和后端生产环境分别构建；
- 生产环境优先通过 Gateway、BFF 或反向代理提供统一入口。

不要写死“latest”版本。优先使用仓库版本管理或当前官方兼容版本。

---

## 3. 推荐仓库结构

### 3.1 单体后端项目

```text
project-root/
├── frontend/
├── backend/
├── compose.yaml
├── .env.example
├── .gitignore
├── README.md
└── .github/workflows/
```

### 3.2 微服务后端项目

```text
project-root/
├── frontend/
├── gateway/
├── catalog-service/
├── inventory-service/
├── order-service/
├── payment-service/
├── infra/
├── scripts/
├── compose.yaml
└── README.md
```

开发阶段不要把 React 源码放进 Spring Boot 的 `src/main/resources/static`。

开发时通常分别运行：

```text
Vite dev server
Spring Boot service(s)
Docker Compose dependencies
```

---

## 4. 开始编码前的分析流程

### 4.1 记录现有系统事实

至少确认：

- 用户可见业务流程；
- 前端已有页面；
- 后端真实 endpoint；
- 请求和响应 DTO；
- 错误响应格式；
- 认证方式；
- 用户角色；
- 数据库；
- 服务端口；
- Gateway 是否可用；
- 本地启动命令；
- 环境变量；
- 测试命令；
- 当前已知失败项。

### 4.2 为每个业务切片定义契约

```text
用户目标：
前端 route：
前端页面或组件：
HTTP method：
API path：
Request body / query：
Success response：
Error response：
Authorization rule：
Persistence change：
Frontend test：
Backend test：
Smoke test：
```

### 4.3 推荐实现顺序

1. 数据库迁移或领域模型；
2. Repository；
3. Service 与业务规则；
4. DTO 与 Mapper；
5. Controller 与异常映射；
6. 后端测试；
7. 前端 TypeScript 类型；
8. API Client；
9. Query / Mutation hooks；
10. 页面、表单和组件；
11. 前端测试；
12. 真实联调；
13. README 与 `.env.example`。

---

## 5. React 项目结构

优先按 feature 组织：

```text
frontend/src/
├── app/
│   ├── router.tsx
│   ├── providers.tsx
│   └── App.tsx
├── features/
│   ├── auth/
│   │   ├── api/
│   │   ├── components/
│   │   ├── hooks/
│   │   ├── pages/
│   │   ├── schemas/
│   │   └── types.ts
│   ├── catalog/
│   ├── cart/
│   ├── checkout/
│   └── orders/
├── shared/
│   ├── api/
│   ├── components/
│   ├── hooks/
│   ├── lib/
│   └── types/
├── styles/
└── main.tsx
```

### 5.1 Page 与 Component

- Page 对应 route 或主要页面；
- Feature component 负责某个业务功能；
- Shared component 必须是跨业务、可复用的；
- 数据编排通常放在 page 或 feature 边界；
- 展示组件专注渲染和用户交互。

避免一个组件同时负责：

- routing；
- API fetching；
- 大型表单；
- Modal；
- 权限判断；
- 全部 UI；
- 复杂数据转换。

---

## 6. React 状态管理原则

在选择工具前，先判断状态属于哪一类。

### 6.1 Local UI State

例如：

- Modal 是否打开；
- 当前 tab；
- 临时输入；
- 展开或折叠；
- hover 状态。

优先使用：

```text
useState
useReducer
```

### 6.2 URL State

例如：

- 搜索关键词；
- 商品分类；
- 排序；
- 页码；
- 当前选中的资源 ID。

优先使用：

```text
route params
search params
```

这样用户可以刷新、复制链接和使用浏览器前进后退。

### 6.3 Server State

例如：

- 商品列表；
- 商品详情；
- 库存；
- 订单；
- 支付状态；
- 用户资料。

优先使用 TanStack Query 或项目已有 server-state 工具。

TanStack Query 负责：

- loading；
- error；
- cache；
- retry；
- refetch；
- mutation；
- cache invalidation；
- polling；
- pagination。

不要把 API 返回的数据重复复制到 Redux、Context 和多个 `useState` 中。

### 6.4 Form State

简单表单可以使用受控组件。

中等或复杂表单优先使用：

```text
React Hook Form
Zod
```

### 6.5 Cross-cutting Client State

例如：

- 当前登录用户的少量 UI 会话信息；
- theme；
- locale；
- 跨页面购物车草稿；
- 多步骤 checkout 的本地进度。

根据复杂度使用：

```text
Context
useReducer
Redux Toolkit
```

---

## 7. Redux Toolkit 使用规则

### 7.1 不作为默认依赖

不要在新 React 项目中默认安装 Redux Toolkit。

先判断是否真的存在复杂的全局 client state。

### 7.2 适合使用 Redux Toolkit 的情况

当出现以下情况之一，并且 Context 或局部 state 已明显难以维护时，可以采用 Redux Toolkit：

- 大量相互关联的跨页面 client state；
- 多个远距离组件频繁读写同一份本地状态；
- 复杂购物车规则需要集中 reducer；
- 多步骤 checkout 需要跨 route 保存草稿；
- 离线编辑与同步队列；
- 撤销 / 重做；
- 大量 client-side derived state；
- 项目已有 Redux Toolkit 并形成统一规范。

### 7.3 不应该使用 Redux Toolkit 的情况

不要仅因为以下需求引入 Redux Toolkit：

- 获取商品列表；
- 获取订单详情；
- 获取库存；
- 调用支付接口；
- 轮询订单状态；
- 缓存 API 数据；
- 一个 Modal；
- 一个表单；
- 一个页面的筛选条件。

这些分别应由 TanStack Query、局部 state、URL state 或表单库负责。

### 7.4 电商项目建议

默认组合：

```text
TanStack Query
  -> 商品、库存、订单、支付等 server state

React Hook Form + Zod
  -> Checkout 和地址表单

URL Search Params
  -> 商品筛选、搜索、排序、分页

useState / Context
  -> 少量 UI 或用户会话状态

Redux Toolkit
  -> 仅当购物车或 checkout client state 明显复杂时引入
```

即使使用 Redux Toolkit，也不要把所有 server state 放进 Redux slice。

如果项目决定使用 Redux Toolkit：

- 使用 `configureStore`；
- 使用 `createSlice`；
- 使用 typed hooks；
- reducer 必须保持纯函数；
- slice 按业务 feature 划分；
- 不创建一个巨大的 global slice；
- 不在 Redux 中存放不可序列化对象；
- 不重复保存 TanStack Query 已管理的数据。

---

## 8. useEffect 使用原则

`useEffect` 用于 React 与外部系统同步，例如：

- 浏览器 API；
- subscription；
- timer；
- 第三方 imperative widget；
- 没有 query layer 时手动请求网络；
- 与 localStorage 同步。

不要用 `useEffect` 做以下事情：

- 根据 props 或 state 计算 derived value；
- 响应按钮点击；
- 把一个 state 复制到另一个 state；
- 在多个 effect 之间传递业务流程；
- 实现本应由 TanStack Query 管理的请求生命周期。

用户点击触发的逻辑应放在 event handler 中。

可在 render 中计算的数据，不要放进 effect。

手动请求必须处理：

- cancellation；
- stale response；
- component unmount；
- React development mode 下的重复执行。

---

## 9. React Router

多页面应用应：

- 集中或按 feature 定义 route；
- 包含 404 页面；
- 需要时使用 route-level lazy loading；
- 把可分享的筛选和分页放进 URL；
- 前端 route guard 只改善 UX；
- 后端必须再次执行 authorization；
- 生产 Web Server 必须支持 SPA fallback。

示例：

```text
/
/products
/products/:productId
/cart
/checkout
/orders/:orderId
/login
/*
```

---

## 10. 异步页面的完整状态

每个异步页面必须明确处理：

- initial loading；
- empty state；
- validation error；
- network error；
- 401；
- 403；
- 404；
- 409 或业务冲突；
- 500；
- retry；
- submitting；
- success feedback。

提交过程中应禁用重复操作。

不要只实现 happy path。

---

## 11. Vite 环境变量

前端环境变量属于公开构建配置，而不是 secret storage。

示例：

```env
VITE_API_BASE_URL=http://localhost:8080/api
```

```ts
const apiBaseUrl = import.meta.env.VITE_API_BASE_URL;
```

禁止放入：

- 数据库密码；
- JWT signing secret；
- OAuth client secret；
- AWS secret key；
- 私有 API key；
- 支付服务 secret key。

只提交：

```text
.env.example
```

不要提交真实 `.env`。

---

## 12. 前端 API Client

禁止在组件中到处拼接 URL。

推荐：

```text
shared/api/apiClient.ts
features/catalog/api/catalogApi.ts
features/orders/api/orderApi.ts
features/payments/api/paymentApi.ts
```

低层 API Client 应统一处理：

- base URL；
- headers；
- credentials；
- JSON parsing；
- 204 response；
- error mapping；
- request ID；
- timeout 或 cancellation。

示例：

```ts
export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly body?: unknown,
  ) {
    super(message);
  }
}

export async function apiFetch<T>(
  path: string,
  init: RequestInit = {},
): Promise<T> {
  const response = await fetch(`/api${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...init.headers,
    },
    credentials: 'include',
  });

  if (!response.ok) {
    const body = await response.json().catch(() => undefined);
    throw new ApiError(
      body?.message ?? `Request failed with status ${response.status}`,
      response.status,
      body,
    );
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return response.json() as Promise<T>;
}
```

注意：multipart request 不要强制设置 JSON Content-Type。

---

## 13. TanStack Query 使用原则

适合管理：

- 商品列表；
- 商品详情；
- 库存；
- 订单状态；
- 用户资料；
- mutation；
- polling；
- pagination；
- cache invalidation。

要求：

- query key 集中管理；
- mutation 成功后只更新或失效相关 cache；
- retry 策略必须考虑业务语义；
- 4xx 业务错误通常不应无限 retry；
- 支付、下单等 mutation 不要自动无脑重试；
- polling 只在需要时运行；
- terminal state 后立即停止 polling；
- 页面卸载后停止不必要请求。

订单状态轮询示例逻辑：

```text
CREATED / RESERVED / PAYMENT_PENDING
  -> 继续 polling

PAID / CANCELLED / FAILED
  -> 停止 polling
```

状态名称必须读取后端真实 enum，不要自行发明。

---

## 14. 表单与 Validation

前端 validation 改善用户体验，后端 validation 才是最终权威。

前端负责：

- required；
- 格式；
- 长度；
- 即时错误提示；
- submitting 状态。

后端负责：

- Bean Validation；
- 业务规则；
- 权限；
- 数据库唯一性；
- 最终完整性。

禁止把 disabled button、隐藏字段或前端 route guard 当作安全边界。

后端字段错误应映射到对应输入框；非字段业务错误应单独展示。

---

## 15. Spring Boot 项目结构

中大型项目优先按 feature 或 domain 组织：

```text
src/main/java/com/example/app/
├── Application.java
├── common/
│   ├── config/
│   ├── error/
│   └── security/
├── auth/
├── catalog/
├── inventory/
├── order/
└── payment/
```

每个 feature 可以包含：

```text
Controller
Service
Repository
Entity
DTO
Mapper
Exception
```

小型教学项目可以按 layer 分包，但项目扩大后优先迁移到 package-by-feature。

---

## 16. Spring Boot 分层职责

### Controller

负责：

- 接收 HTTP 输入；
- 参数解析；
- `@Valid`；
- 调用 Service；
- 返回 status code 和 response。

禁止写大量业务逻辑。

### Service

负责：

- 业务规则；
- transaction boundary；
- Repository 编排；
- 外部服务调用；
- domain exception；
- 状态转换。

### Repository

负责：

- 数据访问；
- query；
- persistence。

禁止决定业务策略。

### DTO 与 Mapper

负责：

- API 契约；
- Entity 与 API 解耦；
- 控制 nullability；
- 避免 lazy-loading JSON 问题；
- 避免暴露内部字段。

禁止直接从 public Controller 返回 JPA Entity。

---

## 17. Dependency Injection 与 Transaction

使用 constructor injection。

禁止 field injection。

Transaction 应放在需要原子性的 Service 操作上。

注意：

- 不要在数据库 transaction 中等待很慢的远程调用，除非一致性模型明确要求；
- 不要依赖 Open Session in View 掩盖 DTO mapping 问题；
- read-only query 可以合理使用 `@Transactional(readOnly = true)`；
- 跨服务操作不能依赖单数据库 transaction 假装完成分布式一致性。

---

## 18. 数据库迁移

使用 Flyway 或 Liquibase。

禁止把：

```yaml
spring.jpa.hibernate.ddl-auto: update
```

当作生产迁移方案。

要求：

- migration 可追踪；
- 已共享的 migration 不随意修改；
- 添加合理 constraint；
- 根据访问模式添加 index；
- destructive change 分阶段执行；
- 使用真实数据库或 Testcontainers 验证 migration。

---

## 19. REST API 设计

推荐：

```text
GET    /api/products
GET    /api/products/{id}
POST   /api/orders
GET    /api/orders/{id}
POST   /api/orders/{id}/payments
```

原则：

- URL 优先使用名词；
- HTTP method 表示动作；
- 使用正确 status code；
- 列表接口支持合理分页；
- 过滤、搜索、排序通常使用 query params；
- 不把内部微服务拓扑暴露给浏览器；
- API contract 变化必须同步更新前端类型和测试。

Request 与 Response DTO 应按意图拆分：

```text
CreateOrderRequest
OrderResponse
OrderListItemResponse
PaymentRequest
PaymentResponse
```

---

## 20. 统一错误格式

使用 `@RestControllerAdvice`。

推荐错误结构：

```json
{
  "timestamp": "2026-07-14T12:00:00Z",
  "status": 409,
  "code": "INSUFFICIENT_INVENTORY",
  "message": "Inventory is not sufficient",
  "path": "/api/orders",
  "requestId": "...",
  "fieldErrors": []
}
```

前端不要依赖解析任意错误字符串判断业务状态。

优先使用稳定的 `code` 字段。

不要把 stack trace 返回给客户端。

---

## 21. Authentication、Authorization、CORS 与 CSRF

### 21.1 先确认现有认证模型

不要未经检查就决定使用：

- JWT；
- HttpOnly Cookie；
- Session；
- OAuth2；
- API Gateway auth。

### 21.2 前端权限不是安全边界

前端可以隐藏按钮和保护 route，但后端必须执行真正的 authorization。

### 21.3 Token Storage

敏感 token 不应随意放进 `localStorage`。

如果使用 cookie：

- 考虑 HttpOnly；
- Secure；
- SameSite；
- CSRF。

如果使用 bearer token：

- 明确 refresh 策略；
- 明确过期处理；
- 不记录 token；
- 防止 XSS 泄漏。

### 21.4 CORS

开发阶段优先使用 Vite proxy 减少跨域差异。

后端 CORS 必须使用明确 origin。

禁止生产环境无条件：

```text
allowedOrigins("*")
allowCredentials(true)
```

不要为了让前端“先跑起来”而关闭 Spring Security、CSRF 或放开所有 origin。

---

## 22. 微服务电商项目特别规则

### 22.1 浏览器不应直接理解内部基础设施

前端不应直接暴露或调用：

- Kafka；
- Redis；
- Spanner；
- 内部 Topic；
- 服务发现；
- 内部管理端口。

浏览器只调用公开 HTTP API。

### 22.2 优先统一 API 入口

优先顺序：

1. 可用的 Gateway；
2. Backend for Frontend；
3. 反向代理统一 `/api`；
4. 最后才是在 API adapter 中隔离多个 service base URL。

不要在 React Component 中散落多个端口。

### 22.3 异步业务状态

Kafka 等异步事件意味着：

```text
HTTP request accepted
!=
最终业务状态已完成
```

例如支付接口成功返回后：

```text
Payment mutation accepted
  -> UI 显示处理中
  -> 查询真实 OrderStatus
  -> 后端确认 PAID
  -> UI 显示成功
```

不能因为支付请求返回 200 就直接把订单显示为 `PAID`。

### 22.4 Inventory

前端显示的库存只是提示。

真正的库存校验和扣减必须由后端执行。

前端不能通过隐藏按钮或本地计算保证不超卖。

### 22.5 Money

不要使用 JavaScript 浮点数进行关键金额计算。

优先：

- 后端返回最小货币单位整数；
- 或返回 decimal string；
- 前端只负责格式化展示；
- 最终金额由后端计算和确认。

### 22.6 Idempotency

下单和支付必须考虑重复点击、网络重试和重复请求。

前端应：

- mutation 期间禁用按钮；
- 避免双击；
- 必要时发送 idempotency key。

后端仍必须提供最终幂等保障。

---

## 23. 测试策略

### 23.1 前端

至少覆盖：

- 关键组件；
- Loading / Empty / Error；
- 表单 validation；
- mutation success；
- mutation failure；
- 订单 terminal state；
- polling 停止条件。

工具：

```text
Vitest
React Testing Library
MSW（需要 API mock 时）
Playwright
```

Mock 只用于测试隔离，不等于真实联调。

### 23.2 后端

至少考虑：

- Service unit test；
- Controller test；
- Repository integration test；
- Testcontainers；
- Security authorization test；
- migration test；
- duplicate request；
- concurrency；
- error contract。

### 23.3 End-to-End

关键电商流程应验证：

```text
浏览商品
-> 创建订单
-> 发起支付
-> 等待异步状态
-> 订单达到 PAID 或其他 terminal state
```

不要用纯 Mock E2E 冒充后端真实联调。

---

## 24. 日志与可观测性

后端使用结构化日志和 SLF4J。

禁止使用大量：

```java
System.out.println()
```

日志可包含：

- request ID；
- trace ID；
- user ID；
- order ID；
- payment ID；
- business operation；
- state transition；
- error stack trace。

禁止打印：

- password；
- token；
- secret；
- 完整银行卡信息；
- 敏感个人数据。

前端错误 UI 应提供对用户有意义的信息，但不要暴露内部 stack trace。

---

## 25. Docker 与本地开发

Docker Compose 优先管理：

- PostgreSQL / MySQL；
- Redis；
- Kafka；
- Spanner emulator；
- 其他基础设施。

不要未经项目要求擅自把所有 Java 服务和前端都塞进 Compose。

README 必须明确：

- 启动依赖；
- 启动后端；
- 启动前端；
- 环境变量；
- 停止命令；
- 测试命令；
- smoke test。

示例：

```bash
docker compose up -d

cd backend
./mvnw spring-boot:run

cd frontend
npm ci
npm run dev
```

---

## 26. CI/CD

CI 至少执行：

### Frontend

```bash
npm ci
npm run lint
npm run typecheck
npm test
npm run build
```

### Backend

```bash
./mvnw test
./mvnw package
```

需要时增加：

- Testcontainers；
- integration test；
- Docker image build；
- dependency scanning；
- Playwright smoke test。

不要在 CI 中硬编码 secret。

---

## 27. 常见反模式

禁止或避免：

1. 所有 React 逻辑写进 `App.tsx`；
2. 所有数据都放 Redux；
3. 把 TanStack Query 数据复制到 Redux；
4. 用 `useEffect` 编排所有业务流程；
5. 在 Component 中硬编码服务 URL；
6. 前端保存 secret；
7. Controller 写大量业务逻辑；
8. 直接返回 JPA Entity；
9. 没有统一错误格式；
10. 支付 API 返回成功就直接显示 `PAID`；
11. 使用 Mock 数据却声称完成联调；
12. 为解决 CORS 直接开放所有 origin；
13. 修改无关后端业务逻辑；
14. 没运行测试就声称完成；
15. 一次改动大量无关文件；
16. 只实现 happy path；
17. 使用 index 作为可编辑列表 key；
18. 在前端进行权威库存判断；
19. 使用浮点数完成关键金额计算；
20. 引入 Redux Toolkit 却没有明确用途。

---

## 28. Codex 执行流程

### Phase 1：分析

- 检查仓库；
- 确认真实 API；
- 确认 Gateway；
- 确认端口；
- 确认状态枚举；
- 确认认证方式；
- 输出 blocker；
- 此阶段不修改代码。

### Phase 2：前端骨架

- 创建 React + TypeScript + Vite；
- Router；
- Providers；
- API Client；
- Query Client；
- Layout；
- 404；
- Error Boundary；
- `.env.example`；
- lint、typecheck、test、build。

### Phase 3：Catalog

- 商品列表；
- 商品详情；
- 搜索、筛选、分页；
- 库存提示；
- loading、empty、error。

### Phase 4：Order

- 创建订单；
- 订单详情；
- 状态展示；
- 错误处理；
- 防重复提交。

### Phase 5：Payment

- 支付 mutation；
- processing UI；
- 订单状态 polling；
- terminal state 停止；
- 失败恢复。

### Phase 6：工程化

- 测试；
- Playwright；
- Dockerfile；
- CI；
- README；
- 真实 smoke test。

---

## 29. 完成定义 Definition of Done

只有满足适用项后，才能声称任务完成：

### 功能

- [ ] 页面连接真实 API；
- [ ] 没有无说明的 Mock 数据；
- [ ] 关键按钮实际可用；
- [ ] loading、empty、error、success 完整；
- [ ] API contract 与后端一致；
- [ ] 业务状态来自后端；
- [ ] 防止重复提交。

### 前端

- [ ] `npm run lint` 通过；
- [ ] `npm run typecheck` 通过；
- [ ] `npm test` 通过；
- [ ] `npm run build` 通过；
- [ ] 无 secret；
- [ ] 无散落硬编码 URL；
- [ ] 状态工具选择合理。

### 后端

- [ ] 编译通过；
- [ ] 测试通过；
- [ ] validation 完整；
- [ ] 统一异常处理；
- [ ] DTO 与 Entity 分离；
- [ ] 权限在后端执行；
- [ ] migration 可运行。

### 联调

- [ ] 前端可访问真实后端；
- [ ] 至少一条核心流程通过；
- [ ] 异步状态处理正确；
- [ ] smoke test 实际执行；
- [ ] README 启动步骤可复现。

### 报告

最终报告必须包含：

1. 修改的文件；
2. 关键设计决定；
3. 实际执行的命令；
4. 每个命令的真实结果；
5. 未完成项和 blocker；
6. 不得隐瞒失败；
7. 未经用户要求不要自动 commit。

---

## 30. 面向 Codex 的最终行为要求

执行任务时：

- 先读仓库，再写代码；
- 先读 API，再写前端调用；
- 优先完成小而完整的垂直切片；
- 不发明 endpoint；
- 不引入无必要依赖；
- Redux Toolkit 按需使用；
- TanStack Query 管理 server state；
- 后端是库存、金额、权限和订单状态的权威来源；
- 异步系统必须等待最终状态；
- 修改后实际运行测试和构建；
- 用证据报告结果。
