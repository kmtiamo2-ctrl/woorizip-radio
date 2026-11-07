//! 첨부파일(사진·파일): NAS에 HTTP로 올리고(POST /file) 내려받음(GET /file/<id>, /thumb/<id>).
//! 올릴 때 사진이면 작은 미리보기(JPEG, 360px)를 앞에 붙여 보내 받는 쪽이 원본을 누를 때만 받게 함 (데이터 절약).
//! 받은 파일은 `%LOCALAPPDATA%\WoorizipRadio\files\` 에 둠 (Python판과 같은 자리).
use crate::app::Shared;
use crate::state::Clip;
use std::collections::HashSet;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Default)]
pub struct Upload {
    pub name: String,
    pub done: u64,
    pub total: u64,
    pub error: Option<String>,
    pub finished: bool,
}

pub fn files_dir() -> PathBuf {
    let base = std::env::var_os("LOCALAPPDATA")
        .map(PathBuf::from)
        .unwrap_or_else(crate::config::data_dir);
    let d = base.join(crate::platform::APP_NAME).join("files");
    let _ = std::fs::create_dir_all(&d);
    d
}

pub fn is_image_name(name: &str) -> bool {
    let n = name.to_lowercase();
    [".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp"]
        .iter()
        .any(|e| n.ends_with(e))
}

pub fn mime_of(name: &str) -> &'static str {
    let n = name.to_lowercase();
    let ext = n.rsplit('.').next().unwrap_or("");
    match ext {
        "jpg" | "jpeg" => "image/jpeg",
        "png" => "image/png",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "bmp" => "image/bmp",
        "mp4" => "video/mp4",
        "mp3" => "audio/mpeg",
        "pdf" => "application/pdf",
        "txt" => "text/plain",
        "zip" => "application/zip",
        _ => "application/octet-stream",
    }
}

/// 사진이면 작은 JPEG 미리보기 바이트 (가로세로 최대 max_px). 아니면 None
pub fn make_thumb(path: &Path, max_px: u32) -> Option<Vec<u8>> {
    let img = image::open(path).ok()?;
    let t = img.thumbnail(max_px, max_px).to_rgb8();
    let mut out = std::io::Cursor::new(Vec::new());
    let mut enc = image::codecs::jpeg::JpegEncoder::new_with_quality(&mut out, 78);
    enc.encode_image(&t).ok()?;
    Some(out.into_inner())
}

pub fn thumb_path(clip: &Clip) -> PathBuf {
    files_dir().join(format!("{}.thumb.jpg", clip.fid))
}

pub fn file_path(clip: &Clip) -> PathBuf {
    let name = if clip.name.is_empty() {
        "file".to_string()
    } else {
        clip.name.clone()
    };
    let (base, ext) = match name.rfind('.') {
        Some(i) if i > 0 => (&name[..i], &name[i..]),
        _ => (name.as_str(), ""),
    };
    let base: String = base.chars().take(60).collect();
    let ext: String = ext.chars().take(10).collect();
    let fid8: String = clip.fid.chars().take(8).collect();
    files_dir().join(format!("{base}_{fid8}{ext}"))
}

/// 지금 연결된 주소의 HTTP 바탕 (`http://192.168.0.6:8765`)
pub fn http_base(state: &Shared) -> String {
    let s = state.lock().unwrap();
    let addr = if s.where_.as_deref() == Some("out") {
        s.cfg.out_host.clone()
    } else {
        s.cfg.host.clone()
    };
    crate::config::ws_url(&addr)
        .replacen("wss://", "https://", 1)
        .replacen("ws://", "http://", 1)
        .trim_end_matches('/')
        .to_string()
}

