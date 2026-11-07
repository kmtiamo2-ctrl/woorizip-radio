//! NAS 연결: 집 주소 → (안 되면) 밖 주소 순서로 붙고, 끊기면 2초부터 2배씩(최대 20초) 쉬고 다시.
//! 30초마다 ping, 120초 동안 아무 응답이 없으면 끊긴 것으로 보고 다시 연결 (Python판과 같은 규칙).
use crate::config::{ws_url, Config};
use crate::proto::{Hello, Welcome};
use futures_util::{SinkExt, StreamExt};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite::Message;

#[derive(Debug)]
pub enum Event {
    State {
        connected: bool,
        where_: Option<String>,
        status: String,
    },
    Welcome(Welcome),
    Message(serde_json::Value),
    /// 소리 조각 (첫 바이트: 1=실시간, 2=보관 음성)
    Binary(Vec<u8>),
}

pub struct Connection {
    stop: tokio::sync::watch::Sender<bool>,
    pub outgoing: mpsc::Sender<serde_json::Value>,
    pub outgoing_bin: mpsc::Sender<Vec<u8>>,
}

impl Connection {
    pub fn spawn(cfg: Arc<Config>, events: mpsc::Sender<Event>) -> Connection {
        let (stop, stop_rx) = tokio::sync::watch::channel(false);
        let (out_tx, out_rx) = mpsc::channel::<serde_json::Value>(64);
        let (bin_tx, bin_rx) = mpsc::channel::<Vec<u8>>(64);
        tokio::spawn(run(cfg, events, out_rx, bin_rx, stop_rx));
        Connection {
            stop,
            outgoing: out_tx,
            outgoing_bin: bin_tx,
        }
    }

    pub fn stop(&self) {
        let _ = self.stop.send(true);
    }
}

async fn run(
    cfg: Arc<Config>,
    events: mpsc::Sender<Event>,
    mut out_rx: mpsc::Receiver<serde_json::Value>,
    mut bin_rx: mpsc::Receiver<Vec<u8>>,
    mut stop: tokio::sync::watch::Receiver<bool>,
) {
    let mut backoff = Duration::from_secs(2);
    loop {
        if *stop.borrow() {
            return;
        }
        if !cfg.configured() {
            let _ = events
                .send(Event::State {
                    connected: false,
                    where_: None,
                    status: "설정에서 NAS 주소·이름·암호를 입력하세요".into(),
                })
                .await;
            tokio::select! { _ = tokio::time::sleep(Duration::from_secs(5)) => {}, _ = stop.changed() => return }
            continue;
        }
        let mut attempts = vec![("home", cfg.host.clone())];
        if !cfg.out_host.trim().is_empty() {
            attempts.push(("out", cfg.out_host.clone()));
        }
        let mut last_err = String::from("NAS 서버에 연결할 수 없어요");
        let mut auth_failed = false;
        let mut was_connected = false;
        for (where_, addr) in attempts {
            if *stop.borrow() {
                return;
            }
            let _ = events
                .send(Event::State {
                    connected: false,
                    where_: None,
                    status: if where_ == "home" {
                        "집 주소로 연결 중…".into()
                    } else {
                        "밖 주소로 연결 중…".into()
                    },
                })
                .await;
            match session(
                &cfg,
                where_,
                &addr,
                &events,
                &mut out_rx,
                &mut bin_rx,
                &mut stop,
            )
            .await
            {
                Ok(()) => {
                    backoff = Duration::from_secs(2);
                    was_connected = true;
                }
                Err(SessionEnd::Auth(msg)) => {
                    last_err = msg;
                    auth_failed = true;
                    break;
                } // 암호·이름이 틀리면 밖 주소로 더 시도해도 소용없음
                Err(SessionEnd::Net(msg)) => {
                    crate::log::info(&format!("{where_} 연결 실패: {msg}"));
                }
                Err(SessionEnd::Stopped) => return,
            }
            if was_connected {
                break;
            } // 한 번 붙었다 끊긴 것: 바로 처음(집)부터 다시
        }
        let _ = events
            .send(Event::State {
                connected: false,
                where_: None,
                status: last_err,
            })
            .await;
        if auth_failed {
            backoff = Duration::from_secs(20);
        } // 암호가 틀린 건 기다려도 안 바뀜 — 서버를 괴롭히지 않게 천천히
        tokio::select! { _ = tokio::time::sleep(backoff) => {}, _ = stop.changed() => return }
        backoff = (backoff * 2).min(Duration::from_secs(20));
    }
}

