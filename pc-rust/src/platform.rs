//! 운영체제와 닿는 부분: 하나만 실행(뮤텍스 + 127.0.0.1:47651 "창 열어 줘" 통로), Windows 시작 시 자동 실행(레지스트리 Run).
//! Python판과 같은 뮤텍스 이름·포트를 써서, 두 판이 같이 깔려 있어도 서로를 알아봄 (6일차 교체 때 씀).
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::mpsc;

pub const GUARD_PORT: u16 = 47651;
/// 주 창 손잡이 (App 이 켜질 때 넣음). 창이 멈춰 있어도 '열어 줘' 요청을 바로 처리하려고 둠
pub static MAIN_HWND: std::sync::atomic::AtomicIsize = std::sync::atomic::AtomicIsize::new(0);
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
                        let h = MAIN_HWND.load(std::sync::atomic::Ordering::Relaxed);
                        if h != 0 {
                            window_show(h, None);
                        }
                        let _ = tx.send(Request::Show);
                    } else if s.starts_with("quit") {
                        // 같은 PC 안에서 온 요청(설치 파일·업데이트): 창이 멈춰 있어도 바로 끝냄
                        crate::log::info("종료 요청(설치·업데이트) → 끝냄");
                        let _ = tx.send(Request::Quit);
                        std::thread::sleep(std::time::Duration::from_millis(300));
                        std::process::exit(0);
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
    use windows_sys::Win32::Foundation::LocalFree;
    use windows_sys::Win32::Security::Cryptography::{CryptProtectData, CRYPT_INTEGER_BLOB};
    if plain.is_empty() {
        return None;
    }
    let input = CRYPT_INTEGER_BLOB {
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
            &input,
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
    use windows_sys::Win32::Foundation::LocalFree;
    use windows_sys::Win32::Security::Cryptography::{CryptUnprotectData, CRYPT_INTEGER_BLOB};
    let bytes = b64_decode(enc)?;
    let input = CRYPT_INTEGER_BLOB {
        cbData: bytes.len() as u32,
        pbData: bytes.as_ptr() as *mut u8,
    };
    let mut out = CRYPT_INTEGER_BLOB {
        cbData: 0,
        pbData: std::ptr::null_mut(),
    };
    let ok = unsafe {
        CryptUnprotectData(
            &input,
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

// ---- 설치 (이 exe 자체가 설치 파일): 설치 자리가 아닌 곳에서 실행되면 자기를 설치 자리에 복사하고 그쪽을 켬 ----

fn same_path(a: &std::path::Path, b: &std::path::Path) -> bool {
    a.to_string_lossy().to_lowercase() == b.to_string_lossy().to_lowercase()
}

/// 지금 실행 파일이 설치본인지
pub fn is_installed_copy() -> bool {
    match (std::env::current_exe(), installed_exe_path()) {
        (Ok(me), Some(inst)) => same_path(&me, &inst),
        _ => true, // 설치 자리를 모르는 환경(리눅스 점검 등)은 그냥 실행
    }
}

/// 설치: 켜져 있는 우리집(이 판이든 Python판이든)에 '종료해 줘' → 설치 폴더(app)를 통째로 바꿈 → 설치본을 같은 인자로 실행.
/// Ok(true)=설치하고 그쪽을 켰으니 나는 끝내면 됨
pub fn install_self(args: &[String]) -> Result<bool, String> {
    let me = std::env::current_exe().map_err(|e| e.to_string())?;
    let Some(inst) = installed_exe_path() else {
        return Ok(false);
    };
    let app_dir = inst.parent().unwrap().to_path_buf();
    let root = app_dir.parent().unwrap().to_path_buf();
    // 1) 실행 중이면 끄기 (뮤텍스가 풀릴 때까지 최대 15초)
    #[cfg(windows)]
    {
        if instance_running() {
            send_request("quit");
            for _ in 0..60 {
                std::thread::sleep(std::time::Duration::from_millis(250));
                if !instance_running() {
                    break;
                }
            }
            if instance_running() {
                return Err("실행 중인 '우리집'을 먼저 종료해 주세요.\n(오른쪽 아래 시계 옆 별 아이콘 → 완전히 종료)\n\n그 다음 이 파일을 다시 실행하면 새 버전으로 바뀌어요.".into());
            }
        }
    }
    // 2) 폴더 바꿔 끼우기: app.new 에 넣고 → app → app.old → app.new → app
    std::fs::create_dir_all(&root).map_err(|e| e.to_string())?;
    let new_dir = root.join("app.new");
    let old_dir = root.join("app.old");
    let _ = std::fs::remove_dir_all(&new_dir);
    std::fs::create_dir_all(&new_dir).map_err(|e| e.to_string())?;
    std::fs::copy(&me, new_dir.join("WoorizipRadio.exe")).map_err(|e| format!("복사 실패: {e}"))?;
    let _ = std::fs::remove_dir_all(&old_dir);
    if app_dir.exists() {
        retry(|| std::fs::rename(&app_dir, &old_dir))?;
    }
    retry(|| std::fs::rename(&new_dir, &app_dir))?;
    let _ = std::fs::remove_dir_all(&old_dir);
    let legacy = root.join("WoorizipRadio.exe"); // 아주 예전 한 파일 설치본
    let _ = std::fs::remove_file(legacy);
    // 3) 자동 실행 등록이 있었으면 설치본을 가리키게 갱신, 시작 메뉴 항목
    // 자동 실행: 설정에서 끄지 않았으면(기본 켜짐) 설치하면서 바로 등록 — 설치본 경로로
    if crate::config::Config::load().autostart {
        let _ = set_autostart(true);
    }
    let _ = make_shortcut(ShortcutWhere::StartMenu);
    // 4) 설치본 실행 (같은 인자)
    std::process::Command::new(&inst)
        .args(args)
        .current_dir(&app_dir)
        .spawn()
        .map_err(|e| format!("설치는 됐지만 실행하지 못했어요: {e}"))?;
    Ok(true)
}

fn retry<T, E: std::fmt::Display>(mut f: impl FnMut() -> Result<T, E>) -> Result<T, String> {
    let mut last = String::new();
    for _ in 0..40 {
        match f() {
            Ok(v) => return Ok(v),
            Err(e) => {
                last = e.to_string();
                std::thread::sleep(std::time::Duration::from_millis(250));
            }
        }
    }
    Err(last)
}

#[cfg(windows)]
fn instance_running() -> bool {
    use windows_sys::Win32::Foundation::{CloseHandle, GetLastError, ERROR_ALREADY_EXISTS};
    use windows_sys::Win32::System::Threading::CreateMutexW;
    let name: Vec<u16> = MUTEX_NAME
        .encode_utf16()
        .chain(std::iter::once(0))
        .collect();
    let h = unsafe { CreateMutexW(std::ptr::null(), 0, name.as_ptr()) };
    let exists = unsafe { GetLastError() } == ERROR_ALREADY_EXISTS;
    if !h.is_null() {
        unsafe { CloseHandle(h) };
    }
    exists
}

#[derive(Clone, Copy, PartialEq)]
pub enum ShortcutWhere {
    StartMenu,
    Desktop,
}

/// '우리집' 바로가기 만들기 (PowerShell). 한글 이름이 어느 언어 Windows 에서도 깨지지 않게 영문 이름으로 만든 뒤 바꿈
#[cfg(windows)]
pub fn make_shortcut(where_: ShortcutWhere) -> Result<(), String> {
    let target = installed_exe_path()
        .filter(|p| p.exists())
        .or_else(|| std::env::current_exe().ok())
        .ok_or("실행 파일을 못 찾았어요")?;
    let t = target.to_string_lossy().replace('\'', "''");
    let dir = target
        .parent()
        .map(|p| p.to_string_lossy().replace('\'', "''"))
        .unwrap_or_default();
    let folder = match where_ {
        ShortcutWhere::StartMenu => {
            "(Join-Path $env:APPDATA 'Microsoft\\Windows\\Start Menu\\Programs')".to_string()
        }
        ShortcutWhere::Desktop => "[Environment]::GetFolderPath('DesktopDirectory')".to_string(),
    };
    let ps = format!(
        "$ErrorActionPreference='Stop'\n$desk={folder}\nif(-not (Test-Path $desk)){{ New-Item -ItemType Directory -Force $desk | Out-Null }}\n$tmp=Join-Path $desk 'WoorizipRadio.lnk'\n$s=(New-Object -ComObject WScript.Shell).CreateShortcut($tmp)\n$s.TargetPath='{t}'\n$s.WorkingDirectory='{dir}'\n$s.IconLocation='{t},0'\n$s.Description='우리집'\n$s.Save()\nMove-Item -Force -LiteralPath $tmp -Destination (Join-Path $desk '우리집.lnk')\n"
    );
    let script = crate::config::data_dir().join("shortcut.ps1");
    let mut bytes = vec![0xEF, 0xBB, 0xBF];
    bytes.extend(ps.as_bytes());
    std::fs::write(&script, bytes).map_err(|e| e.to_string())?;
    let out = std::process::Command::new("powershell")
        .args([
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-WindowStyle",
            "Hidden",
            "-File",
        ])
        .arg(&script)
        .output()
        .map_err(|e| e.to_string())?;
    if !out.status.success() {
        return Err(String::from_utf8_lossy(&out.stderr)
            .chars()
            .take(300)
            .collect());
    }
    Ok(())
}

#[cfg(not(windows))]
pub fn make_shortcut(_where: ShortcutWhere) -> Result<(), String> {
    Err("Windows에서만 돼요".into())
}

/// 사용자에게 보이는 안내 상자 (설치 중 문제 등)
#[cfg(windows)]
pub fn message_box(text: &str, warn: bool) {
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        MessageBoxW, MB_ICONINFORMATION, MB_ICONWARNING,
    };
    let t: Vec<u16> = text.encode_utf16().chain(std::iter::once(0)).collect();
    let title: Vec<u16> = "우리집 설치"
        .encode_utf16()
        .chain(std::iter::once(0))
        .collect();
    unsafe {
        MessageBoxW(
            std::ptr::null_mut(),
            t.as_ptr(),
            title.as_ptr(),
            if warn {
                MB_ICONWARNING
            } else {
                MB_ICONINFORMATION
            },
        )
    };
}
#[cfg(not(windows))]
pub fn message_box(text: &str, _warn: bool) {
    eprintln!("{text}");
}

// ---- 창 숨기기/보이기 (Win32 직접) ----
// 숨긴(Visible=false) 창은 Windows 가 그리기 메시지를 안 보내 egui 가 멈춤 → 트레이 클릭·팝업·종료 요청을 못 받음.
// 그래서 '숨김' = 화면 밖(-32000)으로 옮기고 작업 표시줄·Alt+Tab 에서 빼기(WS_EX_TOOLWINDOW). 창은 계속 살아 있어 모든 게 동작함.

#[cfg(windows)]
pub fn window_handle(cc: &eframe::CreationContext<'_>) -> Option<isize> {
    use raw_window_handle::{HasWindowHandle, RawWindowHandle};
    match cc.window_handle().ok()?.as_raw() {
        RawWindowHandle::Win32(h) => Some(h.hwnd.get()),
        _ => None,
    }
}
#[cfg(not(windows))]
pub fn window_handle(_cc: &eframe::CreationContext<'_>) -> Option<isize> {
    None
}

#[cfg(windows)]
pub fn window_hide(hwnd: isize) {
    use windows_sys::Win32::Foundation::HWND;
    use windows_sys::Win32::UI::WindowsAndMessaging::*;
    let h = hwnd as HWND;
    unsafe {
        ShowWindow(h, SW_HIDE);
        let ex = GetWindowLongPtrW(h, GWL_EXSTYLE);
        SetWindowLongPtrW(h, GWL_EXSTYLE, ex | WS_EX_TOOLWINDOW as isize);
        SetWindowPos(
            h,
            std::ptr::null_mut(),
            -32000,
            -32000,
            0,
            0,
            SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE,
        );
        ShowWindow(h, SW_SHOWNOACTIVATE);
    }
}

#[cfg(windows)]
pub fn window_show(hwnd: isize, pos: Option<(i32, i32)>) {
    use windows_sys::Win32::Foundation::HWND;
    use windows_sys::Win32::UI::WindowsAndMessaging::*;
    let h = hwnd as HWND;
    unsafe {
        ShowWindow(h, SW_HIDE);
        let ex = GetWindowLongPtrW(h, GWL_EXSTYLE);
        SetWindowLongPtrW(h, GWL_EXSTYLE, ex & !(WS_EX_TOOLWINDOW as isize));
        let (x, y) = pos.unwrap_or_else(|| {
            let sw = GetSystemMetrics(SM_CXSCREEN);
            let sh = GetSystemMetrics(SM_CYSCREEN);
            ((sw - 460).max(0) / 2, (sh - 800).max(0) / 2)
        });
        SetWindowPos(
            h,
            std::ptr::null_mut(),
            x,
            y,
            0,
            0,
            SWP_NOSIZE | SWP_NOZORDER,
        );
        ShowWindow(h, SW_SHOW);
        ShowWindow(h, SW_RESTORE);
        SetForegroundWindow(h);
    }
}

#[cfg(windows)]
pub fn window_is_offscreen(hwnd: isize) -> bool {
    use windows_sys::Win32::Foundation::RECT;
    use windows_sys::Win32::UI::WindowsAndMessaging::GetWindowRect;
    let mut r = RECT {
        left: 0,
        top: 0,
        right: 0,
        bottom: 0,
    };
    unsafe { GetWindowRect(hwnd as _, &mut r) };
    r.left <= -30000
}

#[cfg(not(windows))]
pub fn window_hide(_hwnd: isize) {}
#[cfg(not(windows))]
pub fn window_show(_hwnd: isize, _pos: Option<(i32, i32)>) {}
#[cfg(not(windows))]
pub fn window_is_offscreen(_hwnd: isize) -> bool {
    false
}
