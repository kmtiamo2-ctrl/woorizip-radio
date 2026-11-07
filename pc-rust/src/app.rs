//! 창 화면 (egui). Python판 창과 같은 구성 — 상태 줄 / 방 버튼 3줄 격자 / 말하기 버튼(1/3 높이) / 대화 말풍선 / 입력줄.
use crate::state::{josa, member_style, Clip, Kind, Notice, Room, State};
use eframe::egui::{
    self, Align, Color32, CornerRadius, FontId, Frame, Layout, Margin, RichText, Sense, Stroke,
    Vec2,
};
use std::sync::{Arc, Mutex};

// 색 (Python판과 같음)
pub const BG: Color32 = Color32::from_rgb(0x0B, 0x12, 0x20);
pub const CARD: Color32 = Color32::from_rgb(0x14, 0x1C, 0x2E);
pub const CARD2: Color32 = Color32::from_rgb(0x1C, 0x27, 0x40);
pub const TXT: Color32 = Color32::from_rgb(0xF3, 0xF5, 0xF9);
pub const SUB: Color32 = Color32::from_rgb(0x9A, 0xA6, 0xBC);
pub const AMBER: Color32 = Color32::from_rgb(0xF5, 0xB3, 0x01);
pub const ON_AMBER: Color32 = Color32::from_rgb(0x1A, 0x12, 0x00);
pub const GREEN: Color32 = Color32::from_rgb(0x34, 0xD3, 0x99);
pub const RED: Color32 = Color32::from_rgb(0xF2, 0x6D, 0x6D);
pub const OUTLINE: Color32 = Color32::from_rgb(0x2B, 0x37, 0x52);
pub const ON_DARK: Color32 = Color32::from_rgb(0x10, 0x14, 0x18);

pub fn rgb(c: [u8; 3]) -> Color32 {
    Color32::from_rgb(c[0], c[1], c[2])
}
/// 두 색을 섞음 (a=0 → bg, 1 → fg). 대화 묶음 상자의 연한 바탕색에 사용
pub fn blend(fg: Color32, bg: Color32, a: f32) -> Color32 {
    let m = |f: u8, b: u8| (b as f32 + (f as f32 - b as f32) * a) as u8;
    Color32::from_rgb(m(fg.r(), bg.r()), m(fg.g(), bg.g()), m(fg.b(), bg.b()))
}

pub type Shared = Arc<Mutex<State>>;

pub struct App {
    pub state: Shared,
    pub pump: Box<dyn Fn(&mut State) + Send>, // state.outgoing → 서버
    input: String,
    shown_count: usize,
    shown_room: String,
    stick_bottom: bool,
    search: String,
    search_open: bool,
    flash_until: f64,
    last_notice: String,
}

impl App {
    pub fn new(
        cc: &eframe::CreationContext<'_>,
        state: Shared,
        pump: Box<dyn Fn(&mut State) + Send>,
    ) -> App {
        install_fonts(&cc.egui_ctx);
        let mut style = (*cc.egui_ctx.style()).clone();
        style.visuals = egui::Visuals::dark();
        style.visuals.panel_fill = BG;
        style.visuals.window_fill = CARD;
        style.visuals.override_text_color = Some(TXT);
        style.visuals.widgets.inactive.bg_fill = CARD2;
        style.visuals.widgets.hovered.bg_fill = OUTLINE;
        style.visuals.selection.bg_fill = AMBER;
        style.spacing.item_spacing = Vec2::new(6.0, 6.0);
        style
            .text_styles
            .insert(egui::TextStyle::Body, FontId::proportional(14.0));
        style
            .text_styles
            .insert(egui::TextStyle::Button, FontId::proportional(14.0));
        style
            .text_styles
            .insert(egui::TextStyle::Small, FontId::proportional(11.0));
        cc.egui_ctx.set_style(style);
        App {
            state,
            pump,
            input: String::new(),
            shown_count: 40,
            shown_room: String::new(),
            stick_bottom: true,
            search: String::new(),
            search_open: false,
            flash_until: 0.0,
            last_notice: String::new(),
        }
    }

    fn with_state<R>(&self, f: impl FnOnce(&mut State) -> R) -> R {
        let mut s = self.state.lock().unwrap();
        let r = f(&mut s);
        (self.pump)(&mut s);
        r
    }
}

