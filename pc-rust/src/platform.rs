//! 운영체제와 닿는 부분: 하나만 실행(뮤텍스 + 127.0.0.1:47651 "창 열어 줘" 통로), Windows 시작 시 자동 실행(레지스트리 Run).
//! Python판과 같은 뮤텍스 이름·포트를 써서, 두 판이 같이 깔려 있어도 서로를 알아봄 (6일차 교체 때 씀).
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::mpsc;

pub const GUARD_PORT: u16 = 47651;
pub const MUTEX_NAME: &str = "Local\\WoorizipRadio_single_instance";
pub const RUN_KEY: &str = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
pub const APP_NAME: &str = "WoorizipRadio";

/// 두 번째 실행이 보낸 요청
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Request {
    Show,
    Quit,
}

pub struct Guard {
    pub requests: mpsc::Receiver<Request>,
    #[cfg(windows)]
    _mutex: isize,
}

/// 하나만 돌게 함. 이미 실행 중이면 그쪽에 '창 열어 줘'를 보내고 None
pub fn single_instance() -> Option<Guard> {
    #[cfg(windows)]
    let (mutex, already) = create_mutex();
    #[cfg(not(windows))]
    let already = false;
    let listener = if already {
        None
    } else {
        TcpListener::bind(("127.0.0.1", GUARD_PORT)).ok()
    };
    #[cfg(not(windows))]
    let already = already || listener.is_none();
    if already {
        send_request("show");
        return None;
    }
    let (tx, rx) = mpsc::channel();
    if let Some(l) = listener {
        std::thread::Builder::new()
            .name("guard".into())
            .spawn(move || {
                for conn in l.incoming().flatten() {
                    let mut c = conn;
                    let _ = c.set_read_timeout(Some(std::time::Duration::from_secs(1)));
                    let mut buf = [0u8; 16];
                    let n = c.read(&mut buf).unwrap_or(0);
                    let s = String::from_utf8_lossy(&buf[..n]);
                    if s.starts_with("show") {
                        let _ = tx.send(Request::Show);
                    } else if s.starts_with("quit") {
                        let _ = tx.send(Request::Quit);
                    }
                }
            })
            .ok();
    }
    Some(Guard {
        requests: rx,
        #[cfg(windows)]
        _mutex: mutex,
    })
}

/// 실행 중인 프로그램(이 판이든 Python판이든)에 짧은 요청 보내기
pub fn send_request(what: &str) -> bool {
    match TcpStream::connect_timeout(
        &std::net::SocketAddr::from(([127, 0, 0, 1], GUARD_PORT)),
        std::time::Duration::from_secs(1),
    ) {
        Ok(mut c) => c.write_all(what.as_bytes()).is_ok(),
        Err(_) => false,
    }
}

#[cfg(windows)]
fn create_mutex() -> (isize, bool) {
    use windows_sys::Win32::Foundation::{GetLastError, ERROR_ALREADY_EXISTS};
    use windows_sys::Win32::System::Threading::CreateMutexW;
    let name: Vec<u16> = MUTEX_NAME
        .encode_utf16()
        .chain(std::iter::once(0))
        .collect();
    // 안전: 이름 있는 뮤텍스를 만들 뿐이며, 손잡이는 프로그램이 끝날 때까지 들고 있음
    let h = unsafe { CreateMutexW(std::ptr::null(), 0, name.as_ptr()) };
    let already = unsafe { GetLastError() } == ERROR_ALREADY_EXISTS;
    (h as isize, already)
}

/// 자동 실행에 등록할 명령: 설치본이 있으면 설치본, 아니면 지금 실행 파일
pub fn autostart_command() -> String {
    let exe = installed_exe_path()
        .filter(|p| p.exists())
        .unwrap_or_else(|| std::env::current_exe().unwrap_or_default());
    format!("\"{}\" --minimized", exe.display())
}

/// 설치 파일이 프로그램을 풀어 넣는 자리 (`%LOCALAPPDATA%\WoorizipRadio\app\WoorizipRadio.exe`, 6일차)
pub fn installed_exe_path() -> Option<std::path::PathBuf> {
    let base = std::env::var_os("LOCALAPPDATA").map(std::path::PathBuf::from)?;
    Some(base.join(APP_NAME).join("app").join("WoorizipRadio.exe"))
}

