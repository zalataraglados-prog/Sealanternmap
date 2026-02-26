# Third-Party Notices

Sealantermap 在架构与算法层面参考了以下开源项目（均为 MIT 许可证）：

## BlueMap

- Project: https://github.com/BlueMap-Minecraft/BlueMap
- License: MIT
- Copyright:
  - Blue <https://www.bluecolored.de>
  - contributors

参考内容（思路层面）：
- 瓦片化地图输出与 Web 端分块加载
- 渲染线程与更新策略

## squaremap

- Project: https://github.com/jpenilla/squaremap
- License: MIT
- Copyright:
  - Jason Penilla
  - Contributors
  - William Blake Galbreath & Contributors（历史版权）

参考内容（思路层面）：
- 后台渲染调度
- I/O 队列背压与缓存策略

## Pl3xMap

- Project: https://github.com/granny/Pl3xMap
- License: MIT
- Copyright:
  - William Blake Galbreath

参考内容（思路层面）：
- 区域处理任务拆分
- 瓦片层级与渲染配置策略

---

说明：

- Sealantermap 不是“算法原创项目”；采用社区已有路线并做本仓独立实现。
- Sealantermap 以“思路借鉴 + 独立实现”为原则。
- 若后续引入任何直接复制或改写自上述项目的源码片段，将在对应文件头保留原始 MIT 版权声明，并在此文件补充精确来源。
