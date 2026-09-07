# BastionDocs 文档站构建说明

本文档说明如何在本地启动、构建和预览 `BastionDocs` 的 VitePress 文档站。

## 项目位置

文档源码目录：

```text
E:\Project\Bastion\BastionDocs\docs
```

项目根目录：

```text
E:\Project\Bastion\BastionDocs
```

请在项目根目录执行下面的 `npm` 命令。

## 环境要求

- Node.js：建议使用当前项目依赖可兼容的 LTS 版本。
- npm：随 Node.js 安装即可。
- 依赖安装：项目根目录需要存在 `node_modules`。如果没有，请先执行：

```bash
npm install
```

## 本地开发

启动 VitePress 开发服务器：

```bash
npm run docs:dev
```

默认访问地址：

```text
http://localhost:5173/Bastion/
```

开发服务器支持热更新，修改 `docs` 下的 Markdown、主题组件或 `.vitepress` 配置后通常会自动刷新。

## 生产构建

执行生产构建：

```bash
npm run docs:build
```

构建产物输出到：

```text
docs\.vitepress\dist
```

构建完成后可以用于 GitHub Pages、静态服务器或其他静态托管平台。

## 本地预览构建产物

先完成生产构建，然后运行：

```bash
npm run docs:preview
```

预览服务会读取 `docs\.vitepress\dist` 中的静态产物。

## 常用目录说明

```text
docs
├─ .vitepress        VitePress 配置、主题和构建缓存
├─ public            静态资源目录
├─ 01.指南           简体中文指南内容
├─ 02.配置           简体中文文档/参考内容
├─ 03.生态           简体中文生态页面
├─ en                英文内容
├─ ja                日文内容
├─ ru                俄文内容
└─ vi                越南文内容
```

多语言配置位于：

```text
docs\.vitepress\locales
```

主题组件位于：

```text
docs\.vitepress\theme\components
```

## 路由与 base

站点配置中的 `base` 为：

```text
/Bastion/
```

因此本地开发和部署后的页面路径都会带有 `/Bastion/` 前缀。例如：

```text
http://localhost:5173/Bastion/
http://localhost:5173/Bastion/ecosystem
http://localhost:5173/Bastion/en/guide/intro
```

## 已知构建排障

不要在源码 Markdown 中直接写带 `base` 的资源路径：

```md
![afdian](/Bastion/image/afdian.svg)
```

`/Bastion/` 是部署 base，构建阶段资源解析可能不会按静态资源路径处理它。

## 推荐工作流

1. 在项目根目录执行 `npm run docs:dev`。
2. 打开 `http://localhost:5173/Bastion/` 检查页面效果。
3. 修改 `docs` 下的 Markdown、主题组件或 `.vitepress` 配置。
4. 修改完成后执行 `npm run docs:build`。
5. 构建成功后执行 `npm run docs:preview` 做最终静态预览。

