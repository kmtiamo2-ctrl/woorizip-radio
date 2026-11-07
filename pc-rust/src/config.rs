//! 설정 — Python판과 같은 `config.json` (같은 키 이름). 없는 항목은 우리 집 기본값.
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

pub const APP_NAME: &str = "WoorizipRadio";
pub const DEFAULT_GROUPS: &str = "아이들=첫째,둘째,셋째;부모님=엄마,아빠";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Config {
    pub host: String,
    pub out_host: String,
    pub name: String,
    pub key: String,
    pub hotkey: String,
    pub quick: String,
    pub target: String,
    pub autostart: bool,
    pub groups: String,
    pub device: Option<String>,
}

impl Default for Config {
    fn default() -> Self {
        Config {
            host: "192.168.0.6".into(),
            out_host: "100.83.179.121".into(),
            name: String::new(),
            key: "105403".into(),
            hotkey: String::new(),
            quick: "네,아니요,알겠어요,지금 갈게요,조금 있다가요,지금은 못 해요".into(),
            target: "all".into(),
            autostart: true,
            groups: DEFAULT_GROUPS.into(),
            device: None,
        }
    }
}

/// `%APPDATA%\WoorizipRadio` (없으면 홈 폴더 아래). Python판과 같은 자리
pub fn data_dir() -> PathBuf {
    let base = std::env::var_os("APPDATA")
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(PathBuf::from))
        .or_else(|| std::env::var_os("USERPROFILE").map(PathBuf::from))
        .unwrap_or_else(|| PathBuf::from("."));
    base.join(APP_NAME)
}

impl Config {
    pub fn path() -> PathBuf {
        data_dir().join("config.json")
    }

    /// 파일을 읽고, 예전 버전이 빈 문자열로 저장한 항목은 기본값으로 되돌림 (Python판과 같은 규칙)
    pub fn load() -> Config {
        let text = std::fs::read_to_string(Self::path()).unwrap_or_default();
        Self::parse(&text)
    }

    pub fn parse(text: &str) -> Config {
        let d = Config::default();
        let mut c: Config = serde_json::from_str(text).unwrap_or_else(|_| d.clone());
        if c.host.is_empty() {
            c.host = d.host.clone();
        }
        if c.out_host.is_empty() && !d.out_host.is_empty() {
            c.out_host = d.out_host.clone();
        }
        if c.key.is_empty() {
            c.key = d.key.clone();
        }
        if c.quick.is_empty() {
            c.quick = d.quick.clone();
        }
        if c.target.is_empty() {
            c.target = d.target.clone();
        }
        if c.groups.is_empty() {
            c.groups = d.groups.clone();
        }
        c
    }

    pub fn save(&self) -> anyhow::Result<()> {
        let dir = data_dir();
        std::fs::create_dir_all(&dir)?;
        let tmp = dir.join("config.json.tmp");
        std::fs::write(&tmp, serde_json::to_string_pretty(self)?)?;
        std::fs::rename(tmp, Self::path())?; // 쓰다 꺼져도 설정 파일이 깨지지 않게 통째로 바꿔 끼움
        Ok(())
    }

    pub fn configured(&self) -> bool {
        !self.name.is_empty() && !self.host.is_empty() && !self.key.is_empty()
    }

    /// 기기 이름: 설정에 있으면 그것, 없으면 "pc-컴퓨터이름" (같은 기기 재접속 시 서버가 옛 연결을 정리하는 열쇠)
    pub fn device_id(&self) -> String {
        if let Some(d) = &self.device {
            if !d.is_empty() {
                return d.clone();
            }
        }
        let h = hostname::get()
            .ok()
            .and_then(|s| s.into_string().ok())
            .unwrap_or_else(|| "pc".into());
        format!("pc-{h}")
    }
}

/// `'192.168.0.10'`, `'192.168.0.10:8765'`, `'wss://xxx.synology.me'` → WebSocket 주소 (기본 포트 8765, 경로 /)
pub fn ws_url(addr: &str) -> String {
    let mut a = addr.trim().to_string();
    if !a.contains("://") {
        a = format!("ws://{a}");
    }
    a = a
        .replacen("https://", "wss://", 1)
        .replacen("http://", "ws://", 1);
    let (scheme, rest) = a.split_once("://").unwrap_or(("ws", &a));
    let (hostport, path) = match rest.find('/') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, "/"),
    };
    let hostport = if hostport
        .rsplit_once(':')
        .map(|(_, p)| p.chars().all(|c| c.is_ascii_digit()) && !p.is_empty())
        .unwrap_or(false)
    {
        hostport.to_string()
    } else if scheme == "wss" {
        format!("{hostport}:443")
    } else {
        format!("{hostport}:8765")
    };
    format!("{scheme}://{hostport}{path}")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn url_forms() {
        assert_eq!(ws_url("192.168.0.6"), "ws://192.168.0.6:8765/");
        assert_eq!(ws_url("192.168.0.6:9000"), "ws://192.168.0.6:9000/");
        assert_eq!(
            ws_url("wss://home.synology.me"),
            "wss://home.synology.me:443/"
        );
        assert_eq!(ws_url("http://nas/radio"), "ws://nas:8765/radio");
    }

    #[test]
    fn config_defaults_and_empty_strings() {
        let c = Config::parse(r#"{"host": "", "name": "아빠", "key": "", "autostart": false}"#);
        assert_eq!(c.host, "192.168.0.6");
        assert_eq!(c.key, "105403");
        assert_eq!(c.name, "아빠");
        assert!(!c.autostart);
        assert!(c.configured());
        let d = Config::parse("not json");
        assert!(!d.configured());
        assert!(d.device_id().starts_with("pc-"));
    }
}
