# §61.137 全页面竞品 UI 重建 · 规格提取（一手证据）

**方法**：复用 docs/14 §2 实证的 `android:tag` 语义名捷径——`tools/scan_semantic_layouts.py`
扫描 `analysis/decoded/res/layout*/` 全部 6,773 个 layout（含 DataBinding `<layout>` 根），
得 **1,762 个带语义名文件 / 1,754 个唯一语义名**（与 docs/14 的 1,754 计数吻合），
索引在 `analysis/ui-replica/semantic-layout-index.json`。

**结论**：竞品（网易云 9.6.20）曲库模块有完整原生 layout（`music_library_*` 家族），
主设置页 = `main_account` + `item_account_*` 家族；**主底栏 Tab 栏与播放页内嵌播放列表
无原生 layout**（前者代码绘制、后者在 activity_player_audio 内部/RN）——底栏按
§61.136 已提取的 h7 (#FF262626) 底 + 10sp label 近似，挂账。

## 1. 页面 ↔ 竞品 layout 映射

| 自研页面 | 竞品语义名 | 混淆文件 |
|---|---|---|
| 设置页 | `main_account` | cky.xml |
| 设置行（普通/开关/分组标题） | `item_account_normal_item` / `item_account_item_with_switch` / `item_account_group_title` | b1c / b1a / b19 |
| 设置行左区（图标+标题） | `item_sidebar`（include @layout/bzv） | bzv.xml |
| 曲库容器 | `fragment_music_library` | as5.xml |
| 曲目行 | `music_library_music_item` + `music_library_music_info_layout` | cvi + cvg |
| 歌单 tab | `fragment_music_library_playlist` | as9.xml |
| 歌单行 / 分组标题行 | `music_library_playlist_item` / `music_library_playlist_title_item` | cvm / cvn |
| 播放全部头（我的音乐头） | `fragment_my_playlist_music` | asp.xml |
| 搜索框页 | `fragment_music_library_search_tip` | asc.xml |
| 搜索结果页 | `fragment_music_library_search_result` | asa.xml |
| 历史词 chip / 联想行 | `music_library_search_history_item` / `music_library_search_suggest_item` | cvq / cvr |
| 播放列表弹窗（队列风格基准） | `dialog_music_list` | a6h.xml |
| 弹窗底板 | `@drawable/b5y`（#F71C1C1C 顶部圆角 10dip） | drawable/b5y.xml |
| 曲库页背景 | `music_library_fragment_home` content #ff222222 | cva.xml |

## 2. 色值（values/colors.xml 直查，全部一手）

| token | 色值 | 出处 |
|---|---|---|
| PageBg | `aei #FF222222` | asc/asa 根背景、cvm 行背景 |
| RowBg | `aer #FF212121` | cvi 曲目行容器 |
| DialogBg | `a6a #F71C1C1C` | a6h content（b5y） |
| RowSelected | `b1v #12FFFFFF` | bec selector 选中态 |
| ChipBg | `b1y #14FFFFFF` | bfi 历史词 chip |
| TextPrimary | `b12 #FFFFFFFF` | 歌名/tab 选中/搜索词 |
| TextSecondary | `b17 #66FFFFFF` | 歌手 · 时长 12sp |
| TextTertiary | `b1n #66FFFFFF` | 歌单副标题 |
| TextHint | `b1h #33FFFFFF` | 搜索框 hint |
| TabUnselected | `b18 #4DFFFFFF` | as5 atl |
| Divider | `b1p #0DFFFFFF`（0.5dip） | as5/asc |
| AccentRed | `#FFFF3A3A` | cvg CustomButton aer 属性 |
| AccentRedSub | `#4DFF3A3A` | cvg CustomButton pl 属性（背景） |
| 白系 | `b1t #99FFFFFF`、`b1w #B3FFFFFF`、`b16 #80FFFFFF` | 文本层级 |
| 页深背景（§61.136 播放侧） | `rc #FF121212 / di,gm #FF1C1C1C / h7,qx #FF262626` | 不动 |

## 3. 尺寸/字号（dimens.xml + layout 直查）

- **设置行**（a4m style）：高 50dip（a1u）、layout_marginH 16dip（bi）、paddingH 16dip、
  左区图标 20×20dip（a1v）+ marginEnd 10dip、标题 16sp、右侧提示 11sp `aiz #66000000`
  （深色换算 `#66FFFFFF`）、箭头 `c_i` 着色 `a2i`（深色 `#33FFFFFF`）。
- **分组标题**（b19）：高 40dip、paddingStart 16、12sp。
- **设置页头**（cky）：status+toolbar 占位 70dip、行高 52dip（art）、头像 27×27 + marginEnd 10、
  昵称 16sp、右侧扫描键 marginStart 42。
- **曲库 tab**（as5）：TabLayout 高 45dip、字 17sp（style ld）、选中 b12 / 未选 b18、
  指示器 bfn = 白色圆角 5dip、左右 inset 16dip、高 asw=1.0dip（直译）；
  下分割线 0.5dip b1p；根 marginTop 6dip。
- **曲目行**（cvi+cvg）：容器 bg aer、上下 margin 5dip、尾 marginEnd 16dip；
  封面 48×48dip marginStart 16 上下 10（行总高 68dip）、歌名 16sp b12、
  副行 marginTop 6：歌手 12sp b17 + `·`(padding 5) + 时长 12sp b17。
- **歌单行**（cvm）：高 60dip、封面 50×50 圆角 8dip marginStart 16、
  名 16sp b12 marginStart 10、副 12sp b1n marginStart 10。
- **歌单分组标题**（cvn）：高 36dip、13sp b1n、paddingH 16、marginTop 4。
- **播放全部头**（asp）：标题栏 44dip（0.3dip 线 at 44）、标题 18sp b1e 居中 maxWidth 224、
  「播放全部」16sp marginTop 15 paddingStart 14、count 12sp b1n、列表 marginTop 10。
- **搜索框**（asc/asa）：bg bff（#0DFFFFFF 圆角 18dip）、高 36dip、paddingH 10、
  放大镜 16×16（alpha 0.2）、文字 15sp b12 hint b1h「搜索音乐」、清除 16×16；
  「取消」17sp b12 marginH 16。
- **历史词 chip**（cvq）：高 32dip、paddingH 16、bg bfi（#14FFFFFF 圆角 16dip）、
  文字 b12（白）、marginEnd 10。
- **联想行**（cvr）：高 40dip、图标 16×16 marginStart 16、文字 14sp b12 marginStart 8。
- **播放列表弹窗**（a6h）：content bg b5y 顶部圆角 10dip、tab 高 40dip（aiv）、标题 12sp b1e。

## 4. 诚实边界

1. **不导出任何竞品素材**（C4）：图标一律用系统 Icon/文本字形占位（放大镜 ♫ ⋮ 等），
   竞品 `bfe/bfh/c_i/f28` 等 drawable 不复制；1:1 图标需 D-145 式拍板后走
   assets-mapping.csv 流程，挂账。
2. **封面**：~~Track 模型无封面字段~~ **§61.138 更正**——`Track.coverRef`（扫描期落盘的
   本地封面文件路径）已存在，播放页 §61.109 ② 同源；§61.138 已接全部列表行
   （CompCover：LruCache 64 张 + 256px 降采样 + 缺失缓存），占位盒/字形仅作回退。
3. **底栏**：竞品主 Tab 栏无原生 layout（代码绘制），按 §61.136 h7 #FF262626 +
   10sp label + 选中品牌红近似；挂账待真机对照。
4. **cvg CustomButton 属性名混淆**（pl/pp/aer/aey/aiv/aj0），「使用」钮按网易云曲库
   视觉惯例实现为红描边圆角钮（高 26dip、radius 13、红字 14sp），标注不确定度。
5. **指示器高 asw=1.0dip** 为直译（属性名混淆，不能排除是其他 1dip 尺寸属性）。
6. 真机截图校准 NOT RUN（设备相机占用）；本批为代码侧像素规格落位。
