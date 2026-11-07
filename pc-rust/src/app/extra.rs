//! 창의 부가 화면: 첨부 말풍선·사진 크게 보기 / [+] 메뉴·끌어다 놓기·붙여넣기 / 예약 메시지 / 설정·처음 설정.
use super::*;
use crate::files;
use crate::state::Schedule;
use std::cell::RefCell;
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;

/// 해독된 사진: (fid, RGBA 바이트와 크기 또는 오류)
pub type Decoded = (String, Result<(Vec<u8>, [usize; 2]), String>);

/// 사진 크게 보기 창
pub struct Viewer {
    pub fid: String,
    pub name: String,
    pub tex: Option<egui::TextureHandle>,
    pub size: [usize; 2],
    pub zoom: f32,
    pub status: String,
}

pub struct SchedDialog {
    pub open: bool,
    pub text: String,
    pub date: String,
    pub time: String,
    pub repeat: String,
    pub err: String,
}

pub struct SettingsDialog {
    pub open: bool,
    pub unlocked: bool, // 사용자·주소를 바꾸려면 가족 암호
    pub pw: String,
    pub name: String,
    pub host: String,
    pub out_host: String,
    pub key: String,
    pub hotkey: String,
    pub quick: String,
    pub groups: String,
    pub autostart: bool,
    pub msg: String,
}

/// 첨부·예약·설정에 쓰는 상태 (App 안에 둠)
pub struct Extra {
    pub thumbs: RefCell<HashMap<String, egui::TextureHandle>>,
    pub thumb_missing: RefCell<HashSet<String>>,
    pub viewer: RefCell<Option<Viewer>>,
    pub viewer_rx: RefCell<Option<mpsc::Receiver<Decoded>>>,
    pub plus_open: bool,
    pub sched: SchedDialog,
    pub settings: SettingsDialog,
    pub setup_open: bool,
}

impl Extra {
    pub fn new(configured: bool) -> Extra {
        Extra {
            thumbs: RefCell::new(HashMap::new()),
            thumb_missing: RefCell::new(HashSet::new()),
            viewer: RefCell::new(None),
            viewer_rx: RefCell::new(None),
            plus_open: false,
            sched: SchedDialog {
                open: false,
                text: String::new(),
                date: String::new(),
                time: String::new(),
                repeat: "once".into(),
                err: String::new(),
            },
            settings: SettingsDialog {
                open: false,
                unlocked: false,
                pw: String::new(),
                name: String::new(),
                host: String::new(),
                out_host: String::new(),
                key: String::new(),
                hotkey: String::new(),
                quick: String::new(),
                groups: String::new(),
                autostart: false,
                msg: String::new(),
            },
            setup_open: !configured,
        }
    }
}

/// 받은 글의 미리보기 텍스처 (있으면). 없으면 내려받기를 시작
fn thumb_texture(app: &App, ctx: &egui::Context, c: &Clip) -> Option<egui::TextureHandle> {
    if let Some(t) = app.extra.thumbs.borrow().get(&c.fid) {
        return Some(t.clone());
    }
    if app.extra.thumb_missing.borrow().contains(&c.fid) {
        return None;
    }
    let p = files::thumb_path(c);
    if p.exists() {
        match image::open(&p) {
            Ok(img) => {
                let rgb = img.to_rgba8();
                let size = [rgb.width() as usize, rgb.height() as usize];
                let ci = egui::ColorImage::from_rgba_unmultiplied(size, rgb.as_raw());
                let tex =
                    ctx.load_texture(format!("thumb-{}", c.fid), ci, egui::TextureOptions::LINEAR);
                app.extra
                    .thumbs
                    .borrow_mut()
                    .insert(c.fid.clone(), tex.clone());
                return Some(tex);
            }
            Err(_) => {
                app.extra.thumb_missing.borrow_mut().insert(c.fid.clone());
                return None;
            }
        }
    }
    if c.thumb && !c.expired {
        files::fetch(app.state.clone(), "thumb", c.clone(), p, None);
    } else {
        app.extra.thumb_missing.borrow_mut().insert(c.fid.clone());
    }
    None
}

