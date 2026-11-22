# AlbumPlayer 开发与维护说明

## 项目与构建

- 目标平台为 Minecraft Java 26.1.2 Fabric，使用 Java 25、Fabric Loom 1.16.3 和 Gradle Wrapper 9.4.1。从仓库根目录执行 `./gradlew build`，输出位于 `build/libs/`。Gradle 的版本与依赖配置见 `gradle.properties` 和 `build.gradle`。
- 仓库名是 AlbumPlayer。已有注册 ID 保持 `album_jukebox`，方块和物品均为 `album_jukebox:jukebox`，Java 根包是 `dev.geduu.albumjukebox`。这些标识参与世界存档、资源寻址和联机通信。
- Loom 将代码分为 `src/main/java` 和 `src/client/java`。方块、容器、网络和音频桥接位于通用源码。屏幕、方块实体渲染和唱臂插值位于客户端源码。资源位于 `src/main/resources/assets/album_jukebox` 和 `src/main/resources/data/album_jukebox`。

## 唱片与播放状态

- `DiscColors.isBlanksDisc` 按两个已知物品 ID 识别唱片，即 `audioplayerblanks:custom_music_disc` 和 `audioplayerblanks:music_disc`。入仓时，服务端的 `AlbumAudio.track` 同时要求 `JUKEBOX_PLAYABLE` 组件和 AudioPlayer 音频绑定。客户端入仓预测只检查物品 ID 和组件。播放列表和自动换曲会跳过旧存档中遗留的非 Blanks 唱片，但储物界面仍允许取出它们。
- Blanks 兼容基线为私用移植版 `26.1.2-1.0.2+unofficial`。自定义唱片从 `CUSTOM_MODEL_DATA.colors` 的第 0、1 项获得盘体及中心色环颜色。基础唱片优先读取 `DYED_COLOR`，缺省色由 `DiscColors` 定义。构建通过物品 ID 和数据组件集成 Blanks。
- `AlbumBlockEntity` 在服务端管理 27 格仓位、选曲、循环模式及播放状态。按行优先顺序跳过空格。存档写入物品和循环模式，播放帧、音频频道及动画进度属于运行状态。方块被拆除时掉落库存，区块卸载会停止播放。
- `AlbumAudio` 使用 AudioPlayer `2.2.0+26.1.2` 的 API 读取绑定、元数据及缓存 Opus 帧，按 AudioPlayer 的设置限制播放范围和音频时长。音频在线程池加载，`AudioStream` 在虚拟线程中以 20 ms 为一帧发送到 Simple Voice Chat 的定位频道，停止后清空频道缓冲。换曲、恢复和播放中跳转会建立新频道。
- `AlbumMenu` 区分唱片仓和只读的播放界面。播放菜单用容器数据槽同步位置、时长和修订号，每个整数拆成两个 16 位字。客户端的 `AlbumAction` 由服务端核对容器 ID、操作距离和菜单有效性。选曲、跳转还需核对修订号。拖动预览留在客户端，松手后只提交一次跳转。

## 世界模型和客户端联机同步

- `AlbumVisualState` 通过方块实体更新标签给追踪区块的客户端同步盘片配色、唱臂姿态、定位频道 UUID、单曲帧位置和时长。开始、暂停、跳转和换曲等事件在服务端下一次更新时同步，播放时每 10 个服务端 tick 校准一次。新区块追踪者收到当前帧快照，无需打开菜单。
- `discChange` 用于协调换碟和配色，`cueChange` 用于同一张唱片上的真实重定位，例如进度跳转和单曲循环。周期进度校准不能触发抬针。修改网络字段时同步检查标签读写和菜单编解码，并在客户端、服务端同时测试。
- `DeckGeometry` 把单曲完成比例线性映射为唱针到盘心的距离，再由唱臂支点和臂长反算角度。外圈沿用 31° 初始落针点，内圈半径为模型坐标 2.25，中心色环半径为 1.68。唱盘在机身正面左侧，唱臂在右侧。从上往下看，盘片顺时针旋转。
- `DeckMotion` 根据服务端快照及其本地接收时间平滑推进，预测最多 1 秒。正常播放中保持向内推进。跳转时先抬针再横摆，换碟等抬针后再更新盘面。暂停时原地抬针，恢复原地落针后继续移动。单曲循环回外圈并保持旋转，结束或取碟时归位。
- `AlbumJukeboxRenderer` 使用客户端方块实体渲染器绘制盘片几何与唱臂。固定外壳、支架和主轴是方块模型资源。调整几何时一起检查手持模型与世界模型、四种朝向、落针高度和唱针经过内圈时与色环的间距。

## 界面与频谱

- `AlbumScreen` 负责播放、唱片仓、滚动列表和拖动进度。频谱只在可见时订阅当前唱片机的定位频道。
- `AlbumVoicePlugin` 监听 Simple Voice Chat 客户端已解码的定位音频。`AlbumMeterInput` 仅处理被订阅 UUID 的 48 kHz 单声道 PCM，以 4096 点带 Hann 窗的 FFT 生成从低到高的 24 个频段。`AlbumVfd` 按列处理衰减和峰值保持，每列绘制 18 格。切换频道时清空采样，避免上首曲目的峰值残留。

## 验证改动

- 使用 JDK 25 构建，并用 README 所列依赖启动一个专用服务端和至少两个客户端。分别验证操作者与未打开菜单的旁观者。
- 检查 Blanks 已录制唱片可插入、未录制 Blanks 和已绑定音频的原版唱片被拒绝，以及旧仓内原版唱片可取出。分别覆盖普通放入和快捷转移。
- 验证两个不同时长的曲目、暂停及恢复、播放和暂停时跳转、三种循环模式、取出当前唱片、区块重新加载。两个唱片机同时播放时，VFD 只响应打开的那台。
- 若变动渲染，检查四个方向的盘片和唱臂动作，并验证相同播放比例下不同曲目的落针位置一致。
