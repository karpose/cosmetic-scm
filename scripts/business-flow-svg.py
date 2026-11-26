#!/usr/bin/env python3
"""生成 README 里的业务流程架构图（docs/diagrams/business-flow{,-dark}.svg）。

改了业务流程或模块名称后改这里的节点，再运行：
    python3 scripts/business-flow-svg.py
图是手工排版的：节点坐标写死在下面，新加节点时注意不要压到连线。
运行时会检查文字是否超出节点宽度（按中文 1 em、西文 0.6 em 估算）。
"""
from pathlib import Path
from xml.sax.saxutils import escape

OUT = Path(__file__).resolve().parent.parent / "docs" / "diagrams"

W, H = 1680, 1512
NH = 52  # 普通节点高度

# 各业务域颜色：标题栏白字按粗体大字 3:1 对比度选的
LANES_LIGHT = {
    "sales": "#2B6FE0", "prod": "#0E9F73", "purch": "#D2691E",
    "wh": "#0F95A8", "qa": "#6A45E8", "fin": "#46546E",
}
LANES_DARK = {
    "sales": "#5B93F0", "prod": "#2DBF8E", "purch": "#E8894A",
    "wh": "#2FB3C6", "qa": "#9478F5", "fin": "#8A9AB8",
}
HEADER_DARK = {  # 深色下标题栏用稍暗的底，白字仍够对比度
    "sales": "#2B63C7", "prod": "#0C8763", "purch": "#B85A17",
    "wh": "#0C7F90", "qa": "#5B3BD0", "fin": "#46546E",
}

THEMES = {
    "light": dict(
        bg="#F4F7FB", node="#FFFFFF", title="#1F2937", sub="#5B6573",
        derived_fill="#ECEFF3", derived_stroke="#B5BDC9", derived_title="#4B5563",
        chip_fill="#E6EAF0", chip_stroke="#C5CDD8", chip_text="#4B5563",
        gate_fill="#F1ECFF", gate_title="#3F22A8", gate_sub="#5B4A9E",
        tint=0.12, lanes=LANES_LIGHT, headers=LANES_LIGHT, header_text="#FFFFFF",
        region_fill_opacity=0.035,
    ),
    "dark": dict(
        bg="#0E141B", node="#17202A", title="#E6EDF3", sub="#9AA7B4",
        derived_fill="#212A35", derived_stroke="#3D4957", derived_title="#B4BEC9",
        chip_fill="#212A35", chip_stroke="#3D4957", chip_text="#B4BEC9",
        gate_fill="#231C40", gate_title="#D4C6FF", gate_sub="#B3A4E8",
        tint=0.20, lanes=LANES_DARK, headers=HEADER_DARK, header_text="#FFFFFF",
        region_fill_opacity=0.05,
    ),
}

FONT = ("'PingFang SC','Hiragino Sans GB','Microsoft YaHei','Noto Sans CJK SC',"
        "'Source Han Sans SC','WenQuanYi Zen Hei',sans-serif")


def text_width(s, size):
    w = 0.0
    for ch in s:
        if ord(ch) > 0x2E80:
            w += size
        elif ch == " ":
            w += size * 0.32
        else:
            w += size * 0.6
    return w


def mix(hex_a, hex_b, t):
    a = [int(hex_a[i:i + 2], 16) for i in (1, 3, 5)]
    b = [int(hex_b[i:i + 2], 16) for i in (1, 3, 5)]
    return "#" + "".join(f"{round(x + (y - x) * t):02X}" for x, y in zip(a, b))