impl App {
    /// 첨부 말풍선 안쪽: 사진은 미리보기, 파일은 이름·크기. 누르면 열기/크게 보기
    pub(super) fn draw_file_body(&self, ui: &mut egui::Ui, c: &Clip) {
        if c.expired {
            ui.label(
                RichText::new(format!("📎 {} · 기간이 지나 삭제됐어요", c.name))
                    .color(SUB)
                    .size(13.0),
            );
            return;
        }
        let progress = self.with_state(|s| s.downloads.get(&c.fid).copied());
        if c.is_image() {
            if let Some(tex) = thumb_texture(self, ui.ctx(), c) {
                let sz = tex.size_vec2();
                let scale = (240.0 / sz.x).min(240.0 / sz.y).min(1.0);
                let img = egui::Image::new((tex.id(), sz * scale))
                    .corner_radius(8.0)
                    .sense(Sense::click());
                let r = ui.add(img).on_hover_text("누르면 크게 보기");
                if r.clicked() {
                    self.open_viewer(c);
                }
            } else {
                ui.label(
                    RichText::new(format!("🖼 {}  ({})", c.name, human_size(c.size))).size(13.5),
                );
                if ui.add(small_btn("크게 보기", false)).clicked() {
                    self.open_viewer(c);
                }
            }
        } else {
            ui.label(RichText::new(format!("📎 {}  ({})", c.name, human_size(c.size))).size(13.5));
            if let Some(p) = progress {
                ui.add(
                    egui::ProgressBar::new(p)
                        .desired_height(6.0)
                        .desired_width(160.0),
                );
            } else if ui.add(small_btn("열기", false)).clicked() {
                files::open_file(self.state.clone(), c.clone());
            }
        }
    }

    /// 첨부 우클릭 메뉴 항목
    pub(super) fn file_menu(&self, ui: &mut egui::Ui, c: &Clip) {
        if c.expired {
            return;
        }
        if ui.button("열기").clicked() {
            files::open_file(self.state.clone(), c.clone());
            ui.close();
        }
        if ui.button("다른 이름으로 저장…").clicked() {
            if let Some(target) = dialogs::save_file(&c.name) {
                files::save_as(self.state.clone(), c.clone(), target);
            }
            ui.close();
        }
    }

    fn open_viewer(&self, c: &Clip) {
        *self.extra.viewer.borrow_mut() = Some(Viewer {
            fid: c.fid.clone(),
            name: c.name.clone(),
            tex: None,
            size: [0, 0],
            zoom: 1.0,
            status: "원본을 받는 중…".into(),
        });
        let (tx, rx) = mpsc::channel();
        *self.extra.viewer_rx.borrow_mut() = Some(rx);
        let dest = files::file_path(c);
        let fid = c.fid.clone();
        let decode = move |p: PathBuf| {
            let r = image::open(&p).map(|img| {
                let mut img = img;
                // 너무 큰 사진은 화면용으로 줄임 (메모리 절약)
                if img.width() > 2400 || img.height() > 2400 {
                    img = img.thumbnail(2400, 2400);
                }
                let rgba = img.to_rgba8();
                let size = [rgba.width() as usize, rgba.height() as usize];
                (rgba.into_raw(), size)
            });
            let _ = tx.send((fid, r.map_err(|e| e.to_string())));
        };
        if dest.exists() {
            std::thread::spawn(move || decode(dest));
        } else {
            files::fetch(
                self.state.clone(),
                "file",
                c.clone(),
                dest,
                Some(Box::new(decode)),
            );
        }
    }

