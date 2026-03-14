# Sealantermap

项目结构与功能映射文档见：`docs/STRUCTURE.md`

Sealantermap 是一个面向 Paper 服务器的轻量地图预览插件。  
目标是用可控的 I/O 和内存开销，在 `http://127.0.0.1:8156/` 提供可读地图预览。

## 非原创声明

本项目的核心路线并非原创，而是基于社区成熟方案进行独立重写实现。  
参考来源包含 BlueMap、squaremap、Pl3xMap（均为 MIT 许可证），详细见 `THIRD_PARTY_NOTICES.md`。

## 当前能力

- 增量渲染：监听 `world/region/*.mca` 变化，只重绘变更区域。
- 手动全量重建：支持强制重建基底图。
- 瓦片预览：前端按视口加载 `/tile.png`，避免一次性加载超大整图。
- 质量档位：`1 / 4 / 9 / 16 / 80` 五档（从 1 像素/区块到高细节检查档）。
- 可视化开关：未知迷雾、区块边界、首图预测、纹理取色。

## 启动与构建

1. 依赖
- Java 17+
- Paper 1.20+（推荐 1.21.x）

2. 构建（无全局 Maven 时）

```powershell
mvn -f pom.xml clean package -DskipTests
# 或者使用你自己的 Maven Wrapper
# <path-to-maven-wrapper>/mvnw.cmd -f <repo-root>/pom.xml clean package -DskipTests
```

3. 产物

```text
target/sealantermap-0.1.0.jar
```

## 配置要点

文件：`src/main/resources/config.yml`

- `chunk-pixel-size`: 质量档位默认值。
- `local-render.startup-window-chunks`: 首次窗口边长（单位：区块）。
- `local-render.auto-update.*`: 自动增量更新开关与间隔。
- `visual.unknown-fog.*`: 未知迷雾开关与颜色。
- `visual.chunk-boundary.*`: 区块边界叠加开关与颜色。

## 致谢与借鉴

本项目参考了以下开源项目的公开设计思路：

- BlueMap（MIT）
- squaremap（MIT）
- Pl3xMap（MIT）

借鉴点主要包括：

- 瓦片化地图传输与多级缩放思路
- 渲染任务调度与后台更新策略
- 队列限流/背压与缓存设计

具体版权与许可证说明见 `THIRD_PARTY_NOTICES.md`。
