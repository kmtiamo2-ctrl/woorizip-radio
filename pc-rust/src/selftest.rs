//! `--selftest <주소>`: 같은 컴퓨터의 서버에 가상의 아빠(기기 2대)·엄마로 붙어 핵심 동작을 눌러 봄 (CI 와 설치 전 점검용).
//! 창은 띄우지 않고 상태 모듈만으로 확인함. 결과는 OK/FAIL 줄로 출력, 하나라도 FAIL 이면 종료 코드 1.
use crate::app::Shared;
use crate::config::Config;
use crate::net::{Connection, Event};
use crate::state::{Kind, State};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

struct Peer {
    state: Shared,
    _conn: Connection,
}

fn spawn_peer(rt: &tokio::runtime::Runtime, addr: &str, name: &str, device: &str) -> Peer {
    let cfg = Config {
        host: addr.into(),
        out_host: String::new(),
        name: name.into(),
        key: "105403".into(),
        device: Some(device.into()),
        ..Config::default()
    };
    let state: Shared = Arc::new(Mutex::new(State::new(cfg.clone())));
    {
        let mut s = state.lock().unwrap();
        s.clips.clear();
        s.seen_sent.clear();
    }
    let (tx, mut rx) = tokio::sync::mpsc::channel::<Event>(256);
    let conn = rt.block_on(async { Connection::spawn(Arc::new(cfg), tx) });
    let out_tx = conn.outgoing.clone();
    let st = state.clone();
    rt.spawn(async move {
        while let Some(ev) = rx.recv().await {
            let mut s = st.lock().unwrap();
            match ev {
                Event::State {
                    connected,
                    where_,
                    status,
                } => s.on_state(connected, where_, status),
                Event::Welcome(w) => s.on_welcome(&w),
                Event::Message(v) => s.on_message(&v),
                Event::Binary(b) => s.on_binary(&b),
            }
            for v in s.outgoing.drain(..) {
                let _ = out_tx.try_send(v);
            }
        }
    });
    Peer { state, _conn: conn }
}