    /// 사진 크게 보기 창 (휠로 확대·축소)
    pub(super) fn draw_viewer(&mut self, ctx: &egui::Context) {
        // 내려받기·해독이 끝났으면 텍스처로
        let got = self
            .extra
            .viewer_rx
            .borrow()
            .as_ref()
            .and_then(|rx| rx.try_recv().ok());
        if let Some((fid, r)) = got {
            if let Some(v) = self.extra.viewer.borrow_mut().as_mut() {
                if v.fid == fid {
                    match r {
                        Ok((rgba, size)) => {
                            let ci = egui::ColorImage::from_rgba_unmultiplied(size, &rgba);
                            v.tex = Some(ctx.load_texture(
                                format!("view-{fid}"),
                                ci,
                                egui::TextureOptions::LINEAR,
                            ));
                            v.size = size;
                            v.status.clear();
                        }
                        Err(e) => v.status = format!("사진을 열 수 없어요 ({e})"),
                    }
                }
            }
        }
        let mut open = self.extra.viewer.borrow().is_some();
        if !open {
            return;
        }
        let mut close = false;
        {
            let mut vb = self.extra.viewer.borrow_mut();
            let v = vb.as_mut().unwrap();
            egui::Window::new(format!("🖼 {}", v.name))
                .open(&mut open)
                .default_size([720.0, 560.0])
                .resizable(true)
                .show(ctx, |ui| {
                    ui.horizontal(|ui| {
                        if ui.add(small_btn("−", false)).clicked() {
                            v.zoom = (v.zoom / 1.25).max(0.1);
                        }
                        ui.label(
                            RichText::new(format!("{:.0}%", v.zoom * 100.0))
                                .color(SUB)
                                .size(12.0),
                        );
                        if ui.add(small_btn("+", false)).clicked() {
                            v.zoom = (v.zoom * 1.25).min(8.0);
                        }
                        if ui.add(small_btn("맞춤", false)).clicked() {
                            v.zoom = 0.0; // 0 = 창에 맞춤
                        }
                        if ui.add(small_btn("닫기", false)).clicked() {
                            close = true;
                        }
                        if !v.status.is_empty() {
                            ui.label(RichText::new(&v.status).color(SUB).size(12.0));
                        }
                    });
                    if let Some(tex) = &v.tex {
                        let avail = ui.available_size();
                        let sz = tex.size_vec2();
                        let fit = (avail.x / sz.x).min(avail.y / sz.y).min(1.0);
                        let zoom = if v.zoom <= 0.0 { fit } else { v.zoom };
                        let scroll = ui.input(|i| i.smooth_scroll_delta.y);
                        if scroll != 0.0 && ui.input(|i| i.modifiers.ctrl) {
                            v.zoom = (zoom * (1.0 + scroll / 300.0)).clamp(0.1, 8.0);
                        }
                        egui::ScrollArea::both()
                            .auto_shrink([false; 2])
                            .show(ui, |ui| {
                                ui.centered_and_justified(|ui| {
                                    ui.add(egui::Image::new((tex.id(), sz * zoom)));
                                });
                            });
                    } else {
                        ui.centered_and_justified(|ui| ui.spinner());
                    }
                });
        }
        if !open || close {
            *self.extra.viewer.borrow_mut() = None;
            *self.extra.viewer_rx.borrow_mut() = None;
        }
    }

    /// [+] 메뉴: 사진·파일 보내기 / 예약 메시지 / 클립보드 사진 붙여넣기
    pub(super) fn draw_plus_menu(&mut self, _ui: &mut egui::Ui, resp: &egui::Response) {
        let mut pick = false;
        let mut paste = false;
        let mut sched = false;
        egui::Popup::menu(resp)
            .close_behavior(egui::PopupCloseBehavior::CloseOnClick)
            .show(|ui| {
                ui.set_min_width(200.0);
                if ui.button("🖼 사진·파일 보내기…").clicked() {
                    pick = true;
                }
                if ui.button("📋 클립보드 사진 붙여넣기 (Ctrl+V)").clicked() {
                    paste = true;
                }
                if ui.button("⏰ 예약 메시지…").clicked() {
                    sched = true;
                }
            });
        if pick {
            for p in dialogs::pick_files() {
                files::send_file(self.state.clone(), p, None, None);
            }
        }
        if paste {
            self.paste_clipboard();
        }
        if sched {
            self.open_schedule();
        }
    }

    /// 끌어다 놓은 파일 → 보내기, Ctrl+V → 클립보드 사진 보내기
    pub(super) fn handle_drop_and_paste(&mut self, ctx: &egui::Context) {
        let dropped: Vec<PathBuf> = ctx.input(|i| {
            i.raw
                .dropped_files
                .iter()
                .filter_map(|f| f.path.clone())
                .collect()
        });
        for p in dropped {
            files::send_file(self.state.clone(), p, None, None);
        }
        if ctx.input(|i| !i.raw.hovered_files.is_empty()) {
            egui::Area::new(egui::Id::new("drop-hint"))
                .fixed_pos(egui::pos2(16.0, 16.0))
                .show(ctx, |ui| {
                    Frame::NONE
                        .fill(AMBER)
                        .corner_radius(CornerRadius::same(10))
                        .inner_margin(Margin::symmetric(14, 8))
                        .show(ui, |ui| {
                            ui.label(
                                RichText::new("여기 놓으면 지금 고른 방으로 보내요")
                                    .color(ON_DARK)
                                    .strong(),
                            );
                        });
                });
        }
        // Ctrl+V: 입력칸이 글을 받는 중이 아니면(또는 사진이 클립보드에 있으면) 사진 보내기
        let paste = ctx.input(|i| i.modifiers.ctrl && i.key_pressed(egui::Key::V));
        if paste && dialogs::clipboard_has_image() {
            self.paste_clipboard();
        }
    }