#[cfg(windows)]
pub fn set_autostart(enabled: bool) -> Result<(), String> {
    use winreg::enums::HKEY_CURRENT_USER;
    use winreg::RegKey;
    let (key, _) = RegKey::predef(HKEY_CURRENT_USER)
        .create_subkey(RUN_KEY)
        .map_err(|e| e.to_string())?;
    if enabled {
        key.set_value(APP_NAME, &autostart_command())
            .map_err(|e| e.to_string())
    } else {
        match key.delete_value(APP_NAME) {
            Ok(()) => Ok(()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(e) => Err(e.to_string()),
        }
    }
}

#[cfg(windows)]
pub fn autostart_enabled() -> bool {
    use winreg::enums::HKEY_CURRENT_USER;
    use winreg::RegKey;
    RegKey::predef(HKEY_CURRENT_USER)
        .open_subkey(RUN_KEY)
        .and_then(|k| k.get_value::<String, _>(APP_NAME))
        .is_ok()
}

#[cfg(not(windows))]
pub fn set_autostart(_enabled: bool) -> Result<(), String> {
    Err("Windows에서만 돼요".into())
}
#[cfg(not(windows))]
pub fn autostart_enabled() -> bool {
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn autostart_command_points_to_exe_with_minimized() {
        let c = autostart_command();
        assert!(c.ends_with("--minimized"));
        assert!(c.starts_with('"'));
    }
}

// ---- 가족 암호 잠그기 (Windows DPAPI: 같은 Windows 계정에서만 풀림) ----

#[cfg(windows)]
pub fn protect(plain: &str) -> Option<String> {
    use windows_sys::Win32::Security::Cryptography::{CryptProtectData, CRYPT_INTEGER_BLOB};
    use windows_sys::Win32::System::Memory::LocalFree;
    if plain.is_empty() {
        return None;
    }
    let mut input = CRYPT_INTEGER_BLOB {
        cbData: plain.len() as u32,
        pbData: plain.as_ptr() as *mut u8,
    };
    let mut out = CRYPT_INTEGER_BLOB {
        cbData: 0,
        pbData: std::ptr::null_mut(),
    };
    // 안전: Windows API 에 올바른 구조체·포인터를 넘기고, 결과 메모리는 복사 후 LocalFree 로 돌려줌
    let ok = unsafe {
        CryptProtectData(
            &mut input,
            std::ptr::null(),
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            0,
            &mut out,
        )
    };
    if ok == 0 || out.pbData.is_null() {
        return None;
    }
    let bytes = unsafe { std::slice::from_raw_parts(out.pbData, out.cbData as usize).to_vec() };
    unsafe { LocalFree(out.pbData as _) };
    Some(b64_encode(&bytes))
}

#[cfg(windows)]
pub fn unprotect(enc: &str) -> Option<String> {
    use windows_sys::Win32::Security::Cryptography::{CryptUnprotectData, CRYPT_INTEGER_BLOB};
    use windows_sys::Win32::System::Memory::LocalFree;
    let bytes = b64_decode(enc)?;
    let mut input = CRYPT_INTEGER_BLOB {
        cbData: bytes.len() as u32,
        pbData: bytes.as_ptr() as *mut u8,
    };
    let mut out = CRYPT_INTEGER_BLOB {
        cbData: 0,
        pbData: std::ptr::null_mut(),
    };
    let ok = unsafe {
        CryptUnprotectData(
            &mut input,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            0,
            &mut out,
        )
    };
    if ok == 0 || out.pbData.is_null() {
        return None;
    }
    let plain = unsafe { std::slice::from_raw_parts(out.pbData, out.cbData as usize).to_vec() };
    unsafe { LocalFree(out.pbData as _) };
    String::from_utf8(plain).ok()
}

#[cfg(not(windows))]
pub fn protect(_plain: &str) -> Option<String> {
    None
}
#[cfg(not(windows))]
pub fn unprotect(_enc: &str) -> Option<String> {
    None
}

const B64: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

pub fn b64_encode(data: &[u8]) -> String {
    let mut out = String::with_capacity(data.len().div_ceil(3) * 4);
    for ch in data.chunks(3) {
        let b = [ch[0], *ch.get(1).unwrap_or(&0), *ch.get(2).unwrap_or(&0)];
        let n = ((b[0] as u32) << 16) | ((b[1] as u32) << 8) | b[2] as u32;
        out.push(B64[(n >> 18) as usize & 63] as char);
        out.push(B64[(n >> 12) as usize & 63] as char);
        out.push(if ch.len() > 1 {
            B64[(n >> 6) as usize & 63] as char
        } else {
            '='
        });
        out.push(if ch.len() > 2 {
            B64[n as usize & 63] as char
        } else {
            '='
        });
    }
    out
}

pub fn b64_decode(s: &str) -> Option<Vec<u8>> {
    let val = |c: u8| B64.iter().position(|x| *x == c).map(|p| p as u32);
    let bytes: Vec<u8> = s.bytes().filter(|b| !b.is_ascii_whitespace()).collect();
    if !bytes.len().is_multiple_of(4) {
        return None;
    }
    let mut out = Vec::with_capacity(bytes.len() / 4 * 3);
    for ch in bytes.chunks(4) {
        let pad = ch.iter().filter(|c| **c == b'=').count();
        let mut n = 0u32;
        for c in ch {
            n = (n << 6) | if *c == b'=' { 0 } else { val(*c)? };
        }
        out.push((n >> 16) as u8);
        if pad < 2 {
            out.push((n >> 8) as u8);
        }
        if pad < 1 {
            out.push(n as u8);
        }
    }
    Some(out)
}

#[cfg(test)]
mod b64_tests {
    use super::*;
    #[test]
    fn roundtrip() {
        for s in ["", "a", "ab", "abc", "가족 암호 105403"] {
            assert_eq!(b64_decode(&b64_encode(s.as_bytes())).unwrap(), s.as_bytes());
        }
        assert_eq!(b64_encode(b"Man"), "TWFu");
        assert!(b64_decode("abc").is_none());
    }
}