/// 한글 글꼴: Windows 는 맑은 고딕, 그 외는 Noto CJK 를 찾아 붙임 (egui 기본 글꼴엔 한글이 없음).
/// 그림 글자(이모지)는 egui 에 든 흑백 글꼴을 씀 — 시스템의 컬러 이모지 글꼴은 테두리 정보가 없어 빈 칸으로 그려지므로 쓰지 않음
fn install_fonts(ctx: &egui::Context) {
    let candidates = [
        r"C:\Windows\Fonts\malgun.ttf",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/truetype/nanum/NanumGothic.ttf",
    ];
    let mut fonts = egui::FontDefinitions::default();
    let mut found = false;
    for p in candidates {
        if let Ok(b) = std::fs::read(p) {
            fonts
                .font_data
                .insert("kr".into(), egui::FontData::from_owned(b).into());
            found = true;
            break;
        }
    }
    if !found {
        crate::log::info("한글 글꼴을 찾지 못했어요 (글자가 □ 로 보일 수 있음)");
    }
    for fam in [egui::FontFamily::Proportional, egui::FontFamily::Monospace] {
        let list = fonts.families.entry(fam).or_default();
        if found {
            list.insert(0, "kr".into());
        }
    }
    ctx.set_fonts(fonts);
}

fn day_label(ts: i64) -> String {
    use chrono::{Datelike, Local, TimeZone};
    let d = Local
        .timestamp_opt(ts, 0)
        .single()
        .unwrap_or_else(Local::now);
    let today = Local::now();
    if d.date_naive() == today.date_naive() {
        return "오늘".into();
    }
    if d.date_naive() == (today - chrono::Duration::days(1)).date_naive() {
        return "어제".into();
    }
    let wd =
        ["월", "화", "수", "목", "금", "토", "일"][d.weekday().num_days_from_monday() as usize];
    format!("{:02}/{:02} ({wd})", d.month(), d.day())
}
fn time_label(ts: i64) -> String {
    use chrono::{Local, TimeZone};
    Local
        .timestamp_opt(ts, 0)
        .single()
        .map(|d| d.format("%H:%M").to_string())
        .unwrap_or_default()
}
pub fn human_size(n: i64) -> String {
    let f = n as f64;
    if n < 1024 {
        format!("{n} B")
    } else if f < 1048576.0 {
        format!("{:.1} KB", f / 1024.0)
    } else if f < 1073741824.0 {
        format!("{:.1} MB", f / 1048576.0)
    } else {
        format!("{:.2} GB", f / 1073741824.0)
    }
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        // 알림 처리 (팝업·깜빡임은 4일차 트레이와 함께; 지금은 창 제목 깜빡임만)
        let notices: Vec<Notice> = self.with_state(|s| std::mem::take(&mut s.notices));
        for n in notices {
            match n {
                Notice::Text(_) | Notice::FileIn(_) | Notice::Incoming { .. } => {
                    self.flash_until = ctx.input(|i| i.time) + 1.5;
                }
                Notice::Schedules => {}
            }
        }
        if ctx.input(|i| i.time) < self.flash_until {
            ctx.request_repaint_after(std::time::Duration::from_millis(200));
        }

        let (room, connected, status, me, online_n, where_, notice, talking, requesting, incoming) =
            self.with_state(|s| {
                (
                    s.current_room(),
                    s.connected,
                    s.status.clone(),
                    s.cfg.name.clone(),
                    s.online.len(),
                    s.where_.clone(),
                    s.notice.clone(),
                    s.talking,
                    s.requesting,
                    s.incoming_from.clone().map(|f| {
                        (
                            s.disp(&f).to_string(),
                            s.incoming_mode.clone().unwrap_or_default(),
                        )
                    }),
                )
            });
        if room.key != self.shown_room {
            self.shown_room = room.key.clone();
            self.shown_count = 40;
            self.stick_bottom = true;
        }
        if notice != self.last_notice {
            self.last_notice = notice.clone();
        }

        egui::CentralPanel::default()
            .frame(Frame::NONE.fill(BG).inner_margin(Margin::same(12)))
            .show(ctx, |ui| {
                // ---- 상태 줄 ----
                ui.horizontal(|ui| {
                    let dot = if connected { GREEN } else { RED };
                    ui.label(RichText::new("●").color(dot).size(13.0));
                    let where_txt = match where_.as_deref() {
                        Some("home") => "",
                        Some("out") => " · 밖",
                        _ => "",
                    };
                    let txt = if connected {
                        format!("{me} · 연결됨 · 가족 {online_n}명{where_txt}")
                    } else {
                        format!("{me} · {status}")
                    };
                    ui.label(
                        RichText::new(txt)
                            .color(if connected { GREEN } else { SUB })
                            .size(13.0),
                    );
                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        if ui
                            .add(small_btn("🔍", self.search_open))
                            .on_hover_text("대화 검색")
                            .clicked()
                        {
                            self.search_open = !self.search_open;
                            if !self.search_open {
                                self.search.clear();
                            }
                        }
                        if ui.add(small_btn("⚙ 설정", false)).clicked() { /* 5일차 */ }
                        if ui.add(small_btn("⏰ 예약", false)).clicked() { /* 5일차 */ }
                    });
                });
                ui.add_space(6.0);

                // ---- 방 버튼 3줄 격자 ----
                let (grid, rooms_info) = self.with_state(|s| {
                    let g = s.room_grid();
                    let info: Vec<Vec<(bool, bool, usize, bool)>> = g
                        .iter()
                        .map(|row| {
                            row.iter()
                                .map(|r| {
                                    let single =
                                        r.members.as_ref().map(|m| m.len() == 1).unwrap_or(false)
                                            && !s.is_me_room(r);
                                    let on = single
                                        && s.online.contains(&r.members.as_ref().unwrap()[0]);
                                    (single, on, s.unread_count(r), s.is_me_room(r))
                                })
                                .collect()
                        })
                        .collect();
                    (g, info)
                });
                let mut clicked: Option<(String, bool)> = None; // (key, 우클릭=토글)
                let gap = 8.0;
                for (ri, row) in grid.iter().enumerate() {
                    let w =
                        (ui.available_width() - gap * (row.len() as f32 - 1.0)) / row.len() as f32;
                    ui.horizontal(|ui| {
                        ui.spacing_mut().item_spacing.x = gap;
                        for (ci, r) in row.iter().enumerate() {
                            let (single, on, unread, me_room) = rooms_info[ri][ci];
                            let selected = r.key == room.key;
                            let resp = room_button(
                                ui, r, selected, single, on, unread, me_room, online_n, w,
                            );
                            if resp.clicked() {
                                clicked = Some((r.key.clone(), false));
                            }
                            if resp.secondary_clicked() && single {
                                clicked = Some((r.members.as_ref().unwrap()[0].clone(), true));
                            }
                        }
                    });
                }
                if let Some((k, toggle)) = clicked {
                    self.with_state(|s| {
                        if toggle {
                            s.toggle_target(&k)
                        } else {
                            s.select_room(&k)
                        }
                    });
                }
                ui.add_space(8.0);

                // ---- 말하기 버튼 (Python판의 1/3 높이) ----
                let me_room = self.with_state(|s| s.is_me_room(&room));
                let (title, sub, fill, fg) = match () {
                    _ if incoming.is_some() => {
                        let (f, m) = incoming.clone().unwrap();
                        (
                            format!(
                                "🔊 {f}{}",
                                if m == "all" {
                                    " 전체 방송 중"
                                } else {
                                    "가 나에게 말하는 중"
                                }
                            ),
                            String::new(),
                            CARD2,
                            GREEN,
                        )
                    }
                    _ if talking => (
                        format!("{}에게 말하는 중…", room.label),
                        "떼면 끝".into(),
                        RED,
                        TXT,
                    ),
                    _ if requesting => ("연결 중…".into(), String::new(), CARD2, SUB),
                    _ if !connected => ("연결 대기 중".into(), status.clone(), CARD2, SUB),
                    _ if me_room => (
                        "📝 나에게 메모".into(),
                        "말하기는 안 되고 글·사진만 — 내 기기에서만 보여요".into(),
                        CARD2,
                        SUB,
                    ),
                    _ => (
                        format!(
                            "{} {}에게 말하기",
                            room.icon,
                            if room.key == "all" {
                                "모두".to_string()
                            } else {
                                room.label.clone()
                            }
                        ),
                        "누르고 있는 동안 말해요".into(),
                        if room.key == "all" {
                            AMBER
                        } else {
                            rgb(room.color)
                        },
                        ON_DARK,
                    ),
                };
                let talk_h = 44.0;
                let (rect, resp) =
                    ui.allocate_exact_size(Vec2::new(ui.available_width(), talk_h), Sense::drag());
                let painter = ui.painter();
                painter.rect_filled(
                    rect,
                    CornerRadius::same(12),
                    if resp.dragged() && !me_room {
                        RED
                    } else {
                        fill
                    },
                );
                let tf = if sub.is_empty() { 16.0 } else { 15.0 };
                if sub.is_empty() {
                    painter.text(
                        rect.center(),
                        egui::Align2::CENTER_CENTER,
                        &title,
                        FontId::proportional(tf),
                        fg,
                    );
                } else {
                    painter.text(
                        rect.center() - Vec2::new(0.0, 7.0),
                        egui::Align2::CENTER_CENTER,
                        &title,
                        FontId::proportional(tf),
                        fg,
                    );
                    painter.text(
                        rect.center() + Vec2::new(0.0, 11.0),
                        egui::Align2::CENTER_CENTER,
                        &sub,
                        FontId::proportional(10.5),
                        fg.gamma_multiply(0.8),
                    );
                }
                if resp.drag_started() {
                    self.with_state(|s| s.start_talk());
                }
                if resp.drag_stopped() {
                    self.with_state(|s| s.stop_talk());
                }
                if !notice.is_empty() {
                    ui.label(RichText::new(&notice).color(SUB).size(11.5));
                }
                ui.add_space(4.0);

                // ---- 대화 ----
                let head = if room.key == "all" {
                    "모두의 대화".to_string()
                } else if me_room {
                    "나의 메모".to_string()
                } else {
                    format!("{}{}의 대화", room.label, josa(&room.label, "과", "와"))
                };
                ui.horizontal(|ui| {
                    ui.label(
                        RichText::new(format!("{} {head}", room.icon))
                            .color(rgb(room.color))
                            .strong()
                            .size(13.5),
                    );
                    if self.search_open {
                        ui.add(
                            egui::TextEdit::singleline(&mut self.search)
                                .hint_text("대화 검색…")
                                .desired_width(180.0),
                        );
                    }
                });

                let input_h = 92.0;
                let chat_h = (ui.available_height() - input_h).max(80.0);
                let mut scroll = egui::ScrollArea::vertical()
                    .auto_shrink([false; 2])
                    .max_height(chat_h)
                    .min_scrolled_height(chat_h);
                if self.stick_bottom {
                    scroll = scroll.stick_to_bottom(true);
                }
                let out = scroll.show(ui, |ui| {
                    self.draw_messages(ui, &room, me_room);
                });
                // 맨 위로 올리면 더 옛 글 (화면에 더 그리고, 그것도 다 보였으면 서버에서)
                let at_top = out.state.offset.y < 30.0;
                let at_bottom =
                    out.state.offset.y + out.inner_rect.height() >= out.content_size.y - 40.0;
                self.stick_bottom = at_bottom;
                if at_top {
                    let total = self.with_state(|s| s.room_clips(&room).len());
                    if self.shown_count < total {
                        self.shown_count += 40;
                    } else {
                        self.with_state(|s| {
                            s.load_older();
                        });
                        if self.with_state(|s| s.loading_older) {
                            self.shown_count += 100;
                        }
                    }
                }

                // ---- 입력줄 ----
                ui.add_space(4.0);
                let quick = self.with_state(|s| s.quick_replies.clone());
                ui.horizontal_wrapped(|ui| {
                    for q in &quick {
                        if ui
                            .add(
                                egui::Button::new(RichText::new(q).size(12.0))
                                    .fill(CARD2)
                                    .stroke(Stroke::new(1.0_f32, OUTLINE))
                                    .corner_radius(CornerRadius::same(14)),
                            )
                            .clicked()
                        {
                            self.with_state(|s| {
                                s.send_text(q, None);
                            });
                        }
                    }
                });
                let room_col = if room.key == "all" {
                    AMBER
                } else {
                    rgb(room.color)
                };
                ui.horizontal(|ui| {
                    let plus = egui::Button::new(RichText::new("+").size(20.0))
                        .fill(CARD)
                        .stroke(Stroke::new(1.0_f32, OUTLINE))
                        .corner_radius(CornerRadius::same(10))
                        .min_size(Vec2::new(34.0, 34.0));
                    let _ = ui.add(plus).on_hover_text("사진·파일·예약 (5일차)");
                    let send_w = 72.0;
                    let edit_w = ui.available_width() - send_w - 8.0;
                    let hint = if me_room {
                        "나에게 메모".to_string()
                    } else {
                        format!(
                            "{}에게 메시지",
                            if room.key == "all" {
                                "모두"
                            } else {
                                &room.label
                            }
                        )
                    };
                    let edit = egui::TextEdit::singleline(&mut self.input)
                        .hint_text(hint)
                        .desired_width(edit_w)
                        .margin(Margin::symmetric(10, 8))
                        .font(FontId::proportional(14.0));
                    let resp = Frame::NONE
                        .stroke(Stroke::new(1.5_f32, room_col))
                        .corner_radius(CornerRadius::same(10))
                        .fill(CARD)
                        .show(ui, |ui| ui.add(edit))
                        .inner;
                    let enter = resp.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter));
                    let send = egui::Button::new(RichText::new("보내기").color(ON_DARK).strong())
                        .fill(room_col)
                        .corner_radius(CornerRadius::same(10))
                        .min_size(Vec2::new(send_w, 34.0));
                    if (ui.add(send).clicked() || enter) && !self.input.trim().is_empty() {
                        let t = self.input.clone();
                        if self.with_state(|s| s.send_text(&t, None)) {
                            self.input.clear();
                            self.stick_bottom = true;
                        }
                        resp.request_focus();
                    }
                });
            });
    }
}