class Canvas:
    def __init__(self, theme):
        self.t = THEMES[theme]
        self.theme = theme
        self.back = []   # 分区、标题栏
        self.edges = []  # 连线
        self.front = []  # 节点
        self.labels = []
        self.markers = set()
        self.warnings = []

    def lane(self, key):
        return self.t["lanes"][key]

    # ---- 分区和标题栏 ----
    def header(self, x, y, w, text, key, h=44):
        fill = self.t["headers"][key]
        self.back.append(
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="6" fill="{fill}"/>'
            f'<text x="{x + w / 2}" y="{y + h / 2 + 7}" text-anchor="middle" font-size="20" '
            f'font-weight="700" letter-spacing="4" fill="{self.t["header_text"]}">{escape(text)}</text>')

    def region(self, x, y, w, h, key):
        c = self.lane(key)
        self.back.append(
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10" fill="{c}" '
            f'fill-opacity="{self.t["region_fill_opacity"]}" stroke="{c}" stroke-width="1.6" '
            f'stroke-dasharray="9 6"/>')

    # ---- 节点 ----
    def node(self, cx, y, w, title, sub=None, kind="doc", key="prod", h=NH):
        t = self.t
        c = self.lane(key)
        x = cx - w / 2
        stroke_w = 1.5
        if kind == "doc":
            fill, stroke, tc, sc = t["node"], c, t["title"], t["sub"]
        elif kind == "entity":
            fill = mix(t["node"], c, t["tint"])
            stroke, tc, sc = c, t["title"], t["sub"]
        elif kind == "derived":
            fill, stroke, tc, sc = t["derived_fill"], t["derived_stroke"], t["derived_title"], t["sub"]
        elif kind == "gate":
            qa = self.lane("qa")
            fill, stroke, tc, sc = t["gate_fill"], qa, t["gate_title"], t["gate_sub"]
            stroke_w = 2.2
        else:
            raise ValueError(kind)
        dash = ' stroke-dasharray="5 4"' if kind == "derived" else ""
        self.front.append(
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="8" fill="{fill}" '
            f'stroke="{stroke}" stroke-width="{stroke_w}"{dash}/>')
        if kind == "gate":
            # 骑在左上边框上的小盾牌：质量签批关口
            bx, by = x + 16, y
            self.front.append(
                f'<circle cx="{bx}" cy="{by}" r="9" fill="{stroke}"/>'
                f'<path d="M{bx - 4.5} {by - 4} l4.5 -1.8 l4.5 1.8 v3.6 c0 3 -2.2 4.8 -4.5 5.6 '
                f'c-2.3 -0.8 -4.5 -2.6 -4.5 -5.6z" fill="#FFFFFF"/>')
        self._texts(cx, y, w, h, title, sub, tc, sc)
        return (x, y, w, h)

    def diamond(self, cx, cy, w, h, title, sub, key):
        t = self.t
        c = self.lane(key)
        pts = f"{cx},{cy - h / 2} {cx + w / 2},{cy} {cx},{cy + h / 2} {cx - w / 2},{cy}"
        self.front.append(
            f'<polygon points="{pts}" fill="{t["node"]}" stroke="{c}" stroke-width="1.6" '
            f'stroke-linejoin="round"/>')
        self._texts(cx, cy - h / 2, w * 0.62, h, title, sub, t["title"], t["sub"])

    def _texts(self, cx, y, w, h, title, sub, tc, sc):
        if text_width(title, 15) > w - 14:
            self.warnings.append(f"标题超宽：{title}")
        if sub and text_width(sub, 12) > w - 12:
            self.warnings.append(f"副标题超宽：{sub}")
        if sub:
            self.front.append(
                f'<text x="{cx}" y="{y + h / 2 - 3}" text-anchor="middle" font-size="15" '
                f'font-weight="600" fill="{tc}">{escape(title)}</text>'
                f'<text x="{cx}" y="{y + h / 2 + 14}" text-anchor="middle" font-size="12" '
                f'fill="{sc}">{escape(sub)}</text>')
        else:
            self.front.append(
                f'<text x="{cx}" y="{y + h / 2 + 5}" text-anchor="middle" font-size="15" '
                f'font-weight="600" fill="{tc}">{escape(title)}</text>')

    def chip(self, cx, cy, text, w=None):
        t = self.t
        w = w or text_width(text, 12) + 20
        self.front.append(
            f'<rect x="{cx - w / 2}" y="{cy - 12}" width="{w}" height="24" rx="5" '
            f'fill="{t["chip_fill"]}" stroke="{t["chip_stroke"]}"/>'
            f'<text x="{cx}" y="{cy + 4.5}" text-anchor="middle" font-size="12" '
            f'fill="{t["chip_text"]}">{escape(text)}</text>')

    # ---- 连线 ----
    def edge(self, pts, key, dashed=False, arrow=True, both=False, width=1.8):
        c = self.lane(key)
        mid = f"m-{key}"
        self.markers.add(key)
        d = "M" + " L".join(f"{x} {y}" for x, y in pts)
        attrs = f'fill="none" stroke="{c}" stroke-width="{width}" stroke-linejoin="round"'
        if dashed:
            attrs += ' stroke-dasharray="6 5"'
        if arrow:
            attrs += f' marker-end="url(#{mid})"'
        if both:
            attrs += f' marker-start="url(#{mid})"'
        self.edges.append(f'<path d="{d}" {attrs}/>')

    def dot(self, x, y, key):
        self.edges.append(f'<circle cx="{x}" cy="{y}" r="3.5" fill="{self.lane(key)}"/>')

    def label(self, x, y, text, key, anchor="middle"):
        c = self.lane(key)
        tw = text_width(text, 12)
        if anchor == "middle":
            rx = x - tw / 2
        elif anchor == "start":
            rx = x
        else:
            rx = x - tw
        self.labels.append(
            f'<rect x="{rx - 4}" y="{y - 12}" width="{tw + 8}" height="17" rx="3" '
            f'fill="{self.t["bg"]}" fill-opacity="0.92"/>'
            f'<text x="{x}" y="{y}" text-anchor="{anchor}" font-size="12" fill="{c}" '
            f'font-weight="500">{escape(text)}</text>')

    def render(self, title, desc):
        defs = []
        for key in sorted(self.markers):
            c = self.lane(key)
            defs.append(
                f'<marker id="m-{key}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" '
                f'markerHeight="8" orient="auto-start-reverse"><path d="M0 0 L10 5 L0 10z" '
                f'fill="{c}"/></marker>')
        return (
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" '
            f'viewBox="0 0 {W} {H}" font-family="{FONT}" role="img" aria-labelledby="t d">\n'
            f'<title id="t">{escape(title)}</title>\n<desc id="d">{escape(desc)}</desc>\n'
            f'<defs>{"".join(defs)}</defs>\n'
            f'<rect width="{W}" height="{H}" rx="14" fill="{self.t["bg"]}"/>\n'
            + "\n".join(self.back) + "\n" + "\n".join(self.edges) + "\n"
            + "\n".join(self.front) + "\n" + "\n".join(self.labels) + "\n</svg>\n")


