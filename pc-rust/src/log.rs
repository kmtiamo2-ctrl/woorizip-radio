//! 기록 파일: `%APPDATA%\WoorizipRadio\log_rust.txt` (Python판 log.txt 와 따로). 2MB를 넘으면 .1 로 밀어 둠.
use std::io::Write;
use std::sync::Mutex;

static FILE: Mutex<Option<std::fs::File>> = Mutex::new(None);

pub fn init() {
    let dir = crate::config::data_dir();
    let _ = std::fs::create_dir_all(&dir);
    let path = dir.join("log_rust.txt");
    if let Ok(m) = std::fs::metadata(&path) {
        if m.len() > 2 * 1024 * 1024 {
            let _ = std::fs::rename(&path, dir.join("log_rust.1.txt"));
        }
    }
    if let Ok(f) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&path)
    {
        *FILE.lock().unwrap() = Some(f);
    }
}

pub fn info(msg: &str) {
    let line = format!(
        "{} {}\n",
        chrono::Local::now().format("%m-%d %H:%M:%S"),
        msg
    );
    eprint!("{line}");
    if let Some(f) = FILE.lock().unwrap().as_mut() {
        let _ = f.write_all(line.as_bytes());
    }
}
