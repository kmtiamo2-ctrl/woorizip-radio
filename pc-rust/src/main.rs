//! 우리집 PC 프로그램 (Rust판) — 1일차: 뼈대·설정 읽기·NAS 연결/재연결(집→밖)·기록.
//!
//! 설정 파일과 기록 파일은 Python판과 같은 자리(`%APPDATA%\WoorizipRadio\`)를 그대로 써서,
//! 바꿔 끼워도 가족 설정이 유지됩니다.

#![allow(dead_code)] // 1일차: 다음 날들에 쓸 함수·필드를 미리 둠

mod config;
mod log;
mod net;
mod proto;

use std::sync::Arc;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    log::init();
    log::info(&format!(
        "우리집 PC(Rust) 시작 v{}",
        env!("CARGO_PKG_VERSION")
    ));
    let cfg = Arc::new(config::Config::load());
    if !cfg.configured() {
        log::info("설정에 NAS 주소·이름·암호가 없어요 — Python판 설정 창에서 먼저 저장해 주세요 (Rust판 설정 창은 다음 단계)");
    }
    // 서버에서 오는 메시지를 받는 쪽. 1일차는 기록만 남기고, 화면·소리는 다음 날들에 붙임
    let (tx, mut rx) = tokio::sync::mpsc::channel::<net::Event>(256);
    let conn = net::Connection::spawn(cfg.clone(), tx);
    while let Some(ev) = rx.recv().await {
        match ev {
            net::Event::State {
                connected,
                where_,
                status,
            } => {
                log::info(&format!(
                    "상태: {} {}",
                    if connected { "연결됨" } else { "끊김" },
                    status
                ));
                if let Some(w) = where_ {
                    log::info(&format!("위치: {}", if w == "home" { "집" } else { "밖" }));
                }
            }
            net::Event::Welcome(w) => log::info(&format!(
                "환영: 나={} 가족={:?} 이름표={:?}",
                w.you, w.members, w.names
            )),
            net::Event::Message(v) => {
                let t = v.get("type").and_then(|x| x.as_str()).unwrap_or("?");
                log::info(&format!("받음: {}", t));
            }
        }
    }
    conn.stop();
    Ok(())
}
