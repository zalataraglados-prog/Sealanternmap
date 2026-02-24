# Sealantermap

本地超轻量地图渲染插件（Paper），采用“全图加减法”：

1. 启动时全量渲染一次（建立基底图）。
   默认首轮窗口：`128 x 128` 区块（即 `2048 x 2048` 方块）。
2. 运行时监听 `world/region/*.mca` 变化。
3. 只对变更 region 做增量重绘。
4. 仅在边界变化时重建整图。
5. 可开关未知迷雾。
6. 可开关区块边界叠加。

这样避免定时全盘读取所有 region 文件，显著降低硬盘压力。

## 预览地址

- 页面：`http://127.0.0.1:8156/`
- 图片：`http://127.0.0.1:8156/map.png`
- 统计：`http://127.0.0.1:8156/stats.json`

## 命令

- `/slmap status`
- `/slmap render`：按当前增量队列更新
- `/slmap render force`：强制全量重建
- `/slmap reload`

## 首轮大小参数

`config.yml`:

```yaml
local-render:
  startup-window-chunks: 128
```

## 视觉开关

`config.yml`:

```yaml
visual:
  unknown-fog:
    enabled: true
    disabled-color: "#dbeafe"
  chunk-boundary:
    enabled: false
    color: "#1f2937"
```

## 构建

本机无全局 Maven 时：

```bash
I:\files\MavenWrapperArea\mvnw.cmd "-Dmaven.repo.local=I:\files\MavenWrapperArea\.m2repo" -f I:\files\Sealantermap\pom.xml clean package -DskipTests
```

产物：

```text
target/sealantermap-0.1.0.jar
```