fn wait(mut f: impl FnMut() -> bool, secs: f64) -> bool {
    let end = Instant::now() + Duration::from_secs_f64(secs);
    while Instant::now() < end {
        if f() {
            return true;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    false
}

pub fn run(addr: &str) -> i32 {
    let rt = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
        .unwrap();
    let mut fails = vec![];
    let mut check = |name: &str, ok: bool| {
        println!("{}{}", if ok { "OK   " } else { "FAIL " }, name);
        if !ok {
            fails.push(name.to_string());
        }
    };
    // 시험용 데이터 자리: 실제 설정·기록을 건드리지 않게
    let tmp = std::env::temp_dir().join(format!("woorizip-selftest-{}", std::process::id()));
    let _ = std::fs::create_dir_all(&tmp);
    std::env::set_var("APPDATA", &tmp);
    std::env::set_var("LOCALAPPDATA", &tmp);
    std::env::set_var("HOME", &tmp);

    let dad = spawn_peer(&rt, addr, "아빠", "selftest-dad");
    let dad2 = spawn_peer(&rt, addr, "아빠", "selftest-dad2");
    let mom = spawn_peer(&rt, addr, "엄마", "selftest-mom");
    let all_connected = wait(
        || {
            dad.state.lock().unwrap().connected
                && dad2.state.lock().unwrap().connected
                && mom.state.lock().unwrap().connected
        },
        8.0,
    );
    check("서버 접속 (아빠 2대 + 엄마)", all_connected);
    if !all_connected {
        println!("실패: {fails:?}");
        return 1;
    }
    {
        let s = dad.state.lock().unwrap();
        let rooms = s.room_grid();
        check(
            "방 버튼 3줄 격자",
            rooms.len() >= 3
                && rooms[0][0].key == "all"
                && rooms[1].iter().any(|r| r.label == "나"),
        );
    }
    // 문자: 아빠 → 엄마
    dad.state
        .lock()
        .unwrap()
        .send_text("점검 글", Some(serde_json::json!(["엄마"])));
    pump(&dad);
    check(
        "엄마에게 글 도착",
        wait(
            || {
                mom.state
                    .lock()
                    .unwrap()
                    .clips
                    .iter()
                    .any(|c| c.kind == Kind::Text && c.text == "점검 글" && c.from == "아빠")
            },
            5.0,
        ),
    );
    check(
        "아빠 말풍선(text_sent)",
        wait(
            || {
                dad.state
                    .lock()
                    .unwrap()
                    .clips
                    .iter()
                    .any(|c| c.mine && c.text == "점검 글")
            },
            3.0,
        ),
    );
    check(
        "아빠 다른 기기에도(text_echo)",
        wait(
            || {
                dad2.state
                    .lock()
                    .unwrap()
                    .clips
                    .iter()
                    .any(|c| c.mine && c.text == "점검 글")
            },
            3.0,
        ),
    );
    // 읽음: 엄마가 보면 아빠 쪽 확인 표시가 '읽음'
    {
        let mid = mom
            .state
            .lock()
            .unwrap()
            .clips
            .iter()
            .find(|c| c.text == "점검 글")
            .map(|c| c.mid)
            .unwrap_or(0);
        mom.state.lock().unwrap().mark_seen(mid, false);
        pump(&mom);
        check(
            "읽음 표시 (말로)",
            wait(
                || {
                    let s = dad.state.lock().unwrap();
                    let r = s
                        .clips
                        .iter()
                        .filter(|c| c.mine && c.text == "점검 글")
                        .map(|c| s.receipt_text(c))
                        .collect::<Vec<_>>();
                    if !r.is_empty() && !r[0].contains("읽음") {
                        eprintln!("   확인 표시: {r:?}");
                    }
                    r.iter().any(|x| x.contains("읽음"))
                },
                5.0,
            ),
        );
        check(
            "엄마 쪽 읽음 처리됨",
            mom.state.lock().unwrap().seen_sent.contains(&mid),
        );
    }
    // 나에게 메모: 다른 가족에게 안 감
    dad.state
        .lock()
        .unwrap()
        .send_text("메모 글", Some(serde_json::json!(["아빠"])));
    pump(&dad);
    check(
        "메모 → 내 기기들에만",
        wait(
            || {
                dad2.state
                    .lock()
                    .unwrap()
                    .clips
                    .iter()
                    .any(|c| c.text == "메모 글")
            },
            3.0,
        ),
    );
    std::thread::sleep(Duration::from_millis(500));
    check(
        "메모 → 엄마에겐 안 감",
        !mom.state
            .lock()
            .unwrap()
            .clips
            .iter()
            .any(|c| c.text == "메모 글"),
    );
    // 음성: 나에게는 불가, 엄마에게는 허락
    {
        let mut s = dad.state.lock().unwrap();
        s.select_room("아빠");
        s.start_talk();
        check("나에게 말하기 막힘", !s.requesting);
        s.select_room("엄마");
        s.start_talk();
    }
    pump(&dad);
    check(
        "말하기 허락(talk_granted)·마이크 켜기",
        wait(
            || dad.state.lock().unwrap().talking && dad.state.lock().unwrap().mic_wanted,
            5.0,
        ),
    );
    dad.state.lock().unwrap().stop_talk();
    pump(&dad);
    check(
        "말하기 끝",
        wait(|| !dad.state.lock().unwrap().talking, 3.0),
    );
    // 예약
    let at = chrono::Local::now().timestamp() + 3600;
    dad.state.lock().unwrap().schedule_add(
        "예약 글",
        at,
        "once",
        Some(serde_json::json!(["엄마"])),
    );
    pump(&dad);
    check(
        "예약 등록 → 목록",
        wait(
            || {
                dad.state
                    .lock()
                    .unwrap()
                    .schedules
                    .iter()
                    .any(|x| x.text == "예약 글")
            },
            5.0,
        ),
    );
    let sid = dad
        .state
        .lock()
        .unwrap()
        .schedules
        .iter()
        .find(|x| x.text == "예약 글")
        .map(|x| x.sid)
        .unwrap_or(0);
    dad.state.lock().unwrap().schedule_delete(sid);
    pump(&dad);
    check(
        "예약 삭제",
        wait(
            || {
                !dad.state
                    .lock()
                    .unwrap()
                    .schedules
                    .iter()
                    .any(|x| x.text == "예약 글")
            },
            5.0,
        ),
    );
    // 기록 이어받기: 새 기기로 붙으면 history 가 옴
    let dad3 = spawn_peer(&rt, addr, "아빠", "selftest-dad3");
    check(
        "새 기기 접속 시 기록 합침",
        wait(
            || {
                dad3.state
                    .lock()
                    .unwrap()
                    .clips
                    .iter()
                    .any(|c| c.text == "점검 글")
            },
            8.0,
        ),
    );
    // 첨부: 사진 올리기 → 엄마 받기 → 원본·미리보기 내려받기
    {
        let p = tmp.join("점검사진.png");
        let img = image::RgbImage::from_fn(640, 480, |x, y| {
            image::Rgb([(x % 256) as u8, (y % 256) as u8, 90])
        });
        let _ = img.save(&p);
        let ok = crate::files::send_file(dad.state.clone(), p, Some("엄마".into()), None);
        check("첨부 올리기 시작", ok);
        check(
            "엄마에게 첨부 도착 (미리보기 포함)",
            wait(
                || {
                    mom.state
                        .lock()
                        .unwrap()
                        .clips
                        .iter()
                        .any(|c| c.kind == Kind::File && c.name == "점검사진.png" && c.thumb)
                },
                15.0,
            ),
        );
        let clip = mom
            .state
            .lock()
            .unwrap()
            .clips
            .iter()
            .find(|c| c.kind == Kind::File)
            .cloned();
        if let Some(c) = clip {
            let dest = crate::files::file_path(&c);
            let done = Arc::new(Mutex::new(false));
            let d2 = done.clone();
            crate::files::fetch(
                mom.state.clone(),
                "file",
                c.clone(),
                dest.clone(),
                Some(Box::new(move |_| *d2.lock().unwrap() = true)),
            );
            check(
                "원본 내려받기",
                wait(|| *done.lock().unwrap(), 15.0) && dest.exists(),
            );
            let tp = crate::files::thumb_path(&c);
            let done2 = Arc::new(Mutex::new(false));
            let d3 = done2.clone();
            crate::files::fetch(
                mom.state.clone(),
                "thumb",
                c,
                tp.clone(),
                Some(Box::new(move |_| *d3.lock().unwrap() = true)),
            );
            check(
                "미리보기 내려받기",
                wait(|| *done2.lock().unwrap(), 15.0) && image::open(&tp).is_ok(),
            );
        }
        check(
            "아빠 쪽 첨부 말풍선",
            wait(
                || {
                    dad.state
                        .lock()
                        .unwrap()
                        .clips
                        .iter()
                        .any(|c| c.kind == Kind::File && c.mine)
                },
                5.0,
            ),
        );
    }
    // 서명 확인 도구
    {
        let seed = "42".repeat(32);
        let pk = crate::update::pubkey_of(&seed).unwrap();
        let sig = {
            let p = tmp.join("sig.bin");
            std::fs::write(&p, b"x").unwrap();
            crate::update::sign_file(&seed, &p).unwrap()
        };
        check(
            "업데이트 서명 확인",
            crate::update::verify_bytes(b"x", &sig, &pk).is_ok()
                && crate::update::verify_bytes(b"y", &sig, &pk).is_err(),
        );
    }
    let _ = std::fs::remove_dir_all(&tmp);
    println!("실패: {fails:?}");
    if fails.is_empty() {
        0
    } else {
        1
    }
}

fn pump(p: &Peer) {
    let mut s = p.state.lock().unwrap();
    for v in s.outgoing.drain(..) {
        let _ = p._conn.outgoing.try_send(v);
    }
}
