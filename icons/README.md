# Hermes 安卓版 · 图标包

卡通机器人图标，圆角、柔和配色，适配 Android 5.0 ~ 14（含 Android 13 主题单色图标）。

## 目录结构

```
icons/
├─ android/                        # 直接复制到 app/src/main/res/
│  ├─ mipmap-mdpi/                 # 48px
│  ├─ mipmap-hdpi/                 # 72px
│  ├─ mipmap-xhdpi/                # 96px
│  ├─ mipmap-xxhdpi/               # 144px
│  ├─ mipmap-xxxhdpi/              # 192px + 自适应前景/单色 432px
│  ├─ drawable-xxxhdpi/            # 自适应背景 432px
│  └─ mipmap-anydpi-v26/           # 自适应图标 XML
├─ master/hermes_icon_1024.png     # 母版
├─ playstore/hermes_icon_512.png   # 应用商店 512×512
├─ preview/preview_all.png         # 各尺寸与裁切效果预览
└─ generate_icons.py               # 生成脚本（Pillow + numpy）
```

每个密度都包含 `ic_launcher.png`（方角）和 `ic_launcher_round.png`（圆形）。

## 接入 Android 工程

1. 把 `icons/android/` 下的全部文件夹复制到 `app/src/main/res/`（与已有 mipmap 合并）。
2. `AndroidManifest.xml` 中确认：

```xml
<application
    android:icon="@mipmap/ic_launcher"
    android:roundIcon="@mipmap/ic_launcher_round"
    ... >
```

3. Android 8.0+ 会自动使用 `mipmap-anydpi-v26/ic_launcher.xml`，其中已配置前景、背景与 Android 13 单色层，无需额外代码。
4. 上架商店时使用 `playstore/hermes_icon_512.png`。

## 设计规范

| 用途 | 色值 |
| --- | --- |
| 描边 / 眼睛 | `#3D3A5C` |
| 背景渐变 | `#FFF8EB` → `#D4F0FF` |
| 粉色点缀（天线球、腮红、输入点） | `#FF8FA8` / `#FFB8C6` |
| 图标主体 | `#FFFFFF` |

- 内容安全区：自适应图标前景内容高 280px / 432px，圆形、圆角方、方圆裁切均不丢元素。
- 48px 下保留可辨识的头部轮廓与双眼；输入点胶囊在极小尺寸会自然融合，属正常现象。

## 重新生成

```bash
cd icons
python generate_icons.py
```

依赖：`pillow`、`numpy`。修改 `draw_art_layer()` 中的坐标与配色即可整体换肤，所有尺寸、自适应层、预览图会同步重出。
