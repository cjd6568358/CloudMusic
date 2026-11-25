# -*- coding: utf-8 -*-
"""§61.362: tab 图标去毛刺——内部搭接线改连续 path / 平头（Butt）。"""

p = 'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/FnIcons.kt'
s = open(p, encoding='utf-8').read()

# 1) 工具函数：加 Butt 头版本的 line
old_line = '''        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(
            color = tint,
            start = pt(x1, y1),
            end = pt(x2, y2),
            strokeWidth = sw,
            cap = StrokeCap.Round,
        )'''
new_line = old_line + '''
        /**
         * §61.362（用户一手「像拼出来的、有毛刺」）：**内部搭接**用的平头线。
         *
         * 圆头（[line]）在每段端点都画一个半径 = 半个笔宽的实心圆。当两条线以 T 形
         * 或折角搭接时，两个圆头叠在一起就鼓出一个「疙瘩」；48dp 的底栏图标放大了看，
         * 就是「拼装件 + 毛刺」。
         * 平头只画到端点，搭接处是干净的一条，视觉上是一笔画出来的。
         * 只在笔画**真正的自由端**才用 [line]（圆头收尾）。
         */
        fun lineB(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(
            color = tint,
            start = pt(x1, y1),
            end = pt(x2, y2),
            strokeWidth = sw,
            cap = StrokeCap.Butt,
        )'''
assert s.count(old_line) == 1, ('line helper', s.count(old_line))
s = s.replace(old_line, new_line)

# 2) Home：墙顶下沉 0.5 单位，让圆头正好被屋顶线吃掉
old_home = '''                path {
                    moveTo(5.4f * u, 9.8f * u)
                    lineTo(5.4f * u, 20.4f * u)
                    lineTo(18.6f * u, 20.4f * u)
                    lineTo(18.6f * u, 9.8f * u)
                }'''
new_home = '''                // §61.362：墙顶由 9.8 下沉到 10.3 —— 圆头半径 1 单位，顶到 9.3，
                // 正好落在屋顶线（x=5.4 处 y≈9.28）上；原来顶到 8.8 会戳出屋顶 0.5 单位，
                // 就是用户看到的「毛刺」。
                path {
                    moveTo(5.4f * u, 10.3f * u)
                    lineTo(5.4f * u, 20.4f * u)
                    lineTo(18.6f * u, 20.4f * u)
                    lineTo(18.6f * u, 10.3f * u)
                }'''
assert s.count(old_home) == 1, ('home wall', s.count(old_home))
s = s.replace(old_home, new_home)

# 3) Library：符干 + 横梁 合成一条连续 path
old_lib = '''                ring(6.0f, 17.9f, 2.76f)
                ring(18.0f, 15.9f, 2.76f)
                line(8.76f, 17.9f, 8.76f, 4.7f)
                line(20.76f, 15.9f, 20.76f, 2.7f)
                line(8.76f, 4.7f, 20.76f, 2.7f)'''
new_lib = '''                // §61.362：符干 + 横梁原来是 3 次独立 line()，两个折角各叠一对圆头 ⇒
                // 看起来像焊上去的。合成**一条连续 path** 后折角走 Round join，一转到底。
                path {
                    moveTo(8.76f * u, 17.9f * u)
                    lineTo(8.76f * u, 4.7f * u)
                    lineTo(20.76f * u, 2.7f * u)
                    lineTo(20.76f * u, 15.9f * u)
                }
                ring(6.0f, 17.9f, 2.76f)
                ring(18.0f, 15.9f, 2.76f)'''
assert s.count(old_lib) == 1, ('library', s.count(old_lib))
s = s.replace(old_lib, new_lib)

# 4) Search：柄改平头，起点落在圆环外沿
old_search = '''            FnIcon.Search -> {
                ring(10.6f, 10.6f, 7.4f)
                line(15.8f, 15.8f, 21.2f, 21.2f)
            }'''
new_search = '''            FnIcon.Search -> {
                ring(10.6f, 10.6f, 7.4f)
                // §61.362：柄原来从圆环**中心线**（距圆心 √2×5.2≈7.35）起笔且是圆头，
                // 圆头半径 1 单位会在圆环外侧鼓出一个瘤。改成平头 + 起点后移到 16.2
                // （距圆心 √2×5.6≈7.92，正落在圆环外沿 7.4+0.5 之内），搭接处干净。
                lineB(16.2f, 16.2f, 21.2f, 21.2f)
            }'''
assert s.count(old_search) == 1, ('search', s.count(old_search))
s = s.replace(old_search, new_search)

open(p, 'w', encoding='utf-8', newline='').write(s)
print('icons de-burred, CR=%d LF=%d' % (s.count('\r'), s.count('\n')))
