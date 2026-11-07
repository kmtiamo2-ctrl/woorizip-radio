//! 무전기 상태 — Python판 `Radio` 클래스에 해당. 화면(egui)과 통신(net)이 함께 보는 자료.
//! 서버가 주는 메시지를 적용하고, 대화방·말풍선 묶음·읽음 표시 같은 화면용 계산을 함.
use crate::config::Config;
use crate::proto::Welcome;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};

/// 가족별 고정 아이콘·색 (방 버튼, 말하기 버튼, 받은 메시지 이름에 똑같이 씀)
pub fn member_style(name: &str) -> (&'static str, [u8; 3]) {
    match name {
        "엄마" => ("👩", [0xF4, 0x72, 0xB6]),
        "아빠" => ("👨", [0x60, 0xA5, 0xFA]),
        "첫째" => ("👦", [0x34, 0xD3, 0x99]),
        "둘째" => ("👦", [0xFB, 0x92, 0x3C]),
        "셋째" => ("👧", [0xA7, 0x8B, 0xFA]),
        _ => ("😀", [0x9A, 0xA6, 0xBC]),
    }
}
pub fn group_style(name: &str) -> (&'static str, [u8; 3]) {
    match name {
        "아이들" => ("👧", [0x2D, 0xD4, 0xBF]),
        "부모님" => ("👫", [0x81, 0x8C, 0xF8]),
        _ => ("👪", [0x9A, 0xA6, 0xBC]),
    }
}
pub const ALL_STYLE: (&str, [u8; 3]) = ("🏠", [0xF5, 0xB3, 0x01]);
pub const GREY: [u8; 3] = [0x9A, 0xA6, 0xBC];
pub const DEFAULT_MEMBERS: [&str; 5] = ["엄마", "아빠", "첫째", "둘째", "셋째"];
/// 기기에 두는 최근 글 수 (서버가 전부 보관하므로 여기는 화면용). 위로 올리면 서버에서 더 받아 옴
pub const MAX_CLIPS: usize = 3000;

/// `'아이들=첫째,둘째;부모님=엄마,아빠'` → [(이름, [멤버...]), ...]
pub fn parse_groups(text: &str) -> Vec<(String, Vec<String>)> {
    text.split(';')
        .filter_map(|part| {
            let (name, mem) = part.split_once('=')?;
            let members: Vec<String> = mem
                .split(',')
                .map(|m| m.trim().to_string())
                .filter(|m| !m.is_empty())
                .collect();
            if name.trim().is_empty() || members.is_empty() {
                None
            } else {
                Some((name.trim().to_string(), members))
            }
        })
        .collect()
}