/// 파일 올리기 (뒤 스레드). 진행 상황은 state.uploads 에
pub fn send_file(
    state: Shared,
    path: PathBuf,
    to: Option<String>,
    display_name: Option<String>,
) -> bool {
    let (connected, key, me, target) = {
        let s = state.lock().unwrap();
        let t = if s.cfg.target == "all" {
            "all".to_string()
        } else {
            s.target_list().join(",")
        };
        (s.connected, s.cfg.key.clone(), s.cfg.name.clone(), t)
    };
    if !connected {
        state.lock().unwrap().notice = "지금 연결이 없어서 보낼 수 없어요".into();
        return false;
    }
    let Ok(meta) = std::fs::metadata(&path) else {
        state.lock().unwrap().notice = "파일을 찾을 수 없어요".into();
        return false;
    };
    if !meta.is_file() {
        state.lock().unwrap().notice = "폴더는 보낼 수 없어요".into();
        return false;
    }
    let name = display_name.unwrap_or_else(|| {
        path.file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_else(|| "file".into())
    });
    let size = meta.len();
    {
        let mut s = state.lock().unwrap();
        s.uploads.insert(
            path.to_string_lossy().to_string(),
            Upload {
                name: name.clone(),
                total: size,
                ..Default::default()
            },
        );
    }
    let base = http_base(&state);
    let key_path = path.to_string_lossy().to_string();
    let to_q = to.unwrap_or(target);
    std::thread::Builder::new()
        .name("upload".into())
        .spawn(move || {
            let result = (|| -> Result<serde_json::Value, String> {
                let thumb = if is_image_name(&name) {
                    make_thumb(&path, 360)
                } else {
                    None
                };
                let thumb_len = thumb.as_ref().map(|t| t.len()).unwrap_or(0);
                let name100: String = name.chars().take(100).collect();
                let url = format!(
                    "{base}/file?from={}&to={}&name={}&mime={}",
                    urlenc(&me),
                    urlenc(&to_q),
                    urlenc(&name100),
                    urlenc(mime_of(&name))
                );
                let f = std::fs::File::open(&path).map_err(|e| e.to_string())?;
                let st = state.clone();
                let kp = key_path.clone();
                let reader = Progress {
                    inner: thumb
                        .map(std::io::Cursor::new)
                        .into_iter()
                        .map(|c| Box::new(c) as Box<dyn Read + Send>)
                        .chain(std::iter::once(Box::new(f) as Box<dyn Read + Send>))
                        .collect(),
                    idx: 0,
                    on: Box::new(move |n| {
                        if let Some(u) = st.lock().unwrap().uploads.get_mut(&kp) {
                            u.done += n as u64;
                        }
                    }),
                };
                let total = size + thumb_len as u64;
                let resp = ureq::post(&url)
                    .set("X-Key", &key)
                    .set("X-Thumb-Length", &thumb_len.to_string())
                    .set("Content-Type", "application/octet-stream")
                    .set("Content-Length", &total.to_string())
                    .timeout(std::time::Duration::from_secs(3600))
                    .send(reader);
                match resp {
                    Ok(r) => {
                        let body = r.into_string().map_err(|e| e.to_string())?;
                        serde_json::from_str(&body).map_err(|e| e.to_string())
                    }
                    Err(ureq::Error::Status(code, r)) => {
                        let body = r.into_string().unwrap_or_default();
                        let err = serde_json::from_str::<serde_json::Value>(&body)
                            .ok()
                            .and_then(|v| v.get("error").and_then(|e| e.as_str()).map(String::from))
                            .unwrap_or_else(|| code.to_string());
                        Err(match err.as_str() {
                            "too_large" => "파일이 너무 커요 (최대 4GB)".into(),
                            "disk_full" => "NAS 저장 공간이 부족해요".into(),
                            "no_target" => "받을 사람이 없어요".into(),
                            "auth" => "암호가 달라요".into(),
                            other => format!("보내지 못했어요 ({other})"),
                        })
                    }
                    Err(e) => Err(format!("보내지 못했어요 ({e})")),
                }
            })();
            let mut s = state.lock().unwrap();
            match result {
                Ok(m) => {
                    s.on_file_sent(&m);
                    s.notice = "보냈어요".into();
                    if let Some(u) = s.uploads.get_mut(&key_path) {
                        u.finished = true;
                    }
                    crate::log::info(&format!(
                        "첨부 보냄: {name} ({:.1}MB)",
                        size as f64 / 1048576.0
                    ));
                }
                Err(e) => {
                    s.notice = format!("📎 {name}: {e}");
                    if let Some(u) = s.uploads.get_mut(&key_path) {
                        u.finished = true;
                        u.error = Some(e.clone());
                    }
                    crate::log::info(&format!("첨부 보내기 실패: {name} {e}"));
                }
            }
        })
        .ok();
    true
}

/// 여러 조각(미리보기 + 파일)을 이어서 읽으며 진행 상황을 알리는 읽기 도구
struct Progress {
    inner: Vec<Box<dyn Read + Send>>,
    idx: usize,
    on: Box<dyn FnMut(usize) + Send>,
}

impl Read for Progress {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        while self.idx < self.inner.len() {
            let n = self.inner[self.idx].read(buf)?;
            if n > 0 {
                (self.on)(n);
                return Ok(n);
            }
            self.idx += 1;
        }
        Ok(0)
    }
}

fn urlenc(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char)
            }
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