    fn paste_clipboard(&mut self) {
        match dialogs::clipboard_image_png() {
            Some(png) => {
                let dir = std::env::temp_dir().join("woorizip-paste");
                let _ = std::fs::create_dir_all(&dir);
                let name = format!(
                    "스크린샷_{}.png",
                    chrono::Local::now().format("%m%d_%H%M%S")
                );
                let p = dir.join(&name);
                if std::fs::write(&p, png).is_ok() {
                    files::send_file(self.state.clone(), p, None, Some(name));
                }
            }
            None => self.with_state(|s| s.notice = "클립보드에 사진이 없어요".into()),
        }
    }

    // ---- 예약 메시지 ----

    pub(super) fn open_schedule(&mut self) {
        let d = &mut self.extra.sched;
        d.open = true;
        d.err.clear();
        let t = chrono::Local::now() + chrono::Duration::minutes(10);
        d.date = t.format("%Y-%m-%d").to_string();
        d.time = t.format("%H:%M").to_string();
    }

    pub(super) fn draw_schedule_dialog(&mut self, ctx: &egui::Context) {
        if !self.extra.sched.open {
            return;
        }
        let room = self.with_state(|s| s.current_room());
        let (mine, me): (Vec<Schedule>, String) = self.with_state(|s| {
            (
                s.schedules
                    .iter()
                    .filter(|x| x.from.is_empty() || x.from == s.cfg.name)
                    .cloned()
                    .collect(),
                s.cfg.name.clone(),
            )
        });
        let _ = me;
        let mut open = true;
        let mut add: Option<(String, i64, String)> = None;
        let mut del: Option<i64> = None;
        {
            let d = &mut self.extra.sched;
            egui::Window::new("⏰ 예약 메시지")
                .open(&mut open)
                .resizable(false)
                .default_width(360.0)
                .show(ctx, |ui| {
                    ui.label(
                        RichText::new(format!(
                            "받는 사람: {} {}",
                            room.icon,
                            if room.key == "all" {
                                "모두"
                            } else {
                                &room.label
                            }
                        ))
                        .color(rgb(room.color))
                        .strong(),
                    );
                    ui.add(
                        egui::TextEdit::singleline(&mut d.text)
                            .hint_text("보낼 글")
                            .desired_width(f32::INFINITY),
                    );
                    ui.horizontal(|ui| {
                        ui.label("날짜");
                        ui.add(egui::TextEdit::singleline(&mut d.date).desired_width(100.0));
                        ui.label("시간");
                        ui.add(egui::TextEdit::singleline(&mut d.time).desired_width(60.0));
                    });
                    ui.horizontal(|ui| {
                        for (mins, label) in [(10, "+10분"), (30, "+30분"), (60, "+1시간")] {
                            if ui.add(small_btn(label, false)).clicked() {
                                let t = chrono::Local::now() + chrono::Duration::minutes(mins);
                                d.date = t.format("%Y-%m-%d").to_string();
                                d.time = t.format("%H:%M").to_string();
                            }
                        }
                        for (h, label) in [(8, "오늘 8:00"), (18, "오늘 18:00"), (21, "오늘 21:00")]
                        {
                            if ui.add(small_btn(label, false)).clicked() {
                                d.date = chrono::Local::now().format("%Y-%m-%d").to_string();
                                d.time = format!("{h:02}:00");
                            }
                        }
                    });
                    ui.horizontal(|ui| {
                        for (v, label) in [
                            ("once", "한 번"),
                            ("daily", "매일"),
                            ("weekdays", "평일"),
                            ("weekends", "주말"),
                        ] {
                            ui.radio_value(&mut d.repeat, v.to_string(), label);
                        }
                    });
                    if !d.err.is_empty() {
                        ui.label(RichText::new(&d.err).color(RED).size(12.0));
                    }
                    if ui
                        .add(
                            egui::Button::new(RichText::new("예약 추가").color(ON_DARK).strong())
                                .fill(AMBER)
                                .corner_radius(CornerRadius::same(10)),
                        )
                        .clicked()
                    {
                        match parse_when(&d.date, &d.time) {
                            Some(at) if at > chrono::Local::now().timestamp() => {
                                if d.text.trim().is_empty() {
                                    d.err = "보낼 글을 적어 주세요".into();
                                } else {
                                    add = Some((d.text.trim().to_string(), at, d.repeat.clone()));
                                }
                            }
                            Some(_) => d.err = "지난 시간이에요".into(),
                            None => {
                                d.err = "날짜는 2026-11-07, 시간은 18:30 처럼 적어 주세요".into()
                            }
                        }
                    }
                    ui.separator();
                    ui.label(RichText::new("내 예약 목록").color(SUB).size(12.0));
                    if mine.is_empty() {
                        ui.label(RichText::new("예약이 없어요").color(SUB).size(12.0));
                    }
                    for sc in &mine {
                        ui.horizontal(|ui| {
                            ui.label(
                                RichText::new(format!("{} · {}", sched_when(sc), sc.text))
                                    .size(13.0),
                            );
                            if ui.add(small_btn("삭제", false)).clicked() {
                                del = Some(sc.sid);
                            }
                        });
                    }
                });
        }
        if let Some((text, at, repeat)) = add {
            self.with_state(|s| s.schedule_add(&text, at, &repeat, None));
            self.extra.sched.text.clear();
            self.extra.sched.err = "예약했어요 (NAS가 시간이 되면 대신 보내요)".into();
        }
        if let Some(sid) = del {
            self.with_state(|s| s.schedule_delete(sid));
        }
        if !open {
            self.extra.sched.open = false;
        }
    }