/// 받침에 따라 조사 고르기: josa("규민", "과", "와") → "과", josa("로아", …) → "와"
pub fn josa<'a>(word: &str, with_batchim: &'a str, without: &'a str) -> &'a str {
    match word.chars().last() {
        Some(c) if ('가'..='힣').contains(&c) => {
            if (c as u32 - '가' as u32).is_multiple_of(28) {
                without
            } else {
                with_batchim
            }
        }
        _ => without,
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum Kind {
    Text,
    VoiceSent,
    File,
    Clip,
}

/// 대화 한 줄: 문자, 내가 보낸 음성, 첨부, (받은 음성은 3일차)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Clip {
    pub kind: Kind,
    pub id: i64,
    pub mid: i64,
    pub ts: i64,
    /// 받은 글이면 보낸 사람, 내가 보낸 글이면 받는 사람(들, ", " 로 이어짐) 또는 모두일 때 내 이름
    pub from: String,
    pub mode: String, // all | direct | group
    #[serde(default)]
    pub text: String,
    #[serde(default)]
    pub mine: bool,
    #[serde(default)]
    pub seconds: f64,
    // 첨부
    #[serde(default)]
    pub fid: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub size: i64,
    #[serde(default)]
    pub mime: String,
    #[serde(default)]
    pub thumb: bool,
    #[serde(default)]
    pub expired: bool,
    /// 받은 음성(kind=Clip)의 소리 파일 경로 (Python판과 같은 `clips/` 폴더·이름 규칙)
    #[serde(default)]
    pub file: String,
}

impl Clip {
    pub fn is_image(&self) -> bool {
        self.kind == Kind::File
            && (self.mime.starts_with("image/") || {
                let n = self.name.to_lowercase();
                [".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp"]
                    .iter()
                    .any(|e| n.ends_with(e))
            })
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Room {
    pub key: String,
    pub label: String,
    pub members: Option<Vec<String>>, // None = 모두
    pub icon: &'static str,
    pub color: [u8; 3],
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Schedule {
    pub sid: i64,
    #[serde(default)]
    pub to: Value,
    #[serde(default)]
    pub text: String,
    #[serde(default)]
    pub at: i64,
    #[serde(default)]
    pub repeat: String,
    #[serde(default)]
    pub from: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct OutboxItem {
    to: Value,
    text: String,
    ts: i64,
    from: String,
}

/// 화면에 알릴 일 (팝업·깜빡임 등)
#[derive(Debug, Clone)]
pub enum Notice {
    Incoming {
        from: String,
        mode: String,
    },
    Text(Clip),
    FileIn(Clip),
    /// 받은 음성. auto=집(또는 부모)이라 바로 틀었음
    Clip {
        clip: Clip,
        auto: bool,
    },
    Schedules,
}

/// 소리 장치에 시킬 일 (engine 이 비움)
#[derive(Debug, Clone)]
pub enum Sound {
    Live(Vec<u8>),
    BeepIn,
    BeepOk,
    BeepNo,
    PlayFile(String),
    Stop,
}

pub struct State {
    pub cfg: Config,
    pub connected: bool,
    pub where_: Option<String>,
    pub status: String,
    pub members: Vec<String>,
    pub parents: HashSet<String>,
    pub names: HashMap<String, String>,
    pub online: Vec<String>,
    pub member_where: HashMap<String, String>,
    pub talking: bool,
    pub requesting: bool,
    pub incoming_from: Option<String>,
    pub incoming_mode: Option<String>,
    pub notice: String,
    pub clips: Vec<Clip>, // 새것이 앞
    pub receipts: HashMap<i64, HashMap<String, String>>,
    pub seen_sent: HashSet<i64>,
    pub schedules: Vec<Schedule>,
    pub history_more: bool,
    pub loading_older: bool,
    pub quick_replies: Vec<String>,
    outbox: Vec<OutboxItem>,
    /// 서버로 보낼 것 (net 이 비움)
    pub outgoing: Vec<Value>,
    /// 화면에 알릴 것 (app 이 비움)
    pub notices: Vec<Notice>,
    /// 소리 장치에 시킬 것 (engine 이 비움)
    pub sounds: Vec<Sound>,
    /// 마이크를 켜 둬야 하는지 (서버가 말하기를 허락한 동안)
    pub mic_wanted: bool,
    /// 지금 틀고 있는 보관 음성 (id, 끝나는 시각)
    pub playing: Option<(i64, i64)>,
    /// 서버가 "곧 소리 파일 보낼게" 라고 알린 것 (id → 메타)
    pending: HashMap<i64, Value>,
    /// 보내는 중인 첨부: 경로 → 진행 상황
    pub uploads: HashMap<String, crate::files::Upload>,
    /// 내려받는 중인 (종류, fid)
    pub fetching: crate::files::Fetching,
    /// 원본 내려받기 진행률 fid → 0~1
    pub downloads: HashMap<String, f32>,
    /// 받아 둔 새 버전 (경로, 번호) — 창이 한가할 때 적용
    pub update_ready: Option<(std::path::PathBuf, u64)>,
    pub update_applied: bool,
    data_dir: std::path::PathBuf,
    persist: bool, // 시험 때는 설정 파일을 건드리지 않음
}

impl State {
    pub fn new(cfg: Config) -> State {
        let dir = crate::config::data_dir();
        let mut s = State {
            quick_replies: cfg
                .quick
                .split(',')
                .map(|q| q.trim().to_string())
                .filter(|q| !q.is_empty())
                .collect(),
            cfg,
            connected: false,
            where_: None,
            status: "연결 준비 중".into(),
            members: DEFAULT_MEMBERS.iter().map(|s| s.to_string()).collect(),
            parents: ["엄마", "아빠"].iter().map(|s| s.to_string()).collect(),
            names: HashMap::new(),
            online: vec![],
            member_where: HashMap::new(),
            talking: false,
            requesting: false,
            incoming_from: None,
            incoming_mode: None,
            notice: String::new(),
            clips: vec![],
            receipts: HashMap::new(),
            seen_sent: HashSet::new(),
            schedules: vec![],
            history_more: false,
            loading_older: false,
            outbox: vec![],
            outgoing: vec![],
            notices: vec![],
            sounds: vec![],
            mic_wanted: false,
            playing: None,
            pending: HashMap::new(),
            uploads: HashMap::new(),
            fetching: HashSet::new(),
            downloads: HashMap::new(),
            update_ready: None,
            update_applied: false,
            data_dir: dir,
            persist: true,
        };
        s.load_texts();
        s.load_clip_files();
        s.load_outbox();
        s.load_names();
        s
    }

    #[cfg(test)]
    pub fn for_test(name: &str) -> State {
        let cfg = Config {
            name: name.into(),
            ..Config::default()
        };
        let dir = std::env::temp_dir().join(format!(
            "woorizip-test-{}-{}",
            std::process::id(),
            chrono::Local::now().timestamp_nanos_opt().unwrap_or(0)
        ));
        let _ = std::fs::create_dir_all(&dir);
        let mut s = State {
            data_dir: dir.clone(),
            persist: false,
            ..State::new(cfg)
        };
        s.clips.clear();
        s.outbox.clear();
        s.names.clear();
        s
    }

    // ---- 이름 ----

    pub fn me(&self) -> &str {
        &self.cfg.name
    }
    pub fn is_parent(&self) -> bool {
        self.parents.contains(&self.cfg.name)
    }

    /// 화면에 보일 이름: 나 자신은 '나', 나머지는 서버 이름표가 있으면 그 이름
    pub fn disp<'a>(&'a self, name: &'a str) -> &'a str {
        if name == self.cfg.name && !name.is_empty() {
            return "나";
        }
        self.names.get(name).map(|s| s.as_str()).unwrap_or(name)
    }
    /// "엄마, 첫째" 처럼 쉼표로 이어진 이름들도 변환
    pub fn disp_list(&self, s: &str) -> String {
        s.split(", ")
            .filter(|x| !x.is_empty())
            .map(|x| self.disp(x).to_string())
            .collect::<Vec<_>>()
            .join(", ")
    }

    // ---- 상대 고르기 ----

    pub fn target(&self) -> &str {
        &self.cfg.target
    }
    pub fn target_list(&self) -> Vec<String> {
        if self.cfg.target == "all" {
            vec![]
        } else {
            self.cfg
                .target
                .split(',')
                .filter(|t| !t.is_empty())
                .map(String::from)
                .collect()
        }
    }
    pub fn target_json(&self) -> Value {
        if self.cfg.target == "all" {
            json!("all")
        } else {
            json!(self.target_list())
        }
    }
    pub fn target_label(&self) -> String {
        if self.cfg.target == "all" {
            "모두".into()
        } else {
            self.target_list()
                .iter()
                .map(|t| self.disp(t).to_string())
                .collect::<Vec<_>>()
                .join(", ")
        }
    }
    fn set_target(&mut self, t: String) {
        if self.cfg.target != t {
            self.cfg.target = t;
            if self.persist {
                let _ = self.cfg.save();
            }
        }
    }

    /// 대화방: 모두 · 아이들(묶음) · 아빠 · 엄마 · 첫째 · 둘째 · 셋째 순서. 내 자리는 '나'(메모용). '부모님'은 뺌
    pub fn rooms(&self) -> Vec<Room> {
        let me = self.cfg.name.clone();
        let others: Vec<String> = self.members.iter().filter(|m| **m != me).cloned().collect();
        let mut out = vec![Room {
            key: "all".into(),
            label: "모두".into(),
            members: None,
            icon: ALL_STYLE.0,
            color: ALL_STYLE.1,
        }];
        for (name, mem) in parse_groups(&self.cfg.groups) {
            if name == "부모님" {
                continue;
            }
            let m: Vec<String> = mem.into_iter().filter(|x| others.contains(x)).collect();
            if m.len() >= 2 {
                let (ic, col) = group_style(&name);
                out.push(Room {
                    key: format!("g:{name}"),
                    label: name,
                    members: Some(m),
                    icon: ic,
                    color: col,
                });
            }
        }
        for n in ["아빠", "엄마", "첫째", "둘째", "셋째"] {
            if !self.members.iter().any(|m| m == n) {
                continue;
            }
            let (ic, col) = member_style(n);
            if n == me {
                out.push(Room {
                    key: n.into(),
                    label: "나".into(),
                    members: Some(vec![n.into()]),
                    icon: "📝",
                    color: col,
                });
            } else {
                out.push(Room {
                    key: n.into(),
                    label: self.disp(n).to_string(),
                    members: Some(vec![n.into()]),
                    icon: ic,
                    color: col,
                });
            }
        }
        out
    }

    /// 격자 배치: [모두][아이들] / [아빠][엄마] / [첫째][둘째][셋째] (+ 직접 묶은 방은 아래 줄에 2개씩)
    pub fn room_grid(&self) -> Vec<Vec<Room>> {
        let rooms = self.rooms();
        let mut used = HashSet::new();
        let mut rows = vec![];
        for keys in [
            vec!["all", "g:아이들"],
            vec!["아빠", "엄마"],
            vec!["첫째", "둘째", "셋째"],
        ] {
            let l: Vec<Room> = keys
                .iter()
                .filter_map(|k| rooms.iter().find(|r| r.key == *k).cloned())
                .collect();
            if !l.is_empty() {
                for r in &l {
                    used.insert(r.key.clone());
                }
                rows.push(l);
            }
        }
        let rest: Vec<Room> = rooms
            .into_iter()
            .filter(|r| !used.contains(&r.key))
            .collect();
        for ch in rest.chunks(2) {
            rows.push(ch.to_vec());
        }
        rows
    }

    pub fn is_me_room(&self, room: &Room) -> bool {
        room.members.as_deref() == Some(&[self.cfg.name.clone()][..])
    }

    /// 지금 고른 상대에 해당하는 방. 방에 없는 조합이면 custom
    pub fn current_room(&self) -> Room {
        let tl: HashSet<String> = self.target_list().into_iter().collect();
        for room in self.rooms() {
            match &room.members {
                None => {
                    if self.cfg.target == "all" {
                        return room;
                    }
                }
                Some(m) => {
                    if m.iter().cloned().collect::<HashSet<_>>() == tl {
                        return room;
                    }
                }
            }
        }
        let tl: Vec<String> = self.target_list();
        Room {
            key: "custom".into(),
            label: tl
                .iter()
                .map(|t| self.disp(t).to_string())
                .collect::<Vec<_>>()
                .join(", "),
            members: Some(tl),
            icon: "👥",
            color: GREY,
        }
    }

    pub fn select_room(&mut self, key: &str) {
        if self.talking {
            return;
        }
        if let Some(r) = self.rooms().into_iter().find(|r| r.key == key) {
            self.set_target(match r.members {
                None => "all".into(),
                Some(m) => m.join(","),
            });
        }
    }

    /// 가족 버튼 우클릭: 선택/해제 토글 (여러 명 가능)
    pub fn toggle_target(&mut self, name: &str) {
        if self.talking {
            return;
        }
        if name == "all" {
            self.set_target("all".into());
            return;
        }
        if name == self.cfg.name {
            return;
        } // 나는 묶음에 못 넣음
        let mut cur = self.target_list();
        cur.retain(|n| n != &self.cfg.name);
        if let Some(i) = cur.iter().position(|n| n == name) {
            cur.remove(i);
        } else {
            cur.push(name.to_string());
        }
        self.set_target(if cur.is_empty() {
            "all".into()
        } else {
            cur.join(",")
        });
    }

    /// 그 방에 보이는 메시지: 모두 방은 전부, '나' 방은 내 메모만, 나머지는 그 사람(들)과 주고받은 것
    pub fn room_clips(&self, room: &Room) -> Vec<&Clip> {
        let Some(members) = &room.members else {
            return self.clips.iter().collect();
        };
        let ms: HashSet<&str> = members.iter().map(|s| s.as_str()).collect();
        if self.is_me_room(room) {
            return self
                .clips
                .iter()
                .filter(|c| c.mine && c.mode == "direct" && c.from == self.cfg.name)
                .collect();
        }
        self.clips
            .iter()
            .filter(|c| {
                if c.mine {
                    c.mode != "all"
                        && c.from != self.cfg.name
                        && c.from.split(", ").any(|f| ms.contains(f))
                } else {
                    ms.contains(c.from.as_str())
                }
            })
            .collect()
    }

    /// 이 메시지가 '누구와의 대화'인지. 모두에게 보낸 글은 None(묶음 없음), 그 외는 (키, 꼬리표, 색)
    pub fn conv_of(&self, c: &Clip) -> Option<(String, String, [u8; 3])> {
        if c.mode == "all" {
            return None;
        }
        let names: Vec<&str> = if c.mine {
            c.from.split(", ").filter(|n| !n.is_empty()).collect()
        } else {
            vec![c.from.as_str()]
        };
        if names.len() == 1 {
            let n = names[0];
            if n == self.cfg.name {
                return Some((n.into(), "📝 나의 메모".into(), member_style(n).1));
            }
            let d = self.disp(n);
            return Some((
                n.into(),
                format!("{} {}{}의 대화", member_style(n).0, d, josa(d, "과", "와")),
                member_style(n).1,
            ));
        }
        let set: HashSet<&str> = names.iter().cloned().collect();
        for room in self.rooms() {
            if let Some(m) = &room.members {
                if m.iter().map(|s| s.as_str()).collect::<HashSet<_>>() == set {
                    return Some((
                        room.key.clone(),
                        format!("{} {}에게", room.icon, room.label),
                        room.color,
                    ));
                }
            }
        }
        let mut sorted: Vec<&str> = names.clone();
        sorted.sort();
        Some((
            format!("c:{}", sorted.join(",")),
            format!(
                "{}에게",
                names
                    .iter()
                    .map(|n| self.disp(n))
                    .collect::<Vec<_>>()
                    .join(", ")
            ),
            GREY,
        ))
    }

    pub fn unread_count(&self, room: &Room) -> usize {
        self.room_clips(room)
            .iter()
            .filter(|c| !c.mine && c.mid != 0 && !self.seen_sent.contains(&c.mid))
            .count()
    }

    /// 내가 보낸 메시지의 가족별 확인 표시 — 기호 대신 말로: "규민 읽음 · 로아 받음 · 엄마 보냄"
    pub fn receipt_text(&self, c: &Clip) -> String {
        let Some(st) = self.receipts.get(&c.mid) else {
            return String::new();
        };
        let mut names: Vec<&String> = st.keys().collect();
        names.sort_by_key(|n| self.members.iter().position(|m| m == *n).unwrap_or(99));
        names
            .iter()
            .map(|n| {
                let w = match st[*n].as_str() {
                    "seen" => "읽음",
                    "received" => "받음",
                    _ => "보냄",
                };
                format!("{} {}", self.disp(n), w)
            })
            .collect::<Vec<_>>()
            .join(" · ")
    }

    pub fn clip_title(&self, c: &Clip) -> String {
        let who = self.disp_list(&c.from);
        if c.mine {
            let what = match c.kind {
                Kind::VoiceSent => format!(" (음성 {}초)", (c.seconds as i64).max(1)),
                Kind::File => format!(" (📎 {})", c.name),
                _ => String::new(),
            };
            return format!(
                "내가 {}{}",
                if c.mode == "all" {
                    "모두에게".to_string()
                } else {
                    format!("{who}에게")
                },
                what
            );
        }
        match c.mode.as_str() {
            "all" => format!("{who} 전체 방송"),
            "group" => format!("{who}가 몇 명에게"),
            _ => format!("{who}가 나에게"),
        }
    }

    // ---- 서버 메시지 적용 ----

    pub fn on_welcome(&mut self, w: &Welcome) {
        if !w.members.is_empty() {
            self.members = w.members.clone();
        }
        if !w.parents.is_empty() {
            self.parents = w.parents.iter().cloned().collect();
        }
        if w.names != self.names {
            self.names = w.names.clone();
            self.save_names();
        }
        self.notice.clear();
        self.flush_outbox();
    }

    pub fn on_state(&mut self, connected: bool, where_: Option<String>, status: String) {
        self.connected = connected;
        self.where_ = where_;
        self.status = status;
        if !connected {
            self.talking = false;
            self.requesting = false;
            self.mic_wanted = false;
            self.incoming_from = None;
            self.incoming_mode = None;
            self.online.clear();
            self.member_where.clear();
        }
    }

    pub fn on_message(&mut self, m: &Value) {
        let t = m.get("type").and_then(|t| t.as_str()).unwrap_or("");
        let s = |k: &str| m.get(k).and_then(|v| v.as_str()).unwrap_or("").to_string();
        let i = |k: &str| m.get(k).and_then(|v| v.as_i64()).unwrap_or(0);
        let now = chrono::Local::now().timestamp();
        match t {
            "presence" => {
                self.online = m
                    .get("online")
                    .and_then(|v| v.as_array())
                    .map(|a| {
                        a.iter()
                            .filter_map(|x| x.as_str().map(String::from))
                            .collect()
                    })
                    .unwrap_or_default();
                self.member_where = m
                    .get("where")
                    .and_then(|v| v.as_object())
                    .map(|o| {
                        o.iter()
                            .filter_map(|(k, v)| v.as_str().map(|s| (k.clone(), s.to_string())))
                            .collect()
                    })
                    .unwrap_or_default();
            }
            "talk_granted" => {
                if !self.requesting {
                    return;
                }
                self.requesting = false;
                self.talking = true;
                self.mic_wanted = true;
                self.sounds.push(Sound::BeepOk);
                self.notice = if s("to") == "all" {
                    "전체 방송 중".into()
                } else {
                    format!("{}에게 말하는 중", self.target_label())
                };
            }
            "talk_denied" => {
                self.requesting = false;
                self.sounds.push(Sound::BeepNo);
                self.notice = match s("reason").as_str() {
                    "busy" => format!("{}가 말하는 중이에요", self.disp(&s("speaker"))),
                    "offline" => format!("{}가 지금 접속해 있지 않아요", self.disp_list(&s("to"))),
                    _ => "지금은 말할 수 없어요".into(),
                };
            }
            "talk_stopped" => {
                self.talking = false;
                self.requesting = false;
                self.mic_wanted = false;
                self.sounds.push(Sound::BeepNo);
                self.notice = if s("reason") == "too_long" {
                    "1분이 지나 자동으로 끝났어요".into()
                } else {
                    "송신이 끝났어요".into()
                };
            }
            "talk_silent" => {
                self.talking = false;
                self.requesting = false;
                self.mic_wanted = false;
                self.notice = "소리가 안 들려 보내지 않았어요".into();
            }
            "incoming" => {
                self.incoming_from = Some(s("from"));
                self.incoming_mode = Some(s("mode"));
                self.stop_clip();
                self.sounds.push(Sound::BeepIn);
                self.notices.push(Notice::Incoming {
                    from: s("from"),
                    mode: s("mode"),
                });
            }
            "incoming_end" => {
                self.incoming_from = None;
                self.incoming_mode = None;
            }
            "clip" => {
                self.pending.insert(i("id"), m.clone());
            }
            "text" => {
                let cid = i("id");
                self.outgoing.push(json!({"type": "clip_ack", "id": cid}));
                let (from, ts, text) = (
                    s("from"),
                    if i("ts") > 0 { i("ts") } else { now },
                    s("text"),
                );
                if self
                    .clips
                    .iter()
                    .any(|c| c.kind == Kind::Text && c.from == from && c.ts == ts && c.text == text)
                {
                    return;
                }
                let clip = Clip {
                    kind: Kind::Text,
                    id: cid,
                    mid: i("mid"),
                    ts,
                    from,
                    mode: s("mode"),
                    text,
                    ..Clip::empty()
                };
                self.add_clip(clip.clone());
                self.notices.push(Notice::Text(clip));
            }
            "text_sent" | "text_echo" => {
                let mid = i("mid");
                if t == "text_echo" && self.clips.iter().any(|c| c.mid == mid) {
                    return;
                }
                let to = to_label(m.get("to"));
                let clip = Clip {
                    kind: Kind::Text,
                    id: i("id"),
                    mid,
                    ts: if i("ts") > 0 { i("ts") } else { now },
                    text: s("text"),
                    mine: true,
                    from: if to == "all" {
                        self.cfg.name.clone()
                    } else {
                        to.clone()
                    },
                    mode: if to == "all" {
                        "all".into()
                    } else {
                        "direct".into()
                    },
                    ..Clip::empty()
                };
                self.add_clip(clip);
                if t == "text_sent" {
                    self.notice = "보냈어요".into();
                }
            }
            "talk_sent" | "talk_echo" => {
                let mid = i("mid");
                if t == "talk_echo" && self.clips.iter().any(|c| c.mid == mid) {
                    return;
                }
                let to = to_label(m.get("to"));
                let all = s("mode") == "all";
                self.add_clip(Clip {
                    kind: Kind::VoiceSent,
                    id: mid,
                    mid,
                    ts: if i("ts") > 0 { i("ts") } else { now },
                    mine: true,
                    from: if all { self.cfg.name.clone() } else { to },
                    mode: if all { "all".into() } else { "direct".into() },
                    seconds: m.get("seconds").and_then(|v| v.as_f64()).unwrap_or(0.0),
                    ..Clip::empty()
                });
            }
            "file" => {
                let clip = self.file_clip(m, false);
                if self
                    .clips
                    .iter()
                    .any(|c| c.mid == clip.mid && clip.mid != 0)
                {
                    return;
                }
                self.outgoing
                    .push(json!({"type": "clip_ack", "id": clip.id}));
                self.add_clip(clip.clone());
                self.notices.push(Notice::FileIn(clip));
            }
            "file_echo" => {
                let clip = self.file_clip(m, true);
                if !self.clips.iter().any(|c| c.mid == clip.mid) {
                    self.add_clip(clip);
                }
            }
            "file_expired" => {
                let gone: HashSet<String> = m
                    .get("fids")
                    .and_then(|v| v.as_array())
                    .map(|a| {
                        a.iter()
                            .filter_map(|x| x.as_str().map(String::from))
                            .collect()
                    })
                    .unwrap_or_default();
                for c in &mut self.clips {
                    if c.kind == Kind::File && gone.contains(&c.fid) {
                        c.expired = true;
                    }
                }
                self.save_texts();
            }
            "seen_sync" => {
                self.seen_sent.insert(i("mid"));
            }
            "history" => {
                self.history_more = m.get("more").and_then(|v| v.as_bool()).unwrap_or(false);
                self.loading_older = false;
                let items: Vec<Value> = m
                    .get("items")
                    .and_then(|v| v.as_array())
                    .cloned()
                    .unwrap_or_default();
                self.merge_history(&items);
            }
            "receipt" => {
                self.receipts.insert(i("mid"), status_map(m.get("status")));
            }
            "receipts" => {
                for it in m
                    .get("items")
                    .and_then(|v| v.as_array())
                    .cloned()
                    .unwrap_or_default()
                {
                    self.receipts.insert(
                        it.get("mid").and_then(|v| v.as_i64()).unwrap_or(0),
                        status_map(it.get("status")),
                    );
                }
            }
            "schedules" => {
                self.schedules = m
                    .get("items")
                    .and_then(|v| v.as_array())
                    .map(|a| {
                        a.iter()
                            .filter_map(|x| serde_json::from_value(x.clone()).ok())
                            .collect()
                    })
                    .unwrap_or_default();
                self.notices.push(Notice::Schedules);
            }
            "schedule_failed" => {
                self.notice = format!("예약 실패: {}", s("reason"));
                self.notices.push(Notice::Schedules);
            }
            "text_failed" => {
                self.notice = "받을 사람이 없어요 (상대를 다시 골라 주세요)".into();
                crate::log::info(&format!(
                    "문자 실패(서버): 받을 사람 없음 to={:?}",
                    m.get("to")
                ));
                self.set_target("all".into());
            }
            _ => {}
        }
    }

    fn file_clip(&self, m: &Value, mine: bool) -> Clip {
        let s = |k: &str| m.get(k).and_then(|v| v.as_str()).unwrap_or("").to_string();
        let i = |k: &str| m.get(k).and_then(|v| v.as_i64()).unwrap_or(0);
        let to = to_label(m.get("to"));
        let mode = if s("mode").is_empty() {
            "direct".to_string()
        } else {
            s("mode")
        };
        Clip {
            kind: Kind::File,
            id: if i("id") != 0 { i("id") } else { i("mid") },
            mid: i("mid"),
            ts: if i("ts") > 0 {
                i("ts")
            } else {
                chrono::Local::now().timestamp()
            },
            mode: mode.clone(),
            fid: s("fid"),
            name: if s("name").is_empty() {
                "file".into()
            } else {
                s("name")
            },
            size: i("size"),
            mime: s("mime"),
            thumb: m
                .get("thumb")
                .map(|v| !v.is_null() && v != &json!(false) && v != &json!(0))
                .unwrap_or(false),
            expired: m.get("expired").and_then(|v| v.as_bool()).unwrap_or(false),
            from: if mine {
                if mode == "all" {
                    self.cfg.name.clone()
                } else {
                    to
                }
            } else {
                s("from")
            },
            mine,
            ..Clip::empty()
        }
    }

    fn add_clip(&mut self, clip: Clip) {
        let persist = matches!(clip.kind, Kind::Text | Kind::VoiceSent | Kind::File);
        self.clips.insert(0, clip);
        self.clips
            .sort_by(|a, b| b.ts.cmp(&a.ts).then(b.mid.cmp(&a.mid)));
        self.clips.truncate(MAX_CLIPS);
        if persist {
            self.save_texts();
        }
    }

    /// 서버가 보관한 문자 기록을 합침 (이미 있는 것은 mid로 건너뜀) → 폰·PC 어디서나 같은 대화
    pub fn merge_history(&mut self, items: &[Value]) {
        let mut have: HashSet<i64> = self
            .clips
            .iter()
            .filter(|c| c.mid != 0)
            .map(|c| c.mid)
            .collect();
        let mut added = 0;
        for it in items {
            let mid = it.get("mid").and_then(|v| v.as_i64()).unwrap_or(0);
            if mid == 0 || have.contains(&mid) {
                continue;
            }
            let from = it
                .get("from")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();
            let mine = from == self.cfg.name;
            let clip = if it.get("kind").and_then(|v| v.as_str()) == Some("file") {
                self.file_clip(it, mine)
            } else {
                let mode = it
                    .get("mode")
                    .and_then(|v| v.as_str())
                    .unwrap_or("direct")
                    .to_string();
                let to = to_label(it.get("to"));
                Clip {
                    kind: Kind::Text,
                    id: mid,
                    mid,
                    ts: it.get("ts").and_then(|v| v.as_i64()).unwrap_or(0),
                    text: it
                        .get("text")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string(),
                    from: if mine {
                        if mode == "all" {
                            self.cfg.name.clone()
                        } else {
                            to
                        }
                    } else {
                        from
                    },
                    mode,
                    mine,
                    ..Clip::empty()
                }
            };
            self.clips.push(clip);
            have.insert(mid);
            added += 1;
        }
        if added > 0 {
            self.clips
                .sort_by(|a, b| b.ts.cmp(&a.ts).then(b.mid.cmp(&a.mid)));
            self.clips.truncate(MAX_CLIPS);
            self.save_texts();
            crate::log::info(&format!("대화 기록 {added}개 합침"));
        }
    }

    /// 대화창을 맨 위까지 올렸을 때: 서버에 더 옛 기록이 있으면 이어서 받아 옴
    pub fn load_older(&mut self) -> bool {
        if !self.history_more || self.loading_older || !self.connected {
            return false;
        }
        let Some(oldest) = self
            .clips
            .iter()
            .filter(|c| c.mid != 0)
            .map(|c| c.mid)
            .min()
        else {
            return false;
        };
        self.loading_older = true;
        self.outgoing
            .push(json!({"type": "history_before", "mid": oldest, "limit": 100}));
        true
    }

    // ---- 보내기 ----

    pub fn send_text(&mut self, text: &str, to: Option<Value>) -> bool {
        let text = text.trim();
        if text.is_empty() {
            return false;
        }
        let target = to.unwrap_or_else(|| self.target_json());
        if self.connected {
            self.outgoing
                .push(json!({"type": "text", "to": target, "text": text}));
            crate::log::info(&format!("문자 보내기 -> {target}: {text}"));
            return true;
        }
        self.outbox.push(OutboxItem {
            to: target.clone(),
            text: text.into(),
            ts: chrono::Local::now().timestamp(),
            from: self.cfg.name.clone(),
        });
        let n = self.outbox.len();
        if n > 50 {
            self.outbox.drain(0..n - 50);
        }
        self.save_outbox();
        self.notice = format!(
            "지금 연결이 없어서 보관했어요. 연결되면 자동으로 보내요 ({}개 대기)",
            self.outbox.len()
        );
        crate::log::info(&format!(
            "문자 보관(연결 없음, {}) -> {target}: {text}",
            self.status
        ));
        true
    }

    fn flush_outbox(&mut self) {
        if self.outbox.is_empty() {
            return;
        }
        let now = chrono::Local::now().timestamp();
        let pending = std::mem::take(&mut self.outbox);
        let mut sent = 0;
        for item in pending {
            if now - item.ts > 24 * 3600 || item.from != self.cfg.name {
                continue;
            }
            self.outgoing
                .push(json!({"type": "text", "to": item.to, "text": item.text}));
            sent += 1;
        }
        self.save_outbox();
        crate::log::info(&format!("밀린 문자 전송: {sent}개"));
        if sent > 0 {
            self.notice = format!("연결돼서 밀린 문자 {sent}개를 보냈어요");
        }
    }

    /// 받은 메시지를 봤다/들었다고 보낸 사람에게 알림 (한 번만)
    /// 첨부 올리기가 끝남 (서버 응답 = file 메시지): 내 말풍선 추가
    pub fn on_file_sent(&mut self, m: &Value) {
        let clip = self.file_clip(m, true);
        if clip.mid != 0 && self.clips.iter().any(|c| c.mid == clip.mid) {
            return;
        }
        self.add_clip(clip);
    }

    pub fn mark_expired(&mut self, fid: &str) {
        for c in &mut self.clips {
            if c.kind == Kind::File && c.fid == fid {
                c.expired = true;
            }
        }
        self.save_texts();
    }

    pub fn mark_seen(&mut self, mid: i64, mine: bool) {
        if mid == 0 || mine || self.seen_sent.contains(&mid) || !self.connected {
            return;
        }
        self.seen_sent.insert(mid);
        self.outgoing.push(json!({"type": "seen", "mid": mid}));
    }

    pub fn start_talk(&mut self) {
        if self.talking || self.requesting {
            return;
        }
        if !self.connected {
            self.notice = "연결이 안 되어 있어요".into();
            return;
        }
        if let Some(f) = &self.incoming_from {
            self.notice = format!("{}가 말하는 중이에요", self.disp(f));
            return;
        }
        if self.target_list() == vec![self.cfg.name.clone()] {
            self.notice = "나에게는 글·사진만 보낼 수 있어요 (메모)".into();
            return;
        }
        self.requesting = true;
        self.outgoing
            .push(json!({"type": "talk_start", "to": self.target_json()}));
    }
    pub fn stop_talk(&mut self) {
        if !(self.talking || self.requesting) {
            return;
        }
        self.talking = false;
        self.requesting = false;
        self.mic_wanted = false;
        self.outgoing.push(json!({"type": "talk_stop"}));
    }

    /// 마이크를 못 열었을 때 (engine 이 부름)
    pub fn mic_failed(&mut self) {
        self.notice = "마이크를 사용할 수 없어요".into();
        self.stop_talk();
    }

    // ---- 소리 조각 ----

    /// 서버가 보낸 소리: 1=실시간(누가 말하는 중), 2=보관 음성(id 4바이트 + 소리)
    pub fn on_binary(&mut self, data: &[u8]) {
        if data.is_empty() {
            return;
        }
        match data[0] {
            1 => {
                if self.incoming_from.is_some() {
                    self.sounds.push(Sound::Live(data[1..].to_vec()));
                }
            }
            2 if data.len() > 5 => {
                let cid = u32::from_be_bytes([data[1], data[2], data[3], data[4]]) as i64;
                self.on_clip(cid, &data[5..]);
            }
            _ => {}
        }
    }

    fn clip_dir(&self) -> std::path::PathBuf {
        self.data_dir.join("clips")
    }

    fn on_clip(&mut self, cid: i64, pcm: &[u8]) {
        let Some(meta) = self.pending.remove(&cid) else {
            return;
        };
        self.outgoing.push(json!({"type": "clip_ack", "id": cid}));
        let ts = meta
            .get("ts")
            .and_then(|v| v.as_i64())
            .filter(|t| *t > 0)
            .unwrap_or_else(|| chrono::Local::now().timestamp());
        let sender = meta
            .get("from")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let mode = meta
            .get("mode")
            .and_then(|v| v.as_str())
            .unwrap_or("direct")
            .to_string();
        if self
            .clips
            .iter()
            .any(|c| c.kind == Kind::Clip && c.from == sender && c.ts == ts)
        {
            return;
        }
        let seconds = meta
            .get("seconds")
            .and_then(|v| v.as_f64())
            .filter(|s| *s > 0.0)
            .unwrap_or(pcm.len() as f64 / 32000.0);
        let dir = self.clip_dir();
        let _ = std::fs::create_dir_all(&dir);
        let path = dir.join(format!(
            "{}_{}_{}_{}_{:.1}.pcm",
            cid, sender, mode, ts, seconds
        ));
        if std::fs::write(&path, pcm).is_err() {
            return;
        }
        let clip = Clip {
            kind: Kind::Clip,
            id: cid,
            mid: meta.get("mid").and_then(|v| v.as_i64()).unwrap_or(0),
            from: sender,
            mode,
            ts,
            seconds,
            file: path.to_string_lossy().to_string(),
            ..Clip::empty()
        };
        self.add_clip(clip.clone());
        let auto = self.where_.as_deref() == Some("home") || self.is_parent();
        if auto {
            self.play_clip(cid);
        }
        self.notices.push(Notice::Clip { clip, auto });
    }

    /// 보관 음성 듣기 (앞에 알림음). 듣기 시작하면 보낸 사람에게 '들음'
    pub fn play_clip(&mut self, id: i64) {
        if self.incoming_from.is_some() || self.talking {
            return;
        }
        let Some(c) = self
            .clips
            .iter()
            .find(|c| c.kind == Kind::Clip && c.id == id)
            .cloned()
        else {
            return;
        };
        if c.file.is_empty() || !std::path::Path::new(&c.file).exists() {
            self.notice = "소리 파일이 없어요 (24시간이 지나 지워졌을 수 있어요)".into();
            return;
        }
        let end = chrono::Local::now().timestamp() + c.seconds.ceil() as i64 + 1;
        self.playing = Some((id, end));
        self.sounds.push(Sound::PlayFile(c.file.clone()));
        self.mark_seen(c.mid, c.mine);
    }

    pub fn stop_clip(&mut self) {
        if self.playing.take().is_some() {
            self.sounds.push(Sound::Stop);
        }
    }

    /// engine 이 주기적으로 부름: 재생이 끝났으면 표시를 지움
    pub fn tick(&mut self) {
        if let Some((_, end)) = self.playing {
            if chrono::Local::now().timestamp() >= end {
                self.playing = None;
            }
        }
    }

    pub fn is_playing(&self, id: i64) -> bool {
        self.playing.map(|(i, _)| i == id).unwrap_or(false)
    }

    pub fn schedule_add(&mut self, text: &str, at: i64, repeat: &str, to: Option<Value>) {
        let to = to.unwrap_or_else(|| self.target_json());
        self.outgoing.push(
            json!({"type": "schedule_add", "to": to, "text": text, "at": at, "repeat": repeat}),
        );
    }
    pub fn schedule_delete(&mut self, sid: i64) {
        self.outgoing
            .push(json!({"type": "schedule_delete", "sid": sid}));
    }

    pub fn delete_clip(&mut self, mid: i64, id: i64) {
        if self.is_playing(id) {
            self.stop_clip();
        }
        if let Some(c) = self.clips.iter().find(|c| c.mid == mid && c.id == id) {
            if !c.file.is_empty() {
                let _ = std::fs::remove_file(&c.file);
            }
        }
        self.clips.retain(|c| !(c.mid == mid && c.id == id));
        self.save_texts();
    }

    /// `clips/` 폴더의 소리 파일 목록 → 받은 음성 (파일 이름: id_보낸이_방식_시각_초.pcm). 24시간 지난 것은 지움
    fn load_clip_files(&mut self) {
        let dir = self.clip_dir();
        let Ok(rd) = std::fs::read_dir(&dir) else {
            return;
        };
        let now = chrono::Local::now().timestamp();
        for e in rd.flatten() {
            let name = e.file_name().to_string_lossy().to_string();
            let Some(stem) = name.strip_suffix(".pcm") else {
                continue;
            };
            let p: Vec<&str> = stem.split('_').collect();
            if p.len() < 5 {
                continue;
            }
            let (Ok(id), Ok(ts), Ok(seconds)) = (
                p[0].parse::<i64>(),
                p[3].parse::<i64>(),
                p[4].parse::<f64>(),
            ) else {
                continue;
            };
            if now - ts > 24 * 3600 {
                let _ = std::fs::remove_file(e.path());
                continue;
            }
            if self
                .clips
                .iter()
                .any(|c| c.kind == Kind::Clip && c.id == id)
            {
                continue;
            }
            self.clips.push(Clip {
                kind: Kind::Clip,
                id,
                mid: 0,
                from: p[1].to_string(),
                mode: p[2].to_string(),
                ts,
                seconds,
                file: e.path().to_string_lossy().to_string(),
                ..Clip::empty()
            });
        }
        self.clips
            .sort_by(|a, b| b.ts.cmp(&a.ts).then(b.mid.cmp(&a.mid)));
    }

    // ---- 파일 보관 (texts.json / outbox.json / names.json — Python판과 같은 자리) ----

    fn load_texts(&mut self) {
        if let Ok(t) = std::fs::read_to_string(self.data_dir.join("texts.json")) {
            if let Ok(v) = serde_json::from_str::<Vec<Clip>>(&t) {
                self.clips = v;
            }
        }
        self.clips
            .sort_by(|a, b| b.ts.cmp(&a.ts).then(b.mid.cmp(&a.mid)));
    }
    fn save_texts(&self) {
        let keep: Vec<&Clip> = self
            .clips
            .iter()
            .filter(|c| matches!(c.kind, Kind::Text | Kind::VoiceSent | Kind::File))
            .take(500)
            .collect();
        let _ = std::fs::create_dir_all(&self.data_dir);
        if let Ok(s) = serde_json::to_string(&keep) {
            let _ = std::fs::write(self.data_dir.join("texts.json"), s);
        }
    }
    fn load_outbox(&mut self) {
        if let Ok(t) = std::fs::read_to_string(self.data_dir.join("outbox.json")) {
            if let Ok(v) = serde_json::from_str::<Vec<OutboxItem>>(&t) {
                self.outbox = v;
                self.outbox.truncate(50);
            }
        }
    }
    fn save_outbox(&self) {
        if let Ok(s) = serde_json::to_string(&self.outbox) {
            let _ = std::fs::write(self.data_dir.join("outbox.json"), s);
        }
    }
    fn load_names(&mut self) {
        if let Ok(t) = std::fs::read_to_string(self.data_dir.join("names_cache.json")) {
            if let Ok(v) = serde_json::from_str(&t) {
                self.names = v;
            }
        }
    }
    fn save_names(&self) {
        if let Ok(s) = serde_json::to_string(&self.names) {
            let _ = std::fs::write(self.data_dir.join("names_cache.json"), s);
        }
    }
}

impl Clip {
    pub fn empty() -> Clip {
        Clip {
            kind: Kind::Text,
            id: 0,
            mid: 0,
            ts: 0,
            from: String::new(),
            mode: "direct".into(),
            text: String::new(),
            mine: false,
            seconds: 0.0,
            fid: String::new(),
            name: String::new(),
            size: 0,
            mime: String::new(),
            thumb: false,
            expired: false,
            file: String::new(),
        }
    }
}

/// 서버의 to 필드: "all" | ["엄마","첫째"] → "all" | "엄마, 첫째"
fn to_label(v: Option<&Value>) -> String {
    match v {
        Some(Value::String(s)) => s.clone(),
        Some(Value::Array(a)) => a
            .iter()
            .filter_map(|x| x.as_str())
            .collect::<Vec<_>>()
            .join(", "),
        _ => "all".into(),
    }
}

fn status_map(v: Option<&Value>) -> HashMap<String, String> {
    v.and_then(|v| v.as_object())
        .map(|o| {
            o.iter()
                .filter_map(|(k, v)| v.as_str().map(|s| (k.clone(), s.to_string())))
                .collect()
        })
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn names_for_dad(s: &mut State) {
        s.names = [
            ("첫째", "규민"),
            ("둘째", "규준"),
            ("셋째", "로아"),
            ("엄마", "진희"),
        ]
        .iter()
        .map(|(a, b)| (a.to_string(), b.to_string()))
        .collect();
    }

    #[test]
    fn josa_picks_batchim() {
        assert_eq!(josa("규민", "과", "와"), "과");
        assert_eq!(josa("로아", "과", "와"), "와");
        assert_eq!(josa("엄마", "과", "와"), "와");
        assert_eq!(josa("", "과", "와"), "와");
    }

    #[test]
    fn rooms_fixed_grid_with_me_and_names() {
        let mut s = State::for_test("둘째");
        names_for_dad(&mut s);
        let grid = s.room_grid();
        let labels: Vec<Vec<String>> = grid
            .iter()
            .map(|r| r.iter().map(|x| x.label.clone()).collect())
            .collect();
        assert_eq!(
            labels,
            vec![
                vec!["모두", "아이들"],
                vec!["아빠", "진희"],
                vec!["규민", "나", "로아"]
            ]
        );
        assert!(s.rooms().iter().all(|r| r.key != "g:부모님"));
        let me_room = s.rooms().into_iter().find(|r| r.key == "둘째").unwrap();
        assert!(s.is_me_room(&me_room));
        assert_eq!(me_room.icon, "📝");
    }

    #[test]
    fn me_room_shows_only_my_memos_and_blocks_talk() {
        let mut s = State::for_test("아빠");
        s.connected = true;
        s.on_message(
            &json!({"type":"text_sent","id":1,"mid":10,"to":["아빠"],"text":"메모","ts":100}),
        );
        s.on_message(
            &json!({"type":"text_sent","id":2,"mid":11,"to":["엄마"],"text":"엄마에게","ts":101}),
        );
        s.on_message(&json!({"type":"text","id":3,"mid":12,"from":"엄마","mode":"direct","text":"답","ts":102}));
        let me = s.rooms().into_iter().find(|r| r.key == "아빠").unwrap();
        let mom = s.rooms().into_iter().find(|r| r.key == "엄마").unwrap();
        assert_eq!(
            s.room_clips(&me).iter().map(|c| c.mid).collect::<Vec<_>>(),
            vec![10]
        );
        assert_eq!(
            s.room_clips(&mom).iter().map(|c| c.mid).collect::<Vec<_>>(),
            vec![12, 11]
        );
        assert_eq!(s.unread_count(&mom), 1);
        s.select_room("아빠");
        s.start_talk();
        assert!(!s.requesting);
        assert!(s.notice.contains("메모"));
        assert_eq!(
            s.conv_of(&s.clips.iter().find(|c| c.mid == 10).unwrap().clone())
                .unwrap()
                .1,
            "📝 나의 메모"
        );
    }

    #[test]
    fn receipt_text_in_words_with_display_names() {
        let mut s = State::for_test("아빠");
        names_for_dad(&mut s);
        s.on_message(&json!({"type":"text_sent","id":1,"mid":5,"to":"all","text":"다들","ts":1}));
        s.on_message(&json!({"type":"receipt","mid":5,"status":{"첫째":"seen","엄마":"received","셋째":"sent"}}));
        let c = s.clips[0].clone();
        assert_eq!(s.receipt_text(&c), "진희 받음 · 규민 읽음 · 로아 보냄");
        assert_eq!(s.clip_title(&c), "내가 모두에게");
        let conv = s
            .conv_of(&Clip {
                from: "첫째".into(),
                ..Clip::empty()
            })
            .unwrap();
        assert_eq!(conv.1, "👦 규민과의 대화");
    }

    #[test]
    fn history_merge_dedup_and_load_older() {
        let mut s = State::for_test("아빠");
        s.connected = true;
        s.on_message(&json!({"type":"history","more":true,"items":[
            {"mid":3,"from":"엄마","to":["아빠"],"mode":"direct","text":"a","ts":30},
            {"mid":2,"from":"아빠","to":"all","mode":"all","text":"b","ts":20},
            {"mid":3,"from":"엄마","to":["아빠"],"mode":"direct","text":"a","ts":30}]}));
        assert_eq!(s.clips.len(), 2);
        assert!(s.clips[1].mine && s.clips[1].mode == "all");
        assert!(s.load_older());
        assert_eq!(s.outgoing.last().unwrap()["mid"], 2);
        assert!(!s.load_older()); // 이미 받는 중
        s.on_message(&json!({"type":"history","more":false,"items":[{"mid":1,"from":"첫째","to":["아빠"],"mode":"direct","text":"c","ts":10}]}));
        assert_eq!(s.clips.len(), 3);
        assert!(!s.load_older()); // 더 없음
    }

    #[test]
    fn outbox_when_offline_then_flush_on_welcome() {
        let mut s = State::for_test("아빠");
        s.select_room("엄마");
        assert!(s.send_text("나중에", None));
        assert!(s.outgoing.is_empty());
        assert!(s.notice.contains("보관"));
        s.connected = true;
        let w: Welcome =
            serde_json::from_value(json!({"members":["엄마","아빠"],"you":"아빠","where":"home"}))
                .unwrap();
        s.on_welcome(&w);
        assert_eq!(s.outgoing.len(), 1);
        assert_eq!(s.outgoing[0]["to"], json!(["엄마"]));
        assert_eq!(s.outgoing[0]["text"], "나중에");
    }

    #[test]
    fn echo_dedup_and_seen_sync() {
        let mut s = State::for_test("아빠");
        s.connected = true;
        s.on_message(
            &json!({"type":"text_echo","id":1,"mid":7,"to":["첫째"],"text":"폰에서","ts":5}),
        );
        s.on_message(
            &json!({"type":"text_echo","id":1,"mid":7,"to":["첫째"],"text":"폰에서","ts":5}),
        );
        assert_eq!(s.clips.len(), 1);
        assert_eq!(s.clips[0].from, "첫째");
        s.on_message(
            &json!({"type":"text","id":2,"mid":8,"from":"첫째","mode":"direct","text":"x","ts":6}),
        );
        let room = s.rooms().into_iter().find(|r| r.key == "첫째").unwrap();
        assert_eq!(s.unread_count(&room), 1);
        s.on_message(&json!({"type":"seen_sync","mid":8}));
        assert_eq!(s.unread_count(&room), 0);
        s.mark_seen(8, false);
        assert!(!s.outgoing.iter().any(|o| o["type"] == "seen")); // 이미 읽음 처리됨 → 다시 안 보냄
    }

    #[test]
    fn voice_clip_saved_and_autoplayed_at_home() {
        let mut s = State::for_test("아빠");
        s.connected = true;
        s.where_ = Some("home".into());
        s.on_message(&json!({"type":"clip","id":9,"mid":40,"from":"엄마","mode":"direct","ts":1700000000,"seconds":0.5}));
        let mut data = vec![2u8, 0, 0, 0, 9];
        data.extend(vec![0u8; 16000]);
        s.on_binary(&data);
        let c = s
            .clips
            .iter()
            .find(|c| c.kind == Kind::Clip)
            .expect("음성 보관")
            .clone();
        assert!(std::path::Path::new(&c.file).exists());
        assert!(s.is_playing(9));
        assert!(s
            .outgoing
            .iter()
            .any(|o| o["type"] == "clip_ack" && o["id"] == 9));
        assert!(s
            .outgoing
            .iter()
            .any(|o| o["type"] == "seen" && o["mid"] == 40));
        assert!(matches!(s.sounds.last(), Some(Sound::PlayFile(_))));
        assert!(matches!(
            s.notices.last(),
            Some(Notice::Clip { auto: true, .. })
        ));
        // 실시간 소리는 누가 말하는 중일 때만 틀음
        s.sounds.clear();
        s.on_binary(&[1u8, 0, 0]);
        assert!(s.sounds.is_empty());
        s.on_message(&json!({"type":"incoming","from":"첫째","mode":"all"}));
        assert!(!s.is_playing(9)); // 받는 중엔 재생 멈춤
        s.on_binary(&[1u8, 0, 0]);
        assert!(matches!(s.sounds.last(), Some(Sound::Live(_))));
        // 말하기 허락 → 마이크 켜기, 끝 → 끄기
        s.on_message(&json!({"type":"incoming_end","from":"첫째"}));
        s.select_room("엄마");
        s.start_talk();
        assert!(s.requesting);
        s.on_message(&json!({"type":"talk_granted","to":["엄마"]}));
        assert!(s.talking && s.mic_wanted);
        s.on_message(&json!({"type":"talk_stopped","reason":"too_long"}));
        assert!(!s.talking && !s.mic_wanted);
        let _ = std::fs::remove_file(&c.file);
    }

    #[test]
    fn toggle_target_builds_group_and_custom_room() {
        let mut s = State::for_test("아빠");
        s.toggle_target("엄마");
        s.toggle_target("첫째");
        assert_eq!(s.target(), "엄마,첫째");
        assert_eq!(s.current_room().key, "custom");
        s.toggle_target("엄마");
        assert_eq!(s.current_room().key, "첫째");
        s.toggle_target("아빠"); // 나는 못 넣음
        assert_eq!(s.target(), "첫째");
        s.toggle_target("첫째");
        assert_eq!(s.target(), "all");
    }
}