fn small_btn(text: &str, active: bool) -> egui::Button<'_> {
    egui::Button::new(
        RichText::new(text)
            .size(12.0)
            .color(if active { ON_DARK } else { SUB }),
    )
    .fill(if active { AMBER } else { CARD2 })
    .stroke(Stroke::new(1.0_f32, OUTLINE))
    .corner_radius(CornerRadius::same(12))
}

/// 방 버튼 하나. 격자가 폭을 정하고 글자는 가운데. 내 자리('나')는 접속 표시 없음
#[allow(clippy::too_many_arguments)]
fn room_button(
    ui: &mut egui::Ui,
    r: &Room,
    selected: bool,
    single: bool,
    on: bool,
    unread: usize,
    me_room: bool,
    online_n: usize,
    w: f32,
) -> egui::Response {
    let base = match &r.members {
        None => format!("{online_n}명 접속"),
        Some(_) if me_room => String::new(),
        Some(m) if single => {
            if on {
                String::new()
            } else {
                "꺼짐".into()
            }
        }
        Some(m) => format!("{}명", m.len()),
    };
    let mut parts = vec![];
    if !base.is_empty() {
        parts.push(base);
    }
    if unread > 0 {
        parts.push(format!("새 글 {unread}"));
    }
    let sub = parts.join(" · ");
    let dim = single && !on;
    let col = rgb(r.color);
    let (rect, resp) = ui.allocate_exact_size(Vec2::new(w, 38.0), Sense::click());
    let p = ui.painter();
    let fill = if selected {
        col
    } else if resp.hovered() {
        CARD2
    } else {
        CARD
    };
    if r.key == "all" {
        // 가족 5명 색 테두리
        let cols = ["엄마", "아빠", "첫째", "둘째", "셋째"].map(|n| rgb(member_style(n).1));
        let seg = rect.width() / cols.len() as f32;
        for (i, c) in cols.iter().enumerate() {
            let x0 = rect.left() + seg * i as f32;
            p.rect_filled(
                egui::Rect::from_min_max(
                    egui::pos2(x0, rect.top()),
                    egui::pos2(x0 + seg + 0.5, rect.bottom()),
                ),
                CornerRadius::ZERO,
                *c,
            );
        }
        p.rect_filled(rect.shrink(2.0), CornerRadius::same(12), fill);
        // 바깥 모서리를 바탕색으로 다듬기 위해 테두리만 둥글게 덧그림
        p.rect_stroke(
            rect,
            CornerRadius::same(14),
            Stroke::new(2.0_f32, BG),
            egui::StrokeKind::Outside,
        );
    } else {
        p.rect_filled(rect, CornerRadius::same(14), fill);
        p.rect_stroke(
            rect,
            CornerRadius::same(14),
            Stroke::new(1.0_f32, if selected { col } else { OUTLINE }),
            egui::StrokeKind::Inside,
        );
    }
    let fg = if selected {
        ON_DARK
    } else if dim {
        SUB
    } else {
        col
    };
    let text = format!(
        "{} {}{}",
        r.icon,
        r.label,
        if sub.is_empty() {
            String::new()
        } else {
            format!("  ·  {sub}")
        }
    );
    let galley = p.layout_no_wrap(text, FontId::proportional(13.5), fg);
    let mut pos = rect.center() - galley.size() / 2.0;
    if on {
        pos.x += 7.0;
    }
    p.galley(pos, galley.clone(), fg);
    if on {
        p.circle_filled(
            egui::pos2(pos.x - 10.0, rect.center().y),
            4.0,
            if selected { ON_DARK } else { GREEN },
        );
    }
    resp
}