    /// 대화 칸 아래: 이 방으로 갈 예약 글 (보낼 예정)
    pub(super) fn draw_pending_schedules(&self, ui: &mut egui::Ui, room: &Room) {
        let list: Vec<Schedule> = self.with_state(|s| {
            s.schedules
                .iter()
                .filter(|sc| sched_in_room(sc, room))
                .cloned()
                .collect()
        });
        for sc in list {
            ui.with_layout(Layout::right_to_left(Align::Min), |ui| {
                Frame::NONE
                    .fill(blend(AMBER, CARD, 0.15))
                    .stroke(Stroke::new(1.0_f32, blend(AMBER, CARD, 0.5)))
                    .corner_radius(CornerRadius::same(12))
                    .inner_margin(Margin::symmetric(12, 8))
                    .show(ui, |ui| {
                        ui.vertical(|ui| {
                            ui.label(RichText::new(&sc.text).size(13.5));
                            let to = self.with_state(|s| match &sc.to {
                                serde_json::Value::Array(a) => a
                                    .iter()
                                    .filter_map(|x| x.as_str())
                                    .map(|n| s.disp(n).to_string())
                                    .collect::<Vec<_>>()
                                    .join(", "),
                                _ => String::new(),
                            });
                            ui.label(
                                RichText::new(format!(
                                    "⏰ {} 보낼 예정{}",
                                    sched_when(&sc),
                                    if room.key == "all" && !to.is_empty() {
                                        format!(" · {to}에게")
                                    } else {
                                        String::new()
                                    }
                                ))
                                .color(AMBER)
                                .size(10.5),
                            );
                        });
                    });
            });
        }
    }

    // ---- 설정 ----

    pub(super) fn open_settings(&mut self) {
        let d = &mut self.extra.settings;
        let s = self.state.lock().unwrap();
        d.open = true;
        d.unlocked = false;
        d.pw.clear();
        d.msg.clear();
        d.name = s.cfg.name.clone();
        d.host = s.cfg.host.clone();
        d.out_host = s.cfg.out_host.clone();
        d.key = s.cfg.key.clone();
        d.hotkey = s.cfg.hotkey.clone();
        d.quick = s.cfg.quick.clone();
        d.groups = s.cfg.groups.clone();
        d.autostart = crate::platform::autostart_enabled();
    }