/// NAS에서 미리보기(thumb) 또는 원본(file)을 내려받음 (뒤 스레드, 같은 것은 한 번에 하나만). 끝나면 on_done(경로)
pub fn fetch(
    state: Shared,
    kind: &'static str,
    clip: Clip,
    dest: PathBuf,
    on_done: Option<Box<dyn FnOnce(PathBuf) + Send>>,
) {
    let key = (kind.to_string(), clip.fid.clone());
    {
        let mut s = state.lock().unwrap();
        if clip.expired || s.fetching.contains(&key) {
            return;
        }
        s.fetching.insert(key.clone());
    }
    let base = http_base(&state);
    let fkey = state.lock().unwrap().cfg.key.clone();
    std::thread::Builder::new()
        .name("download".into())
        .spawn(move || {
            let tmp = dest.with_extension(format!(
                "{}.part",
                dest.extension()
                    .map(|e| e.to_string_lossy().to_string())
                    .unwrap_or_default()
            ));
            let result = (|| -> Result<(), String> {
                let url = format!("{base}/{kind}/{}", clip.fid);
                let r = match ureq::get(&url)
                    .set("X-Key", &fkey)
                    .timeout(std::time::Duration::from_secs(3600))
                    .call()
                {
                    Ok(r) => r,
                    Err(ureq::Error::Status(410, _)) => return Err("expired".into()),
                    Err(ureq::Error::Status(c, _)) => return Err(format!("HTTP {c}")),
                    Err(e) => return Err(e.to_string()),
                };
                let total: u64 = r
                    .header("Content-Length")
                    .and_then(|v| v.parse().ok())
                    .unwrap_or(0);
                let mut reader = r.into_reader();
                let mut f = std::fs::File::create(&tmp).map_err(|e| e.to_string())?;
                let mut buf = vec![0u8; 256 * 1024];
                let mut got = 0u64;
                loop {
                    let n = reader.read(&mut buf).map_err(|e| e.to_string())?;
                    if n == 0 {
                        break;
                    }
                    f.write_all(&buf[..n]).map_err(|e| e.to_string())?;
                    got += n as u64;
                    if kind == "file" && total > 0 {
                        state
                            .lock()
                            .unwrap()
                            .downloads
                            .insert(clip.fid.clone(), got as f32 / total as f32);
                    }
                }
                drop(f);
                std::fs::rename(&tmp, &dest).map_err(|e| e.to_string())?;
                Ok(())
            })();
            let _ = std::fs::remove_file(&tmp);
            {
                let mut s = state.lock().unwrap();
                s.fetching.remove(&key);
                s.downloads.remove(&clip.fid);
                match &result {
                    Ok(()) => {}
                    Err(e) if e == "expired" => s.mark_expired(&clip.fid),
                    Err(e) => {
                        s.notice = format!("📎 {} 내려받기 실패", clip.name);
                        crate::log::info(&format!("첨부 받기 실패: {} {e}", clip.name));
                    }
                }
            }
            if result.is_ok() {
                if let Some(cb) = on_done {
                    cb(dest);
                }
            }
        })
        .ok();
}

/// 원본을 내려받아(없으면) 기본 프로그램으로 열기
pub fn open_file(state: Shared, clip: Clip) {
    let dest = file_path(&clip);
    if dest.exists() {
        start_file(&dest);
        return;
    }
    fetch(
        state,
        "file",
        clip,
        dest,
        Some(Box::new(|p| start_file(&p))),
    );
}

pub fn start_file(path: &Path) {
    if let Err(e) = open::that_detached(path) {
        crate::log::info(&format!("파일 열기 실패: {e}"));
    }
}

/// 다른 이름으로 저장: 원본이 있으면 복사, 없으면 받아서 복사
pub fn save_as(state: Shared, clip: Clip, target: PathBuf) {
    let src = file_path(&clip);
    let copy = move |p: PathBuf| {
        if let Err(e) = std::fs::copy(&p, &target) {
            crate::log::info(&format!("저장 실패: {e}"));
        }
    };
    if src.exists() {
        copy(src);
    } else {
        fetch(state, "file", clip, src, Some(Box::new(copy)));
    }
}

pub type FetchKey = (String, String);
pub type Fetching = HashSet<FetchKey>;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_and_paths() {
        assert!(is_image_name("A.JPG"));
        assert!(!is_image_name("a.pdf"));
        assert_eq!(mime_of("x.png"), "image/png");
        assert_eq!(
            urlenc("엄마,첫째"),
            "%EC%97%84%EB%A7%88%2C%EC%B2%AB%EC%A7%B8"
        );
        let c = Clip {
            fid: "abcdef0123456789".into(),
            name: "사진.jpg".into(),
            ..Clip::empty()
        };
        assert!(file_path(&c)
            .to_string_lossy()
            .ends_with("사진_abcdef01.jpg"));
        assert!(thumb_path(&c)
            .to_string_lossy()
            .ends_with("abcdef0123456789.thumb.jpg"));
    }

    #[test]
    fn thumb_from_png() {
        let dir = std::env::temp_dir().join(format!("wz-thumb-{}", std::process::id()));
        let _ = std::fs::create_dir_all(&dir);
        let p = dir.join("t.png");
        let img = image::RgbImage::from_fn(800, 600, |x, _| image::Rgb([(x % 256) as u8, 80, 120]));
        img.save(&p).unwrap();
        let t = make_thumb(&p, 360).expect("thumb");
        let back = image::load_from_memory(&t).unwrap();
        assert!(back.width() <= 360 && back.height() <= 360);
        assert!(make_thumb(Path::new("/nonexistent.png"), 360).is_none());
    }
}