def draw(theme):
    cv = Canvas(theme)

    # ---------------- 布局常量 ----------------
    SL_X, SL_W = 24, 400          # 销售泳道
    CT_X, CT_W = 444, 792         # 中间：生产 / 仓库
    PL_X, PL_W = 1256, 400        # 采购泳道
    TOP = 84
    yA, yB, yC, yD = 112, 196, 280, 364
    HUB_Y, HUB_H = 476, 56
    W2 = 604
    WH_BOTTOM = 684
    LANE_BOTTOM = WH_BOTTOM + 10 + 44   # 与“仓库管理”标题栏底边对齐
    FIN_TOP = LANE_BOTTOM + 28
    R1, R2, R3, R4 = 790, 874, 958, 1044
    FIN_BOTTOM = 1126
    Q_TOP = FIN_BOTTOM + 10 + 44 + 30
    Q1, Q2 = Q_TOP + 20, Q_TOP + 96
    Q_BOTTOM = Q2 + NH + 20

    col = [522, 681, 840, 999, 1158]   # 中间五列中心
    CW = 124                          # 中间列节点宽
    gap = lambda i: (col[i] + col[i + 1]) / 2  # 两列之间的空隙中线

    S_MAIN, S_SUB = 175, 345           # 销售主列 / 副列
    P_MAIN, P_SIDE = 1410, 1578        # 采购主列 / 侧列

    def mid(y, h=NH):
        return y + h / 2

    # ---------------- 标题栏和分区 ----------------
    cv.header(SL_X, 24, SL_W, "销售管理", "sales")
    cv.header(CT_X, 24, CT_W, "生产管理", "prod")
    cv.header(PL_X, 24, PL_W, "采购管理", "purch")
    cv.region(SL_X, TOP, SL_W, LANE_BOTTOM - TOP, "sales")
    cv.region(PL_X, TOP, PL_W, LANE_BOTTOM - TOP, "purch")
    cv.region(CT_X, TOP, CT_W, yD + NH + 20 - TOP, "prod")
    cv.region(CT_X, yD + NH + 36, CT_W, WH_BOTTOM - (yD + NH + 36), "wh")
    cv.header(CT_X, WH_BOTTOM + 10, CT_W, "仓库管理", "wh")
    cv.region(SL_X, FIN_TOP, PL_X + PL_W - SL_X, FIN_BOTTOM - FIN_TOP, "fin")
    cv.header(SL_X, FIN_BOTTOM + 10, PL_X + PL_W - SL_X, "财务管理", "fin")
    cv.region(SL_X, Q_TOP, PL_X + PL_W - SL_X, Q_BOTTOM - Q_TOP, "qa")
    cv.header(SL_X, Q_BOTTOM + 10, PL_X + PL_W - SL_X, "质量与合规（贯穿全程）", "qa")

    # ================= 销售 =================
    cv.node(S_MAIN, yA, 210, "渠道客户", "经销商 · 电商平台 · 门店", "entity", "sales")
    cv.node(100, yB, 136, "经销商订单", "门户下单 · 内部审核", key="sales")
    cv.node(250, yB, 136, "电商订单", "ERP 接口 · 失败重试", key="sales")
    cv.node(S_MAIN, yC, 176, "协议价 · 信用额度", "带入单价 · 超额不能出库", "derived", "sales")
    cv.node(S_MAIN, HUB_Y, 160, "销售出库", "FEFO · 客户效期标准", key="sales", h=HUB_H)
    cv.chip(60, mid(HUB_Y, HUB_H), "批次", w=44)
    cv.node(S_SUB, W2, 124, "销售退货", "普通退货 · 召回回收", key="sales")

    # 客户 → 两类订单（分叉）
    yb1 = (yA + NH + yB) / 2
    cv.edge([(S_MAIN, yA + NH), (S_MAIN, yb1)], "sales", arrow=False)
    cv.edge([(100, yb1), (250, yb1)], "sales", arrow=False)
    cv.edge([(100, yb1), (100, yB)], "sales")
    cv.edge([(250, yb1), (250, yB)], "sales")
    # 两类订单 → 协议价 / 信用额度（合并）
    yb2 = (yB + NH + yC) / 2
    cv.edge([(100, yB + NH), (100, yb2), (S_MAIN, yb2)], "sales", arrow=False)
    cv.edge([(250, yB + NH), (250, yb2), (S_MAIN, yb2)], "sales", arrow=False)
    cv.edge([(S_MAIN, yb2), (S_MAIN, yC)], "sales")
    cv.edge([(S_MAIN, yC + NH), (S_MAIN, HUB_Y)], "sales")
    # 出库 → 退货
    cv.edge([(235, HUB_Y + HUB_H), (235, mid(W2)), (S_SUB - 62, mid(W2))], "sales")
    # 出库历史 → 补货建议
    fx = 382
    cv.edge([(S_MAIN + 80, HUB_Y + 14), (fx, HUB_Y + 14), (fx, mid(yA)), (col[0] - CW / 2, mid(yA))],
            "sales", dashed=True)
    cv.label(fx, 300, "出库历史", "sales")
    cv.label(fx, 318, "需求预测", "sales")

    # ================= 生产 =================
    cv.node(col[0], yA, CW, "补货建议", "MRP · 预测 · 齐套", "derived", "prod")
    cv.node(col[2], yA, CW, "批生产记录", "自产 · 工序 · 复核", key="prod")
    cv.node(col[3], yA, CW, "配方版本 · BOM", "签批生效 · 备案", "entity", "prod")
    cv.node(col[4], yA, CW, "工艺路线 · 设备", "自产工序 · 校准", "entity", "prod")

    cv.node(col[0], yB, CW, "自产单 / 委托单", "按生效配方版本", key="prod")
    cv.node(col[1], yB, CW, "按 FEFO 发料", "只发已放行批次", key="prod")
    cv.node(col[3], yB, CW, "完工入库", "物料平衡 95%–102%", key="prod")
    cv.node(col[4], yB, CW, "成品上市放行", "仅 QSO · 登记留样", "gate", "prod")

    cv.node(col[0], yC, CW, "委托生产合同", "签批生效才算依据", "entity", "prod")
    cv.node(col[2], yC, CW, "受托厂完工申报", "委托 · 出厂放行", key="prod")
    cv.chip(col[3], yC + 22, "成品批次 · 待放行")
    cv.node(col[2], yD, CW, "受托厂", "门户 · 驻厂监督", "entity", "prod")

    # 补货建议 → 生产单
    cv.edge([(col[0], yA + NH), (col[0], yB)], "prod")
    # 合同 ⇢ 委托单
    cv.edge([(col[0], yC), (col[0], yB + NH)], "prod", dashed=True)
    cv.label(col[0] + 8, (yB + NH + yC) / 2 + 5, "依据", "prod", anchor="start")
    # 生产单 → 发料
    cv.edge([(col[0] + CW / 2, mid(yB)), (col[1] - CW / 2, mid(yB))], "prod")
    # 发料 → 分叉（自产 / 委托）
    cv.edge([(col[1] + CW / 2, mid(yB)), (col[2], mid(yB))], "prod", arrow=False)
    cv.dot(col[2], mid(yB), "prod")
    cv.edge([(col[2], mid(yB)), (col[2], yA + NH)], "prod")
    cv.edge([(col[2], mid(yB)), (col[2], yC)], "prod")
    # 合并 → 完工入库
    g23 = gap(2)
    cv.edge([(col[2] + CW / 2, mid(yA)), (g23, mid(yA)), (g23, mid(yB))], "prod", arrow=False)
    cv.edge([(col[2] + CW / 2, mid(yC)), (g23, mid(yC)), (g23, mid(yB))], "prod", arrow=False)
    cv.edge([(g23, mid(yB)), (col[3] - CW / 2, mid(yB))], "prod")
    # 受托厂 → 完工申报
    cv.edge([(col[2], yD), (col[2], yC + NH)], "prod")
    # 完工入库 → 上市放行
    cv.edge([(col[3] + CW / 2, mid(yB)), (col[4] - CW / 2, mid(yB))], "prod")
    # 补货建议 → 采购单（沿生产区顶部）
    ty = 98
    rx = 1292
    cv.edge([(col[0] + 30, yA), (col[0] + 30, ty), (rx, ty), (rx, mid(yB)), (P_MAIN - 80, mid(yB))],
            "prod", dashed=True)
    cv.label(1040, ty + 4, "补货建议生成采购单", "prod")

    # ================= 仓库 =================
    hub_x0, hub_x1 = col[1] - CW / 2, col[4] + CW / 2
    hub_cx = (hub_x0 + hub_x1) / 2
    cv.node(hub_cx, HUB_Y, hub_x1 - hub_x0, "批次库存",
            "自有仓 · 平台仓 · 第三方仓 · 只分配已放行、未过期批次", "entity", "wh", h=HUB_H)
    cv.node(col[0], W2, CW, "退货隔离区", "不参与分配", key="wh")
    cv.node(col[1], W2, CW, "退货处置", "转可售 / 报废", "gate", "wh")
    cv.node(col[2], W2, CW, "库存作业", "调拨 · 盘点 · 报废", key="wh")
    cv.node(col[3], W2, CW, "期初导入", "QSO 签批", key="wh")
    cv.node(col[4], W2, CW, "受托厂寄存仓", "补料 · 余料退库", key="wh")

    hb = HUB_Y + HUB_H
    # 批次库存 → 发料；上市放行 → 批次库存
    cv.edge([(col[1], HUB_Y), (col[1], yB + NH)], "wh")
    cv.label(col[1] + 8, yD + 10, "FEFO 分配", "wh", anchor="start")
    cv.edge([(col[4], yB + NH), (col[4], HUB_Y)], "prod")
    cv.label(col[4] - 8, yD + 10, "放行后可用", "prod", anchor="end")
    # 库存作业
    cv.edge([(col[2], hb), (col[2], W2)], "wh", both=True)
    cv.edge([(col[3], W2), (col[3], hb)], "wh")
    cv.edge([(col[4], hb), (col[4], W2)], "wh")
    cv.label(col[4] - 8, (hb + W2) / 2 + 5, "委托发料转入", "wh", anchor="end")
    cv.edge([(col[0] + CW / 2, mid(W2)), (col[1] - CW / 2, mid(W2))], "wh")
    cv.edge([(col[1], W2), (col[1], hb)], "wh")
    cv.label(col[1] + 8, (hb + W2) / 2 + 5, "转可售", "wh", anchor="start")
    # 批次库存 → 销售出库
    cv.edge([(hub_x0, HUB_Y + 40), (S_MAIN + 80, HUB_Y + 40)], "wh")
    cv.label(col[0], HUB_Y + 34, "FEFO 出库", "wh")
    # 销售退货 → 退货隔离区
    cv.edge([(S_SUB + 62, mid(W2)), (col[0] - CW / 2, mid(W2))], "sales")

    # ================= 采购 =================
    cv.node(P_MAIN, yA, 160, "供应商", "合格名录 · 资质有效", "entity", "purch")
    cv.node(P_MAIN, yB, 160, "采购单", "禁用原料不能采购", key="purch")
    cv.node(P_SIDE, yB, 128, "询价比价", "封标 · 定标转协议价", key="purch")
    cv.node(P_MAIN, yC, 160, "送货通知", "逐批绑定 COA", key="purch")
    cv.node(P_SIDE, yC, 128, "供应商门户", "确认订单 · 交期", "entity", "purch")
    cv.node(P_MAIN, yD, 160, "采购收货", "生成物料批次", key="purch")
    cv.chip(P_SIDE, mid(yD), "物料批次 · 待检")
    cv.node(P_MAIN, HUB_Y, 160, "物料检验放行", "QA / QSO 签批", "gate", "purch", h=HUB_H)
    cv.node(P_MAIN, W2, 160, "退供", "质量签批后扣库存", key="purch")

    cv.edge([(P_MAIN, yA + NH), (P_MAIN, yB)], "purch")
    cv.edge([(P_SIDE - 64, mid(yB)), (P_MAIN + 80, mid(yB))], "purch")
    cv.edge([(P_MAIN, yB + NH), (P_MAIN, yC)], "purch")
    cv.edge([(P_SIDE - 64, mid(yC)), (P_MAIN + 80, mid(yC))], "purch")
    cv.edge([(P_MAIN, yC + NH), (P_MAIN, yD)], "purch")
    cv.edge([(P_MAIN, yD + NH), (P_MAIN, HUB_Y)], "purch")
    # 物料放行 → 批次库存
    cv.edge([(P_MAIN - 80, HUB_Y + 18), (hub_x1, HUB_Y + 18)], "purch")
    cv.label((hub_x1 + P_MAIN - 80) / 2, HUB_Y + 12, "合格入库", "purch")
    # 批次库存 → 退供
    vx = 1290
    cv.edge([(hub_x1, HUB_Y + 40), (vx, HUB_Y + 40), (vx, mid(W2)), (P_MAIN - 80, mid(W2))], "purch")

    # ================= 财务 =================
    cv.node(S_MAIN, R1, 160, "应收对账单", "出库行 − 退货冲减", key="sales")
    cv.node(S_MAIN, R2, 160, "销项发票", "开票 · 销项台账", key="sales")
    cv.node(S_MAIN, R3, 160, "财务审批", "审批人不能是制单人", key="sales")
    cv.diamond(S_MAIN, R4 + 26, 168, 68, "回款核销", None, "sales")

    cv.node(P_MAIN, R1, 160, "采购 / 委外对账单", "只结已放行批次", key="purch")
    cv.node(P_MAIN, R2, 160, "进项发票", "金额与合计一致", key="purch")
    cv.node(P_SIDE, R2, 128, "供应商 / 受托厂", "门户确认 · 开票", "entity", "purch")
    cv.node(P_MAIN, R3, 160, "财务审批", "审批人不能是制单人", key="purch")
    cv.diamond(P_MAIN, R4 + 26, 168, 68, "付款核销", None, "purch")

    cv.node(col[0], R1, 136, "信用额度", "待收 + 已出库未对账", "derived", "fin")
    cv.node(col[4], R1, 136, "委外加工费", "上市放行后可结算", "derived", "fin")
    cv.node(col[0], R2, 136, "应收应付账龄", "账龄分段 · 催收", "derived", "fin")
    cv.node(col[2], R2, 136, "批次成本 · 毛利", "标准成本估值", "derived", "fin")
    cv.node(col[4], R2, 136, "现金流预测", "按应收应付到期日", "derived", "fin")

    cv.node(col[0], R4, 136, "回款登记", "手工 · 回调", key="fin")
    cv.node(col[2], R4, 150, "财务系统", "凭证 · 回执 · 收付款", "entity", "fin")
    cv.node(col[4], R4, 136, "付款登记", "手工 · 回调", key="fin")

    # 销售出库 → 应收；放行来料 → 应付；退供冲减
    cv.edge([(S_MAIN, HUB_Y + HUB_H), (S_MAIN, R1)], "sales")
    cv.label(S_MAIN - 8, FIN_TOP - 30, "已出库", "sales", anchor="end")
    ax = 1640
    cv.edge([(P_MAIN + 80, mid(HUB_Y, HUB_H)), (ax, mid(HUB_Y, HUB_H)), (ax, mid(R1)), (P_MAIN + 80, mid(R1))],
            "purch")
    cv.label(ax - 8, FIN_TOP - 30, "已放行来料", "purch", anchor="end")
    cv.edge([(P_MAIN, W2 + NH), (P_MAIN, R1)], "purch", dashed=True)
    cv.label(P_MAIN - 8, FIN_TOP - 30, "退供冲减", "purch", anchor="end")
    # 应收链
    cv.edge([(S_MAIN, R1 + NH), (S_MAIN, R2)], "sales")
    cv.edge([(S_MAIN, R2 + NH), (S_MAIN, R3)], "sales")
    cv.edge([(S_MAIN, R3 + NH), (S_MAIN, R4 + 26 - 34)], "sales")
    cv.edge([(S_MAIN + 80, mid(R1)), (col[0] - 68, mid(R1))], "sales", dashed=True)
    # 应付链
    cv.edge([(P_MAIN, R1 + NH), (P_MAIN, R2)], "purch")
    cv.edge([(P_SIDE - 64, mid(R2)), (P_MAIN + 80, mid(R2))], "purch")
    cv.edge([(P_MAIN, R2 + NH), (P_MAIN, R3)], "purch")
    cv.edge([(P_MAIN, R3 + NH), (P_MAIN, R4 + 26 - 34)], "purch")
    cv.edge([(col[4] + 68, mid(R1)), (P_MAIN - 80, mid(R1))], "fin")
    # 审批后凭证 → 财务系统
    jy = mid(R3)
    cv.edge([(S_MAIN + 80, jy), (col[2], jy)], "fin", arrow=False)
    cv.edge([(P_MAIN - 80, jy), (col[2], jy)], "fin", arrow=False)
    cv.dot(col[2], jy, "fin")
    cv.edge([(col[2], jy), (col[2], R4)], "fin")
    cv.label(col[2], jy - 8, "审批后生成凭证，财务系统拉取并回执", "fin")
    # 财务系统 → 回款 / 付款登记 → 核销
    cv.edge([(col[2] - 75, mid(R4)), (col[0] + 68, mid(R4))], "fin")
    cv.label((col[2] - 75 + col[0] + 68) / 2, mid(R4) - 8, "回款回调", "fin")
    cv.edge([(col[2] + 75, mid(R4)), (col[4] - 68, mid(R4))], "fin")
    cv.label((col[2] + 75 + col[4] - 68) / 2, mid(R4) - 8, "付款回调", "fin")
    cv.edge([(col[0] - 68, mid(R4)), (S_MAIN + 84, mid(R4))], "sales")
    cv.edge([(col[4] + 68, mid(R4)), (P_MAIN - 84, mid(R4))], "purch")

    # ================= 质量与合规 =================
    qx = [165, 435, 705, 975, 1245, 1515]
    QW = 230
    row1 = [
        ("质量标准 · 批次检验", "可采用供方报告 · 不合格转偏差"),
        ("偏差 · CAPA", "重大偏差未关不能放行"),
        ("留样台账 · 贮存环境", "效期 + 6 个月 · 温湿度超限转偏差"),
        ("稳定性试验", "签批结论作保质期依据"),
        ("投诉 · 不良反应 · 召回", "逐客户通知 · 定期演练"),
        ("供应商审核", "合格名录 · 资质到期提醒"),
    ]
    row2 = [
        ("变更控制", "配方 · 标签 · 原料变更"),
        ("受控文件 · 培训", "已读确认 · 考核 · 制度覆盖"),
        ("标签设计稿 · 打样", "包材放行核对印刷一致"),
        ("GMP 自查", "检查要点委托生产版 24 项"),
        ("质量安全负责人", "任职 · 协助履职授权"),
        ("批次追溯 · 审计日志", "正查到客户 · 反查到原料"),
    ]
    for x, (t1, s1) in zip(qx, row1):
        cv.node(x, Q1, QW, t1, s1, key="qa")
    for x, (t2, s2) in zip(qx, row2):
        cv.node(x, Q2, QW, t2, s2, key="qa")

    # ================= 图例 =================
    ly = H - 52
    lx = 40
    t = cv.t

    def legend_box(x, kind, text):
        if kind == "doc":
            fill, stroke, dash = t["node"], cv.lane("prod"), ""
        elif kind == "entity":
            fill, stroke, dash = mix(t["node"], cv.lane("prod"), t["tint"]), cv.lane("prod"), ""
        elif kind == "derived":
            fill, stroke, dash = t["derived_fill"], t["derived_stroke"], ' stroke-dasharray="5 4"'
        elif kind == "gate":
            fill, stroke, dash = t["gate_fill"], cv.lane("qa"), ""
        else:
            fill, stroke, dash = t["chip_fill"], t["chip_stroke"], ""
        cv.front.append(
            f'<rect x="{x}" y="{ly - 11}" width="30" height="20" rx="5" fill="{fill}" '
            f'stroke="{stroke}" stroke-width="1.5"{dash}/>'
            f'<text x="{x + 40}" y="{ly + 4}" font-size="13" fill="{t["sub"]}">{escape(text)}</text>')
        return x + 40 + text_width(text, 13) + 34

    lx = legend_box(lx, "doc", "单据 / 操作")
    lx = legend_box(lx, "entity", "往来单位 · 外部系统 · 主数据")
    lx = legend_box(lx, "derived", "系统计算")
    lx = legend_box(lx, "gate", "质量签批关口")
    lx = legend_box(lx, "chip", "批次状态")
    for dashed, text in ((False, "单据流 / 实物流"), (True, "依据 · 生成 · 冲减")):
        cv.edge([(lx, ly), (lx + 40, ly)], "fin", dashed=dashed)
        cv.front.append(f'<text x="{lx + 50}" y="{ly + 4}" font-size="13" fill="{t["sub"]}">{escape(text)}</text>')
        lx += 50 + text_width(text, 13) + 34

    title = "化妆品供应链系统业务流程架构图"
    desc = ("按销售、生产、采购、仓库、财务分区，展示从采购收货、物料检验放行、自产和委托生产、"
            "成品上市放行、FEFO 销售出库，到应收应付对账和财务系统回调的单据流，以及贯穿全程的质量与合规模块。")
    for w in cv.warnings:
        print(f"[{theme}] {w}")
    return cv.render(title, desc)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "business-flow.svg").write_text(draw("light"), encoding="utf-8")
    (OUT / "business-flow-dark.svg").write_text(draw("dark"), encoding="utf-8")
    print("已生成", OUT / "business-flow.svg", OUT / "business-flow-dark.svg")


if __name__ == "__main__":
    main()