    pub(super) fn draw_settings(&mut self, ctx: &egui::Context) {
        if !self.extra.settings.open {
            return;
        }
        if let Some(m) = self.with_state(|s| s.settings_msg.take()) {
            self.extra.settings.msg = m;
        }
        let members = self.with_state(|s| s.members.clone());
        let real_key = self.with_state(|s| s.cfg.key.clone());
        let mut open = true;
        let mut save = false;
        let mut check_update = false;
        let mut make_shortcut_bg = false;
        {
            let d = &mut self.extra.settings;
            egui::Window::new("⚙ 설정")
                .open(&mut open)
                .resizable(false)
                .default_width(380.0)
                .show(ctx, |ui| {
                    egui::Grid::new("settings-grid")
                        .num_columns(2)
                        .spacing([10.0, 8.0])
                        .show(ui, |ui| {
                            ui.label("단축키");
                            ui.add(
                                egui::TextEdit::singleline(&mut d.hotkey)
                                    .hint_text("예: f9, ctrl+f9, scroll lock (비우면 없음)")
                                    .desired_width(240.0),
                            );
                            ui.end_row();
                            ui.label("빠른 답장");
                            ui.add(
                                egui::TextEdit::singleline(&mut d.quick)
                                    .hint_text("쉼표로 구분")
                                    .desired_width(240.0),
                            );
                            ui.end_row();
                            ui.label("묶음");
                            ui.add(
                                egui::TextEdit::singleline(&mut d.groups)
                                    .hint_text("아이들=첫째,둘째,셋째")
                                    .desired_width(240.0),
                            );
                            ui.end_row();
                            ui.label("자동 실행");
                            ui.checkbox(&mut d.autostart, "Windows 시작할 때 켜기");
                            ui.end_row();
                        });
                    ui.separator();
                    ui.label(
                        RichText::new("사용자·주소 변경 (가족 암호 필요)")
                            .color(SUB)
                            .size(12.0),
                    );
                    if !d.unlocked {
                        ui.horizontal(|ui| {
                            ui.add(
                                egui::TextEdit::singleline(&mut d.pw)
                                    .password(true)
                                    .hint_text("가족 암호")
                                    .desired_width(140.0),
                            );
                            if ui.button("열기").clicked() {
                                if d.pw.trim() == real_key {
                                    d.unlocked = true;
                                } else {
                                    d.msg = "암호가 달라요".into();
                                }
                                d.pw.clear();
                            }
                        });
                    } else {
                        egui::Grid::new("settings-grid2")
                            .num_columns(2)
                            .spacing([10.0, 8.0])
                            .show(ui, |ui| {
                                ui.label("이 PC 사용자");
                                egui::ComboBox::from_id_salt("name")
                                    .selected_text(&d.name)
                                    .show_ui(ui, |ui| {
                                        for m in &members {
                                            ui.selectable_value(&mut d.name, m.clone(), m);
                                        }
                                    });
                                ui.end_row();
                                ui.label("집 주소");
                                ui.add(
                                    egui::TextEdit::singleline(&mut d.host).desired_width(240.0),
                                );
                                ui.end_row();
                                ui.label("밖 주소");
                                ui.add(
                                    egui::TextEdit::singleline(&mut d.out_host)
                                        .hint_text("Tailscale 주소 (없으면 비움)")
                                        .desired_width(240.0),
                                );
                                ui.end_row();
                                ui.label("가족 암호");
                                ui.add(
                                    egui::TextEdit::singleline(&mut d.key)
                                        .password(true)
                                        .desired_width(240.0),
                                );
                                ui.end_row();
                            });
                    }
                    if !d.msg.is_empty() {
                        ui.label(
                            RichText::new(&d.msg)
                                .color(if d.msg.contains("달라") { RED } else { GREEN })
                                .size(12.0),
                        );
                    }
                    ui.horizontal(|ui| {
                        if ui
                            .add(
                                egui::Button::new(RichText::new("저장").color(ON_DARK).strong())
                                    .fill(AMBER)
                                    .corner_radius(CornerRadius::same(10)),
                            )
                            .clicked()
                        {
                            save = true;
                        }
                        ui.label(
                            RichText::new("암호는 이 Windows 계정에서만 풀 수 있게 잠가 저장해요")
                                .color(SUB)
                                .size(11.0),
                        );
                    });
                    ui.separator();
                    ui.horizontal(|ui| {
                        if ui
                            .add(small_btn("바탕화면에 바로가기 만들기", false))
                            .clicked()
                        {
                            // PowerShell 이 2~3초 걸리므로 뒤 스레드에서 — 창이 멈추지 않게
                            d.msg = "바로가기를 만드는 중…".into();
                            make_shortcut_bg = true;
                        }
                        if ui.add(small_btn("지금 업데이트 확인", false)).clicked() {
                            check_update = true;
                        }
                        ui.label(
                            RichText::new(format!("버전 {}", crate::update::version()))
                                .color(SUB)
                                .size(11.0),
                        );
                    });
                });
        }
        if save {
            self.apply_settings();
        }
        if make_shortcut_bg {
            let st = self.state.clone();
            std::thread::spawn(move || {
                let msg =
                    match crate::platform::make_shortcut(crate::platform::ShortcutWhere::Desktop) {
                        Ok(()) => "바탕화면에 '우리집' 바로가기를 만들었어요".to_string(),
                        Err(e) => format!("바로가기를 만들지 못했어요 ({e})"),
                    };
                let mut s = st.lock().unwrap();
                s.notice = msg.clone();
                s.settings_msg = Some(msg);
            });
        }
        if check_update {
            let st = self.state.clone();
            std::thread::spawn(move || {
                let msg = match crate::update::check(&st) {
                    Ok(Some(r)) => {
                        st.lock().unwrap().update_ready = Some((r.path, r.number));
                        format!(
                            "새 버전 v{}을 받아 뒀어요 — 창을 닫아 두면 조용히 바뀌어요",
                            r.number
                        )
                    }
                    Ok(None) => "지금이 최신이에요".into(),
                    Err(e) => format!("확인 실패: {e}"),
                };
                let mut s = st.lock().unwrap();
                s.notice = msg.clone();
                s.settings_msg = Some(msg);
            });
            self.extra.settings.msg = "확인하는 중…".into();
        }
        if !open {
            self.extra.settings.open = false;
        }
    }

