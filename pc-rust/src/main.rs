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
mod log;
mod net;
mod proto;
mod state;

use std::sync::{Arc, Mutex};

fn main() -> anyhow::Result<()> {
    log::init();
    log::info(&format!(
        "우리집 PC(Rust) 시작 v{}",
        env!("CARGO_PKG_VERSION")
    ));
    let cfg = config::Config::load();
    if !cfg.configured() {
        log::info("설정에 NAS 주소·이름·암호가 없어요 — Python판 설정 창에서 먼저 저장해 주세요 (Rust판 설정 창은 5일차)");
    }
    let headless = std::env::args().any(|a| a == "--headless"); // 창 없이 연결만 (점검용)
    let state: app::Shared = Arc::new(Mutex::new(state::State::new(cfg.clone())));

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

    if headless {
        log::info("창 없이 연결 점검 모드");
        loop {
            std::thread::sleep(std::time::Duration::from_secs(1));
        }
    }

    let opts = eframe::NativeOptions {
        viewport: eframe::egui::ViewportBuilder::default()
            .with_title("우리집")
            .with_inner_size([440.0, 760.0])
            .with_min_inner_size([360.0, 560.0]),
        ..Default::default()
    };
    let st = state.clone();
    let pump_box: Box<dyn Fn(&mut state::State) + Send> = Box::new(move |s| pump(s, &out_tx));
    eframe::run_native(
        "woorizip",
        opts,
        Box::new(move |cc| {
            *repaint.lock().unwrap() = Some(cc.egui_ctx.clone());
            Ok(Box::new(app::App::new(cc, st, pump_box)))
        }),
    )
    .map_err(|e| anyhow::anyhow!("창 열기 실패: {e}"))?;
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
