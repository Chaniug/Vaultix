# Docs/assets —— README 与仓库展示用的图片资源

> 这些**不是** App 的资源（App 图标在 `app/src/main/res/`）。本目录只服务仓库展示。

| 文件 | 用途 | 规格 |
|---|---|---|
| `vaultix-banner.webp` | README 顶部**动图横幅**（吉祥物 + 品牌名 + 文案，一张图） | 880×260 · 36 帧 · 12fps · **≈2.8 MB** |
| `social-preview.png` | GitHub **Social preview**（仓库设置里手动上传） | 1280×640 · ≈0.27 MB |
| `vaultix-icon.png` | **App 图标**的渲染件（由 `ic_launcher_*.xml` 矢量渲出，与 App 完全一致） | 512×512 · ≈0.09 MB |

## 品牌色（**取自吉祥物本身**，别再另选）

从吉祥物的不透明像素聚类得到：主调是**深褐黑**（`#040302` / `#562513`）+ **暖金**（`#D1A15D` / `#AB8B60`）。
故横幅与社交图统一用：文字金 `#E8BC6B`、副文案 `#DCC7A8` / `#B79A78`、底 `#140F0A`。
⚠️ App 图标自身是**蓝**（`#5B7BF0 → #3B5BDB → #1E2C8C`）——蓝是产品身份、金是吉祥物气质，
两者是互补搭配，**不要把图标改成金色**。

## 为什么是 WebP（不是 APNG / GIF）

原素材是 **APNG**（556×556 / 36 帧 / 12fps / **10.2 MB**）。同尺寸实测：

| 格式 | 体积 | alpha | 取舍 |
|---|---|---|---|
| APNG | 3.54 MB（320 宽） | 完整 | 与无损 WebP 同量级，无优势 |
| **WebP 无损** | **2.8 MB**（880×260 横幅） | 完整 | ✅ 本仓选用：**零压缩伪影** |
| WebP 有损 q80 | 0.94 MB（320 宽） | 完整 | ⚠️ 用户报"动起来有残影" ⇒ 已弃用 |
| GIF | 1.05 MB（320 宽） | 仅 1-bit | 高光渐变与半透明边缘掉质 |

GitHub 的 markdown 预览**支持动图 WebP**（有专门测试仓库列出 "Supported in GitHub MarkDown preview"）。

## ⚠️ 生成横幅时的两个坑（照抄命令前先读）

1. **`color` 输入是无限流，不限制帧数会永远编码下去** —— 实测 ffmpeg 跑了 13 分钟、
   产出 238 MB 的残file。⇒ 必须给 **`-frames:v 36`**（或把 `-shortest` 放在**输出侧**；
   放输入侧会报 `Option shortest ... cannot be applied to input url`）。
2. 本机 ffmpeg **没有 `-filter_complex_script`**（同 `-vsync` 被删那类）⇒ 滤镜必须内联；
   `drawtext` 的字体路径写 `C\:/Windows/Fonts/xxx.ttf`（**filter 字符串内不走 MSYS 路径转换**）；
   彩色 emoji（🔐）**渲染不出来**，要用 `seguisym.ttf` 的**单色**字形。

## 重新生成

```bash
SRC=/d/idmDown/work_cat04_sprite_anim.png   # 原始 APNG 素材

# 1) 动图横幅（吉祥物 + 品牌名 + 文案，一张图；透明底靠文字描边保证浅色主题可见）
ffmpeg -y -i "$SRC" -f lavfi -i "color=c=black@0.0:s=880x260,format=rgba" \
  -filter_complex "[0:v]scale=-1:216[mg];[1:v][mg]overlay=24:22[b];\
[b]drawtext=fontfile='C\:/Windows/Fonts/arialbd.ttf':text='Vaultix':x=268:y=46:fontsize=104:fontcolor=0xE8BC6B:borderw=4:bordercolor=0x2A1A0E,\
drawtext=fontfile='C\:/Windows/Fonts/msyhbd.ttc':text='Android 开源密码管理器':x=274:y=166:fontsize=28:fontcolor=0xDCC7A8:borderw=3:bordercolor=0x2A1A0E,\
drawtext=fontfile='C\:/Windows/Fonts/msyh.ttc':text='Bitwarden 云端库  ·  KeePass KDBX 本地库':x=274:y=208:fontsize=21:fontcolor=0xB79A78:borderw=3:bordercolor=0x2A1A0E[out]" \
  -map "[out]" -c:v libwebp -lossless 1 -compression_level 6 -loop 0 -frames:v 36 \
  Docs/assets/vaultix-banner.webp

# 2) App 图标渲染件：把 ic_launcher_*.xml 的 pathData 拼成 SVG（background 渐变 + foreground 路径），
#    用**本机 Edge 无头**渲染（pycairo 在 Windows 装不上，svglib 因此渲不出 PNG）：
#    msedge --headless=new --disable-gpu --default-background-color=00000000 \
#           --window-size=512,512 --screenshot=Docs/assets/vaultix-icon.png file:///…/icon.html

# 3) Social preview（静态）：深色暖底 + 图标 + 金字 + 文案 + 吉祥物首帧
ffmpeg -y -i "$SRC" -frames:v 1 /tmp/frame0.png
ffmpeg -y -f lavfi -i "color=c=0x140F0A:s=1280x640" -i /tmp/frame0.png -i Docs/assets/vaultix-icon.png \
  -filter_complex "[1:v]scale=-1:520[mg];[2:v]scale=104:104[ic];[0:v][mg]overlay=W-w-70:70[a];[a][ic]overlay=88:96[b];\
[b]drawtext=fontfile='C\:/Windows/Fonts/arialbd.ttf':text='Vaultix':x=210:y=78:fontsize=118:fontcolor=0xE8BC6B,\
drawbox=x=214:y=240:w=180:h=7:color=0xE8BC6B@1:t=fill,\
drawtext=fontfile='C\:/Windows/Fonts/msyhbd.ttc':text='Android 开源密码管理器':x=92:y=290:fontsize=38:fontcolor=0xDCC7A8,\
drawtext=fontfile='C\:/Windows/Fonts/msyh.ttc':text='同时支持 Bitwarden 云端库与 KeePass KDBX 本地库':x=94:y=352:fontsize=27:fontcolor=0xB79A78,\
drawtext=fontfile='C\:/Windows/Fonts/msyh.ttc':text='GPL-3.0-or-later  ·  Android 8.0+  ·  Kotlin + Compose':x=94:y=408:fontsize=23:fontcolor=0x8A7259" \
  -frames:v 1 Docs/assets/social-preview.png
```

## ⚠️ Social preview 必须**手动上传**（GitHub 没有 API）

仓库 → **Settings → General → Social preview → Upload an image**，传 `social-preview.png`。
不上传的话，GitHub 在分享链接时会自动截取 README 顶部，显得很单薄。