    fn apply_settings(&mut self) {
        let d = &self.extra.settings;
        let (changed_conn, hotkey) = {
            let mut s = self.state.lock().unwrap();
            let before = (
                s.cfg.name.clone(),
                s.cfg.host.clone(),
                s.cfg.out_host.clone(),
                s.cfg.key.clone(),
            );
            s.cfg.hotkey = d.hotkey.trim().to_string();
            s.cfg.quick = d.quick.trim().to_string();
            s.cfg.groups = d.groups.trim().to_string();
            s.cfg.autostart = d.autostart;
            if d.unlocked {
                if !d.name.trim().is_empty() {
                    s.cfg.name = d.name.trim().to_string();
                }
                s.cfg.host = d.host.trim().to_string();
                s.cfg.out_host = d.out_host.trim().to_string();
                if !d.key.trim().is_empty() {
                    s.cfg.key = d.key.trim().to_string();
                }
            }
            s.quick_replies = s
                .cfg
                .quick
                .split(',')
                .map(|q| q.trim().to_string())
                .filter(|q| !q.is_empty())
                .collect();
            if let Err(e) = s.cfg.save() {
                crate::log::info(&format!("설정 저장 실패: {e}"));
            }
            let after = (
                s.cfg.name.clone(),
                s.cfg.host.clone(),
                s.cfg.out_host.clone(),
                s.cfg.key.clone(),
            );
            (before != after, s.cfg.hotkey.clone())
        };
        let _ = self.inputs.hotkey_tx.send(hotkey);
        if let Err(e) = crate::platform::set_autostart(d.autostart) {
            crate::log::info(&format!("자동 실행 설정 실패: {e}"));
        }
        if changed_conn {
            let cfg = self.state.lock().unwrap().cfg.clone();
            (self.reconfigure)(cfg);
        }
        self.extra.settings.msg = "저장했어요".into();
    }

    /// 처음 한 번: "이 PC는 누구 것인가요?"
    pub(super) fn draw_setup(&mut self, ctx: &egui::Context) {
        if !self.extra.setup_open {
            return;
        }
        let members = self.with_state(|s| s.members.clone());
        let mut chosen: Option<String> = None;
        egui::Modal::new(egui::Id::new("setup")).show(ctx, |ui| {
            ui.set_width(320.0);
            ui.label(RichText::new("이 PC는 누구 것인가요?").strong().size(17.0));
            ui.label(
                RichText::new(
                    "우리 집 NAS 주소와 가족 암호는 이미 들어 있어요. 이름만 고르면 돼요.",
                )
                .color(SUB)
                .size(12.0),
            );
            ui.add_space(6.0);
            for m in &members {
                let (ic, col) = member_style(m);
                if ui
                    .add(
                        egui::Button::new(
                            RichText::new(format!("{ic} {m}")).size(15.0).color(ON_DARK),
                        )
                        .fill(rgb(col))
                        .corner_radius(CornerRadius::same(12))
                        .min_size(Vec2::new(300.0, 40.0)),
                    )
                    .clicked()
                {
                    chosen = Some(m.clone());
                }
            }
        });
        if let Some(name) = chosen {
            let cfg = {
                let mut s = self.state.lock().unwrap();
                s.cfg.name = name;
                if let Err(e) = s.cfg.save() {
                    crate::log::info(&format!("설정 저장 실패: {e}"));
                }
                s.cfg.clone()
            };
            if cfg.autostart {
                if let Err(e) = crate::platform::set_autostart(true) {
                    crate::log::info(&format!("자동 실행 설정 실패: {e}"));
                }
            }
            (self.reconfigure)(cfg);
            self.extra.setup_open = false;
        }
    }
}

fn parse_when(date: &str, time: &str) -> Option<i64> {
    use chrono::{Local, NaiveDate, NaiveTime, TimeZone};
    let d = NaiveDate::parse_from_str(date.trim(), "%Y-%m-%d").ok()?;
    let t = NaiveTime::parse_from_str(time.trim(), "%H:%M").ok()?;
    Local
        .from_local_datetime(&d.and_time(t))
        .single()
        .map(|x| x.timestamp())
}

