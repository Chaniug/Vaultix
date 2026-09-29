# Docs/assets —— README 与仓库展示用的图片资源

> 这些**不是** App 的资源（App 图标在 `app/src/main/res/`）。本目录只服务仓库展示。

| 文件 | 用途 | 规格 |
|---|---|---|
| `vaultix-mascot.webp` | README 顶部吉祥物（**动图**） | 320×320 · 30 帧 · 10fps · **≈0.94 MB** |
| `vaultix-wordmark.png` | README 标题**字标**（替代 `# 🔐 Vaultix` 那行文字） | 860×240 · ≈16 KB |
| `social-preview.png` | GitHub **Social preview**（仓库设置里手动上传） | 1280×640 · ≈0.25 MB |

## 字标为什么做成「深色底 + 字标 + 强调条」

⚠️ **透明底的白色字在 GitHub 浅色主题下会看不见** —— 这是 README 图片最常见的翻车点。
两种可行做法：① **烘一层深色底**（本仓选用：两种主题下都是"一块深色面板"，且与 Social preview 同品牌）；
② `<picture>` + `prefers-color-scheme` 出两版图（更轻，但依赖 GitHub 对 `<picture>` 的支持）。
将来若想换成 ②，照着上面的 ffmpeg 命令把 `-f lavfi -i color=…` 那层去掉即可得到透明版。

标题语义靠 `<h1><img/></h1>` 保留（不是裸 `<img>`）—— 这样 GitHub 的页面结构与无障碍读屏仍有标题。


## 为什么是 WebP 而不是 APNG / GIF

原始素材是 **APNG**（556×556 / 36 帧 / 12fps / **10.2 MB**）：体积过大，
README 首屏会被它拖慢。三种格式同尺寸（320×320 / 30 帧 / 10fps）实测：

| 格式 | 体积 | alpha | 取舍 |
|---|---|---|---|
| APNG | 3.54 MB | 完整 | 太大 |
| **WebP（本仓选用）** | **0.94 MB** | 完整 | ✅ 体积最小且保真；GitHub markdown 预览支持动图 WebP |
| GIF | 1.05 MB | 仅 1-bit | 色彩渐变与半透明边缘会掉质（本素材是高光丰富的金甲机甲猫） |

## 重新生成（素材更新时照抄）

```bash
# 1) README 动图（缩尺寸 + 降帧率是关键，别直接放原图）
ffmpeg -y -i <原图>.png -vf "fps=10,scale=320:-1:flags=lanczos" \
  -c:v libwebp -q:v 80 -loop 0 Docs/assets/vaultix-mascot.webp

# 2) Social preview：取首帧 → 叠到深色底 + 字标（滤镜内联：本机 ffmpeg 已删 -filter_complex_script）
ffmpeg -y -i <原图>.png -frames:v 1 /tmp/frame.png
ffmpeg -y -f lavfi -i "color=c=0x0B1220:s=1280x640" -i /tmp/frame.png \
  -filter_complex "[1:v]scale=-1:500[mg];[0:v][mg]overlay=W-w-72:70,\
drawbox=x=88:y=250:w=150:h=6:color=0x3B82F6@1:t=fill,\
drawtext=fontfile='C\:/Windows/Fonts/arialbd.ttf':text='Vaultix':x=84:y=120:fontsize=128:fontcolor=0xF8FAFC,\
drawtext=fontfile='C\:/Windows/Fonts/msyhbd.ttc':text='Android 开源密码管理器':x=92:y=296:fontsize=44:fontcolor=0x93C5FD,\
drawtext=fontfile='C\:/Windows/Fonts/msyh.ttc':text='同时支持 Bitwarden 云端库与 KeePass KDBX 本地库':x=94:y=368:fontsize=27:fontcolor=0x94A3B8" \
  -frames:v 1 Docs/assets/social-preview.png

# 3) 字标（README 标题）。⚠️ 去掉下面 `-f lavfi -i color=…` 那一层就得到**透明底**版本
#    （但透明版的白色字在浅色主题下会看不见，见上一节）
ffmpeg -y -f lavfi -i "color=c=0x0B1220:s=860x240" \
  -filter_complex "drawtext=fontfile='C\:/Windows/Fonts/seguisym.ttf':text='🔐':x=62:y=56:fontsize=126:fontcolor=0xE2E8F0,\
drawtext=fontfile='C\:/Windows/Fonts/arialbd.ttf':text='Vaultix':x=212:y=42:fontsize=134:fontcolor=0xF8FAFC,\
drawbox=x=216:y=196:w=186:h=7:color=0x3B82F6@1:t=fill" \
  -frames:v 1 Docs/assets/vaultix-wordmark.png
```

⚠️ **本机 ffmpeg 没有 `-filter_complex_script`**（同 `-vsync` 被删那类）⇒ 滤镜必须内联；
`drawtext` 的字体路径要写 `C\:/Windows/Fonts/xxx.ttf`（**filter 字符串内不走 MSYS 路径转换**）；
锁字形取自 `seguisym.ttf`（单色，不是彩色 emoji —— 后者 ffmpeg 渲染不出来）。

## ⚠️ Social preview 必须**手动上传**（GitHub 没有 API）

仓库 → **Settings → General → Social preview → Upload an image**，传 `social-preview.png`。
不上传的话，GitHub 会在分享链接时自动截取 README 顶部，显得很单薄。