impl App {
    fn draw_messages(&mut self, ui: &mut egui::Ui, room: &Room, _me_room: bool) {
        let (clips, convs, receipts, me) = self.with_state(|s| {
            let mut all: Vec<Clip> = s.room_clips(room).into_iter().cloned().collect();
            if !self.search.trim().is_empty() {
                let q = self.search.trim().to_lowercase();
                all.retain(|c| {
                    c.text.to_lowercase().contains(&q) || c.name.to_lowercase().contains(&q)
                });
            }
            let shown: Vec<Clip> = all
                .into_iter()
                .take(self.shown_count)
                .collect::<Vec<_>>()
                .into_iter()
                .rev()
                .collect();
            let convs: Vec<Option<(String, String, [u8; 3])>> =
                shown.iter().map(|c| s.conv_of(c)).collect();
            let receipts: Vec<String> = shown
                .iter()
                .map(|c| {
                    if c.mine {
                        s.receipt_text(c)
                    } else {
                        String::new()
                    }
                })
                .collect();
            // 화면에 보인 받은 글은 읽음 처리
            for c in &shown {
                s.mark_seen(c.mid, c.mine);
            }
            (shown, convs, receipts, s.cfg.name.clone())
        });
        let disp = |n: &str| self.with_state(|s| s.disp(n).to_string());
        if clips.is_empty() {
            ui.add_space(20.0);
            ui.vertical_centered(|ui| {
                ui.label(
                    RichText::new(if self.search.is_empty() {
                        "아직 대화가 없어요"
                    } else {
                        "찾는 글이 없어요"
                    })
                    .color(SUB),
                )
            });
            return;
        }
        let mut last_day = String::new();
        let mut i = 0;
        while i < clips.len() {
            let day = day_label(clips[i].ts);
            if day != last_day {
                last_day = day.clone();
                ui.vertical_centered(|ui| ui.label(RichText::new(day).color(SUB).size(11.0)));
            }
            // 같은 대화 상자(conv)로 이어지는 글을 한 묶음으로
            let conv = convs[i].clone();
            let mut j = i + 1;
            while j < clips.len() && convs[j] == conv && day_label(clips[j].ts) == last_day {
                j += 1;
            }
            let group: Vec<usize> = (i..j).collect();
            let draw_group = |ui: &mut egui::Ui, app: &App| {
                let mut prev_from: Option<String> = None;
                for &k in &group {
                    let c = &clips[k];
                    let same = prev_from.as_deref() == Some(c.from.as_str()) && !c.mine;
                    draw_bubble(
                        ui,
                        c,
                        same,
                        &receipts[k],
                        &me,
                        &disp,
                        room.key == "all",
                        app,
                    );
                    prev_from = Some(c.from.clone());
                }
            };
            match conv {
                Some((_, label, color)) if room.key == "all" => {
                    let col = rgb(color);
                    Frame::NONE
                        .fill(blend(col, BG, 0.07))
                        .stroke(Stroke::new(1.0_f32, blend(col, BG, 0.5)))
                        .corner_radius(CornerRadius::same(12))
                        .inner_margin(Margin::same(8))
                        .show(ui, |ui| {
                            ui.set_width(ui.available_width());
                            ui.label(RichText::new(label).color(col).size(11.5).strong());
                            draw_group(ui, self);
                        });
                }
                _ => draw_group(ui, self),
            }
            ui.add_space(4.0);
            i = j;
        }
    }
}