pub fn sched_in_room(sc: &Schedule, room: &Room) -> bool {
    let Some(members) = &room.members else {
        return true;
    };
    let names: Vec<String> = match &sc.to {
        serde_json::Value::Array(a) => a
            .iter()
            .filter_map(|x| x.as_str().map(String::from))
            .collect(),
        serde_json::Value::String(s) if s != "all" => {
            s.split(',').map(|x| x.trim().to_string()).collect()
        }
        _ => return false,
    };
    names.iter().collect::<HashSet<_>>() == members.iter().collect::<HashSet<_>>()
}

pub fn sched_when(sc: &Schedule) -> String {
    use chrono::{Datelike, Local, TimeZone};
    let d = Local
        .timestamp_opt(sc.at, 0)
        .single()
        .unwrap_or_else(Local::now);
    let today = Local::now();
    let tomorrow = today + chrono::Duration::days(1);
    let day = if d.date_naive() == today.date_naive() {
        "오늘".to_string()
    } else if d.date_naive() == tomorrow.date_naive() {
        "내일".to_string()
    } else {
        format!("{}/{}", d.month(), d.day())
    };
    let rep = match sc.repeat.as_str() {
        "daily" => " (매일)",
        "weekdays" => " (평일)",
        "weekends" => " (주말)",
        _ => "",
    };
    format!("{day} {}{rep}", d.format("%H:%M"))
}

/// 운영체제 대화 상자·클립보드 (Windows 에서만 실제로 동작)
pub mod dialogs {
    use std::path::PathBuf;

    #[cfg(windows)]
    pub fn pick_files() -> Vec<PathBuf> {
        rfd::FileDialog::new()
            .set_title("보낼 사진·파일 고르기")
            .pick_files()
            .unwrap_or_default()
    }
    #[cfg(windows)]
    pub fn save_file(name: &str) -> Option<PathBuf> {
        rfd::FileDialog::new().set_file_name(name).save_file()
    }
    #[cfg(windows)]
    pub fn clipboard_has_image() -> bool {
        arboard::Clipboard::new()
            .and_then(|mut c| c.get_image())
            .is_ok()
    }
    #[cfg(windows)]
    pub fn clipboard_image_png() -> Option<Vec<u8>> {
        let img = arboard::Clipboard::new().ok()?.get_image().ok()?;
        let buf = image::RgbaImage::from_raw(
            img.width as u32,
            img.height as u32,
            img.bytes.into_owned(),
        )?;
        let mut out = std::io::Cursor::new(Vec::new());
        image::DynamicImage::ImageRgba8(buf)
            .write_to(&mut out, image::ImageFormat::Png)
            .ok()?;
        Some(out.into_inner())
    }

    #[cfg(not(windows))]
    pub fn pick_files() -> Vec<PathBuf> {
        vec![]
    }
    #[cfg(not(windows))]
    pub fn save_file(_name: &str) -> Option<PathBuf> {
        None
    }
    #[cfg(not(windows))]
    pub fn clipboard_has_image() -> bool {
        false
    }
    #[cfg(not(windows))]
    pub fn clipboard_image_png() -> Option<Vec<u8>> {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn schedule_room_match_and_when() {
        let room = Room {
            key: "엄마".into(),
            label: "진희".into(),
            members: Some(vec!["엄마".into()]),
            icon: "👩",
            color: [0; 3],
        };
        let sc = Schedule {
            sid: 1,
            to: serde_json::json!(["엄마"]),
            text: "x".into(),
            at: chrono::Local::now().timestamp() + 600,
            repeat: "daily".into(),
            from: String::new(),
        };
        assert!(sched_in_room(&sc, &room));
        let all = Room {
            key: "all".into(),
            label: "모두".into(),
            members: None,
            icon: "🏠",
            color: [0; 3],
        };
        assert!(sched_in_room(&sc, &all));
        let sc_all = Schedule {
            to: serde_json::json!("all"),
            ..sc.clone()
        };
        assert!(!sched_in_room(&sc_all, &room));
        assert!(sched_when(&sc).starts_with("오늘") || sched_when(&sc).starts_with("내일"));
        assert!(sched_when(&sc).ends_with("(매일)"));
        assert!(parse_when("2026-11-07", "18:30").is_some());
        assert!(parse_when("2026-13-07", "18:30").is_none());
    }
}
