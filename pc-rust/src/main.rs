//! 우리집 PC 프로그램 (Rust판).
//!
//! 설정 파일과 기록 파일은 Python판과 같은 자리(`%APPDATA%\WoorizipRadio\`)를 그대로 써서,
//! 바꿔 끼워도 가족 설정이 유지됩니다.
//!
//! 구성: `net`(NAS 연결, tokio) → `state`(상태·대화 자료) ← `app`(egui 창). 통신은 뒤 스레드, 화면은 앞 스레드.
#![allow(dead_code)] // 다음 날들에 쓸 함수·필드를 미리 둠
#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]

mod app;
mod audio;
mod config;
mod files;
mod hotkey;
mod log;
mod net;
mod platform;
mod proto;
mod selftest;
mod state;
mod tray;
mod update;

use std::sync::{Arc, Mutex};

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    if update::cli(&args) {
        return Ok(()); // --sign / --verify / --pubkey (CI 용)
    }
    if args.get(1).map(|a| a.as_str()) == Some("--selftest") {
        let addr = args
            .get(2)
            .cloned()
            .unwrap_or_else(|| "127.0.0.1:8765".into());
        std::process::exit(selftest::run(&addr));
    }
    log::init();
    log::info(&format!("우리집 PC(Rust) 시작 {}", update::version()));
    // 설치 자리가 아닌 곳(내려받은 파일 등)에서 실행됐으면: 설치하고 설치본을 켠 뒤 끝
    if cfg!(windows)
        && !args
            .iter()
            .any(|a| a == "--no-install" || a == "--headless")
        && !platform::is_installed_copy()
    {
        let pass: Vec<String> = args[1..].to_vec();
        match platform::install_self(&pass) {
            Ok(true) => {
                log::info("설치본으로 복사하고 그쪽을 켬");
                return Ok(());
            }
            Ok(false) => {}
            Err(e) => {
                log::info(&format!("설치 실패: {e}"));
                platform::message_box(&format!("설치 중 문제가 생겼어요:\n{e}"), true);
                return Ok(());
            }
        }
    }
    let just_updated = args.iter().any(|a| a == "--updated");
    if just_updated {
        update::cleanup();
    }
    let cfg = config::Config::load();
    if !cfg.configured() {
        log::info("설정에 NAS 주소·이름·암호가 없어요 — Python판 설정 창에서 먼저 저장해 주세요 (Rust판 설정 창은 5일차)");
    }
    let headless = std::env::args().any(|a| a == "--headless"); // 창 없이 연결만 (점검용)
    let start_minimized = std::env::args().any(|a| a == "--minimized");
    // 하나만 실행: 이미 떠 있으면 그 창을 앞으로 보내고 끝
    let guard = if headless {
        None
    } else {
        match platform::single_instance() {
            Some(g) => Some(g),
            None => {
                log::info("이미 실행 중 → 그 창을 열어 달라고 보냄");
                return Ok(());
            }
        }
    };
    let state: app::Shared = Arc::new(Mutex::new(state::State::new(cfg.clone())));
    if just_updated {
        state.lock().unwrap().notice = format!("새 버전 {}으로 업데이트됐어요", update::version());
    }

    // 통신: 별도 스레드의 tokio 런타임
    let rt = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()?;
    let (ev_tx, mut ev_rx) = tokio::sync::mpsc::channel::<net::Event>(256);
    let conn = rt.block_on(async { net::Connection::spawn(Arc::new(cfg), ev_tx) });
    let out_tx = conn.outgoing.clone();
    let repaint: Arc<Mutex<Option<eframe::egui::Context>>> = Arc::new(Mutex::new(None));

    // 서버 → 상태 적용 → 화면 다시 그리기
    {
        let state = state.clone();
        let out_tx = out_tx.clone();
        let repaint = repaint.clone();
        rt.spawn(async move {
            while let Some(ev) = ev_rx.recv().await {
                {
                    let mut s = state.lock().unwrap();
                    match ev {
                        net::Event::State {
                            connected,
                            where_,
                            status,
                        } => s.on_state(connected, where_, status),
                        net::Event::Welcome(w) => s.on_welcome(&w),
                        net::Event::Message(v) => s.on_message(&v),
                        net::Event::Binary(b) => s.on_binary(&b),
                    }
                    pump(&mut s, &out_tx);
                }
                if let Some(ctx) = repaint.lock().unwrap().as_ref() {
                    ctx.request_repaint();
                }
            }
        });
    }

    // 소리: 전용 스레드가 상태를 20ms 마다 보며 스피커·마이크를 다룸
    {
        let state = state.clone();
        let bin_tx = conn.outgoing_bin.clone();
        let out_tx = out_tx.clone();
        std::thread::Builder::new()
            .name("audio".into())
            .spawn(move || {
                let mut audio = audio::Audio::new();
                if let Some(e) = &audio.error {
                    log::info(e);
                    state.lock().unwrap().notice = e.clone();
                }
                loop {
                    std::thread::sleep(std::time::Duration::from_millis(20));
                    let (sounds, mic_wanted, mic_failed) = {
                        let mut s = state.lock().unwrap();
                        s.tick();
                        let sounds = std::mem::take(&mut s.sounds);
                        (sounds, s.mic_wanted, false)
                    };
                    let _ = mic_failed;
                    for snd in sounds {
                        match snd {
                            state::Sound::Live(b) => audio.play_live(&b),
                            state::Sound::BeepIn => audio.effect(&audio::beep_in()),
                            state::Sound::BeepOk => audio.effect(&audio::beep_ok()),
                            state::Sound::BeepNo => audio.effect(&audio::beep_no()),
                            state::Sound::Stop => audio.stop_playback(),
                            state::Sound::PlayFile(p) => match std::fs::read(&p) {
                                Ok(b) => audio.play_all(&audio::bytes_to_i16(&b)),
                                Err(e) => log::info(&format!("소리 파일 읽기 실패 {p}: {e}")),
                            },
                        }
                    }
                    if mic_wanted && !audio.mic_on() {
                        let tx = bin_tx.clone();
                        let ok = audio.start_mic(Box::new(move |frame| {
                            let _ = tx.try_send(frame); // 밀리면 조각을 버림 (실시간이라 기다리지 않음)
                        }));
                        if !ok {
                            let mut s = state.lock().unwrap();
                            s.mic_failed();
                            pump(&mut s, &out_tx);
                        }
                    } else if !mic_wanted && audio.mic_on() {
                        audio.stop_mic();
                    }
                }
            })
            .expect("audio thread");
    }

    // 자동 업데이트: 켜지고 2분 뒤, 그 뒤 12시간마다 확인. 받아 두면 state.update_ready 에 두고 창이 한가할 때 적용
    {
        let state = state.clone();
        std::thread::Builder::new()
            .name("update".into())
            .spawn(move || {
                std::thread::sleep(std::time::Duration::from_secs(update::FIRST_CHECK_SECS));
                loop {
                    match update::check(&state) {
                        Ok(Some(r)) => {
                            state.lock().unwrap().update_ready = Some((r.path, r.number))
                        }
                        Ok(None) => {}
                        Err(e) => log::info(&format!("업데이트 확인 실패: {e}")),
                    }
                    std::thread::sleep(std::time::Duration::from_secs(update::CHECK_EVERY_SECS));
                }
            })
            .ok();
    }

    if headless {
        log::info("창 없이 연결 점검 모드");
        // --send-file <경로>: 연결되면 그 파일을 지금 상대에게 보냄 (첨부 기능 점검용)
        let args: Vec<String> = std::env::args().collect();
        let mut send_path = args
            .iter()
            .position(|a| a == "--send-file")
            .and_then(|i| args.get(i + 1).cloned());
        loop {
            std::thread::sleep(std::time::Duration::from_millis(300));
            let connected = state.lock().unwrap().connected;
            if connected {
                if let Some(p) = send_path.take() {
                    log::info(&format!("점검: 첨부 보내기 {p}"));
                    files::send_file(state.clone(), std::path::PathBuf::from(p), None, None);
                }
            }
            let notices = {
                let mut s = state.lock().unwrap();
                let n = std::mem::take(&mut s.notices);
                pump(&mut s, &out_tx);
                n
            };
            for n in notices {
                if let state::Notice::FileIn(c) = n {
                    log::info(&format!(
                        "점검: 첨부 받음 {} ({}바이트, 미리보기 {})",
                        c.name, c.size, c.thumb
                    ));
                    let st = state.clone();
                    let dest = files::file_path(&c);
                    files::fetch(
                        st.clone(),
                        "file",
                        c.clone(),
                        dest,
                        Some(Box::new(|p| {
                            log::info(&format!("점검: 원본 내려받음 {}", p.display()))
                        })),
                    );
                    if c.thumb {
                        files::fetch(
                            st,
                            "thumb",
                            c.clone(),
                            files::thumb_path(&c),
                            Some(Box::new(|p| {
                                log::info(&format!("점검: 미리보기 내려받음 {}", p.display()))
                            })),
                        );
                    }
                }
            }
        }
    }

    let opts = eframe::NativeOptions {
        viewport: eframe::egui::ViewportBuilder::default()
            .with_title("우리집")
            .with_inner_size([440.0, 760.0])
            .with_min_inner_size([360.0, 560.0]),
        ..Default::default()
    };
    // 전역 단축키 (설정이 바뀌면 hk_spec_tx 로 새 값을 보냄)
    let (hk_tx, hk_rx) = std::sync::mpsc::channel::<hotkey::HotkeyEvent>();
    let (hk_spec_tx, hk_spec_rx) = std::sync::mpsc::channel::<String>();
    let initial_hotkey = state.lock().unwrap().cfg.hotkey.clone();
    hotkey::start(hk_spec_rx, initial_hotkey, hk_tx);
    let inputs = app::Inputs {
        requests: guard.map(|g| g.requests),
        hotkey_rx: hk_rx,
        hotkey_tx: hk_spec_tx,
        start_minimized,
    };
    let st = state.clone();
    let pump_box: Box<dyn Fn(&mut state::State) + Send> = Box::new(move |s| pump(s, &out_tx));
    let cfg_tx = conn.cfg_tx.clone();
    let reconfigure: app::Reconfigure = Box::new(move |c| {
        let _ = cfg_tx.send(Arc::new(c));
    });
    // 그리기: Windows 는 wgpu(DirectX 12 — 그래픽 드라이버가 없어도 소프트웨어로 됨), 그 외는 OpenGL.
    // 하나가 실패하면 다른 쪽으로 한 번 더 시도
    let first = if cfg!(windows) {
        eframe::Renderer::Wgpu
    } else {
        eframe::Renderer::Glow
    };
    let second = if cfg!(windows) {
        eframe::Renderer::Glow
    } else {
        eframe::Renderer::Wgpu
    };
    let app_cell = Arc::new(Mutex::new(Some((st, pump_box, inputs, reconfigure))));
    let mut last_err = String::new();
    for renderer in [first, second] {
        if app_cell.lock().unwrap().is_none() {
            break; // 창이 떴다가 정상 종료됨
        }
        let o = eframe::NativeOptions {
            renderer,
            ..opts.clone()
        };
        let cell = app_cell.clone();
        let rp = repaint.clone();
        let r = eframe::run_native(
            "woorizip",
            o,
            Box::new(move |cc| {
                *rp.lock().unwrap() = Some(cc.egui_ctx.clone());
                let (st, pump_box, inputs, reconfigure) =
                    cell.lock().unwrap().take().expect("app once");
                Ok(Box::new(app::App::new(
                    cc,
                    st,
                    pump_box,
                    inputs,
                    reconfigure,
                )))
            }),
        );
        match r {
            Ok(()) => {
                last_err.clear();
                break;
            }
            Err(e) => {
                last_err = format!("{e}");
                log::info(&format!("창 열기 실패({renderer:?}): {e}"));
            }
        }
    }
    if !last_err.is_empty() {
        platform::message_box(&format!("창을 열 수 없어요:\n{last_err}"), true);
        anyhow::bail!("창 열기 실패: {last_err}");
    }
    conn.stop();
    Ok(())
}

/// 상태에 쌓인 보낼 것(outgoing)을 통신 쪽으로 넘김
fn pump(s: &mut state::State, out_tx: &tokio::sync::mpsc::Sender<serde_json::Value>) {
    for v in s.outgoing.drain(..) {
        if out_tx.try_send(v).is_err() {
            log::info("보내기 대기열이 가득 찼어요");
        }
    }
}