#[allow(clippy::too_many_arguments)]
fn draw_bubble(
    ui: &mut egui::Ui,
    c: &Clip,
    same: bool,
    receipt: &str,
    me: &str,
    disp: &dyn Fn(&str) -> String,
    in_all: bool,
    _app: &App,
) {
    let who_col = rgb(member_style(if c.mine { me } else { &c.from }).1);
    let max_w = ui.available_width() * 0.72;
    let layout = if c.mine {
        Layout::right_to_left(Align::Min)
    } else {
        Layout::left_to_right(Align::Min)
    };
    ui.with_layout(layout, |ui| {
        ui.vertical(|ui| {
            ui.set_max_width(max_w);
            if !c.mine && !same {
                ui.label(
                    RichText::new(format!("{} {}", member_style(&c.from).0, disp(&c.from)))
                        .color(who_col)
                        .size(12.5)
                        .strong(),
                );
            }
            let fill = if c.mine {
                blend(who_col, CARD, 0.35)
            } else {
                CARD2
            };
            let bubble = Frame::NONE
                .fill(fill)
                .corner_radius(CornerRadius::same(14))
                .inner_margin(Margin::symmetric(12, 8));
            let resp = bubble
                .show(ui, |ui| {
                    ui.set_max_width(max_w - 24.0);
                    match c.kind {
                        Kind::Text => {
                            ui.label(RichText::new(&c.text).size(14.0));
                        }
                        Kind::VoiceSent => {
                            ui.label(
                                RichText::new(format!("🎤 음성 {}초", (c.seconds as i64).max(1)))
                                    .size(13.5),
                            );
                        }
                        Kind::File => {
                            if c.expired {
                                ui.label(
                                    RichText::new(format!(
                                        "📎 {} · 기간이 지나 삭제됐어요",
                                        c.name
                                    ))
                                    .color(SUB)
                                    .size(13.0),
                                );
                            } else {
                                ui.label(
                                    RichText::new(format!(
                                        "📎 {}  ({})",
                                        c.name,
                                        human_size(c.size)
                                    ))
                                    .size(13.5),
                                );
                                ui.label(
                                    RichText::new("첨부 열기·미리보기는 5일차")
                                        .color(SUB)
                                        .size(10.5),
                                );
                            }
                        }
                        Kind::Clip => {
                            ui.label(
                                RichText::new(format!(
                                    "🔊 음성 {}초 (3일차)",
                                    (c.seconds as i64).max(1)
                                ))
                                .size(13.5),
                            );
                        }
                    }
                })
                .response;
            resp.context_menu(|ui| {
                if ui.button("복사").clicked() {
                    ui.ctx().copy_text(c.text.clone());
                    ui.close();
                }
                if ui.button("삭제").clicked() {
                    let (mid, id) = (c.mid, c.id);
                    let st = _app.state.clone();
                    st.lock().unwrap().delete_clip(mid, id);
                    ui.close();
                }
            });
            // 아래 작은 글: 시간 · (내 글이면) 누가 읽었는지 말로
            let mut meta = time_label(c.ts);
            let memo = c.mine && c.from == me;
            if c.mine && in_all && c.mode != "all" && !memo {
                meta = format!("{} · {}에게", meta, disp_list_simple(&c.from, disp));
            }
            if !receipt.is_empty() && !memo {
                meta = format!("{meta} · {receipt}");
            }
            ui.label(RichText::new(meta).color(SUB).size(10.5));
        });
    });
}

fn disp_list_simple(s: &str, disp: &dyn Fn(&str) -> String) -> String {
    s.split(", ")
        .filter(|x| !x.is_empty())
        .map(disp)
        .collect::<Vec<_>>()
        .join(", ")
}