enum SessionEnd {
    Auth(String),
    Net(String),
    Stopped,
}

/// 한 번의 접속: 손잡이(hello) → 환영 → 메시지 주고받기. 정상적으로 붙었다가 끊기면 Ok, 붙지도 못하면 Err
async fn session(
    cfg: &Config,
    where_: &str,
    addr: &str,
    events: &mpsc::Sender<Event>,
    out_rx: &mut mpsc::Receiver<serde_json::Value>,
    bin_rx: &mut mpsc::Receiver<Vec<u8>>,
    stop: &mut tokio::sync::watch::Receiver<bool>,
) -> Result<(), SessionEnd> {
    let url = ws_url(addr);
    let connect = tokio_tungstenite::connect_async(&url);
    let (ws, _) = match tokio::time::timeout(Duration::from_secs(5), connect).await {
        Ok(Ok(x)) => x,
        Ok(Err(e)) => return Err(SessionEnd::Net(format!("{e}"))),
        Err(_) => return Err(SessionEnd::Net("5초 안에 응답 없음".into())),
    };
    let (mut sink, mut stream) = ws.split();
    let device = cfg.device_id();
    let hello = Hello {
        kind: "hello",
        name: &cfg.name,
        key: &cfg.key,
        device: &device,
        where_,
    };
    sink.send(Message::Text(serde_json::to_string(&hello).unwrap()))
        .await
        .map_err(|e| SessionEnd::Net(format!("{e}")))?;

    let mut connected = false;
    let mut last_rx = tokio::time::Instant::now();
    let mut ping = tokio::time::interval(Duration::from_secs(30));
    ping.tick().await; // 첫 틱은 바로 오므로 버림
    loop {
        tokio::select! {
            _ = stop.changed() => { let _ = sink.send(Message::Close(None)).await; return Err(SessionEnd::Stopped); }
            _ = ping.tick() => {
                if last_rx.elapsed() > Duration::from_secs(120) {
                    crate::log::info("연결 응답 없음 2분 → 다시 연결");
                    return if connected { Ok(()) } else { Err(SessionEnd::Net("응답 없음".into())) };
                }
                if sink.send(Message::Ping(Vec::new())).await.is_err() { return if connected { Ok(()) } else { Err(SessionEnd::Net("보내기 실패".into())) }; }
            }
            Some(v) = out_rx.recv() => {
                if sink.send(Message::Text(v.to_string())).await.is_err() { return if connected { Ok(()) } else { Err(SessionEnd::Net("보내기 실패".into())) }; }
            }
            Some(b) = bin_rx.recv() => {
                if sink.send(Message::Binary(b)).await.is_err() { return if connected { Ok(()) } else { Err(SessionEnd::Net("보내기 실패".into())) }; }
            }
            m = stream.next() => {
                let m = match m { Some(Ok(m)) => m, _ => return if connected { Ok(()) } else { Err(SessionEnd::Net("연결이 끊겼어요".into())) } };
                last_rx = tokio::time::Instant::now();
                let text = match m {
                    Message::Text(t) => t,
                    Message::Binary(b) => { let _ = events.send(Event::Binary(b)).await; continue; }
                    Message::Ping(p) => { let _ = sink.send(Message::Pong(p)).await; continue; }
                    Message::Close(_) => return if connected { Ok(()) } else { Err(SessionEnd::Net("서버가 연결을 닫았어요".into())) },
                    _ => continue,
                };
                let v: serde_json::Value = match serde_json::from_str(&text) { Ok(v) => v, Err(_) => continue };
                match v.get("type").and_then(|t| t.as_str()).unwrap_or("") {
                    "error" => {
                        let reason = v.get("reason").and_then(|r| r.as_str()).unwrap_or("");
                        let msg = match reason {
                            "auth" => "가족 암호가 틀려요".to_string(),
                            "unknown_member" => "이름이 가족 명단에 없어요".to_string(),
                            other => format!("서버 오류: {other}"),
                        };
                        return Err(SessionEnd::Auth(msg));
                    }
                    "welcome" => {
                        match serde_json::from_value::<Welcome>(v.clone()) {
                            Ok(w) => {
                                connected = true;
                                let _ = events.send(Event::State { connected: true, where_: Some(w.where_.clone()), status: "연결됨".into() }).await;
                                let _ = events.send(Event::Welcome(w)).await;
                            }
                            Err(e) => return Err(SessionEnd::Net(format!("환영 메시지 해석 실패: {e}"))),
                        }
                    }
                    _ => { let _ = events.send(Event::Message(v)).await; }
                }
            }
        }
    }
}
