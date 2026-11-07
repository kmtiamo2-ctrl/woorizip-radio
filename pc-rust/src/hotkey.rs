//! 전역 단축키(다른 창에 있어도 누르고 있는 동안 말하기). 설정의 `hotkey` 문자열은 Python판과 같은 꼴:
//! `f9`, `scroll lock`, `ctrl+f9`, `ctrl+alt+t` 처럼 소문자, `+` 로 이음.
//! Windows 에서는 rdev 가 키보드 훅으로 누름/뗌을 모두 알려 줌. 다른 운영체제에서는 꺼져 있음.

/// 설정 문자열을 (보조키 집합, 주 키 이름) 으로 나눔
pub fn parse(spec: &str) -> Option<(Vec<String>, String)> {
    let parts: Vec<String> = spec
        .to_lowercase()
        .split('+')
        .map(|p| p.trim().to_string())
        .filter(|p| !p.is_empty())
        .collect();
    let main = parts.last()?.clone();
    let mods = parts[..parts.len() - 1].to_vec();
    Some((mods, main))
}

/// 단축키가 눌림/떼짐을 받는 쪽
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum HotkeyEvent {
    Down,
    Up,
}

#[cfg(windows)]
mod imp {
    use super::*;
    use rdev::{listen, Event, EventType, Key};
    use std::sync::mpsc;

    /// 키 이름 → rdev 키. Python `keyboard` 라이브러리 이름을 따름
    fn key_of(name: &str) -> Option<Key> {
        Some(match name {
            "f1" => Key::F1,
            "f2" => Key::F2,
            "f3" => Key::F3,
            "f4" => Key::F4,
            "f5" => Key::F5,
            "f6" => Key::F6,
            "f7" => Key::F7,
            "f8" => Key::F8,
            "f9" => Key::F9,
            "f10" => Key::F10,
            "f11" => Key::F11,
            "f12" => Key::F12,
            "scroll lock" | "scrolllock" => Key::ScrollLock,
            "pause" | "pause break" => Key::Pause,
            "insert" => Key::Insert,
            "home" => Key::Home,
            "end" => Key::End,
            "page up" | "pageup" => Key::PageUp,
            "page down" | "pagedown" => Key::PageDown,
            "space" => Key::Space,
            "tab" => Key::Tab,
            "caps lock" | "capslock" => Key::CapsLock,
            "num lock" | "numlock" => Key::NumLock,
            "print screen" => Key::PrintScreen,
            "ctrl" | "control" => Key::ControlLeft,
            "alt" => Key::Alt,
            "shift" => Key::ShiftLeft,
            "`" => Key::BackQuote,
            "-" => Key::Minus,
            "=" => Key::Equal,
            s if s.len() == 1 && s.as_bytes()[0].is_ascii_alphabetic() => match s.as_bytes()[0] {
                b'a' => Key::KeyA,
                b'b' => Key::KeyB,
                b'c' => Key::KeyC,
                b'd' => Key::KeyD,
                b'e' => Key::KeyE,
                b'f' => Key::KeyF,
                b'g' => Key::KeyG,
                b'h' => Key::KeyH,
                b'i' => Key::KeyI,
                b'j' => Key::KeyJ,
                b'k' => Key::KeyK,
                b'l' => Key::KeyL,
                b'm' => Key::KeyM,
                b'n' => Key::KeyN,
                b'o' => Key::KeyO,
                b'p' => Key::KeyP,
                b'q' => Key::KeyQ,
                b'r' => Key::KeyR,
                b's' => Key::KeyS,
                b't' => Key::KeyT,
                b'u' => Key::KeyU,
                b'v' => Key::KeyV,
                b'w' => Key::KeyW,
                b'x' => Key::KeyX,
                b'y' => Key::KeyY,
                b'z' => Key::KeyZ,
                _ => return None,
            },
            s if s.len() == 1 && s.as_bytes()[0].is_ascii_digit() => match s.as_bytes()[0] {
                b'0' => Key::Num0,
                b'1' => Key::Num1,
                b'2' => Key::Num2,
                b'3' => Key::Num3,
                b'4' => Key::Num4,
                b'5' => Key::Num5,
                b'6' => Key::Num6,
                b'7' => Key::Num7,
                b'8' => Key::Num8,
                b'9' => Key::Num9,
                _ => return None,
            },
            _ => return None,
        })
    }

    fn is_mod(key: Key, name: &str) -> bool {
        match name {
            "ctrl" | "control" => matches!(key, Key::ControlLeft | Key::ControlRight),
            "alt" => matches!(key, Key::Alt | Key::AltGr),
            "shift" => matches!(key, Key::ShiftLeft | Key::ShiftRight),
            _ => false,
        }
    }

    /// 훅 스레드를 띄움. 단축키가 바뀌면 새 spec 을 보내 바꿀 수 있음 (spec 이 비면 아무 일도 안 함)
    pub fn start(spec_rx: mpsc::Receiver<String>, initial: String, out: mpsc::Sender<HotkeyEvent>) {
        std::thread::Builder::new()
            .name("hotkey".into())
            .spawn(move || {
                let spec = std::sync::Arc::new(std::sync::Mutex::new(parse(&initial)));
                {
                    let spec = spec.clone();
                    std::thread::spawn(move || {
                        for s in spec_rx {
                            *spec.lock().unwrap() = parse(&s);
                        }
                    });
                }
                let mut mods_down: Vec<Key> = vec![];
                let mut main_down = false;
                let cb = move |ev: Event| {
                    let Some((mods, main)) = spec.lock().unwrap().clone() else {
                        return;
                    };
                    let Some(main_key) = key_of(&main) else {
                        return;
                    };
                    match ev.event_type {
                        EventType::KeyPress(k) => {
                            if mods.iter().any(|m| is_mod(k, m)) && !mods_down.contains(&k) {
                                mods_down.push(k);
                            }
                            if k == main_key && !main_down {
                                let all =
                                    mods.iter().all(|m| mods_down.iter().any(|d| is_mod(*d, m)));
                                if all {
                                    main_down = true;
                                    let _ = out.send(HotkeyEvent::Down);
                                }
                            }
                        }
                        EventType::KeyRelease(k) => {
                            mods_down.retain(|d| *d != k);
                            if k == main_key && main_down {
                                main_down = false;
                                let _ = out.send(HotkeyEvent::Up);
                            }
                        }
                        _ => {}
                    }
                };
                if let Err(e) = listen(cb) {
                    crate::log::info(&format!("단축키 훅 실패: {e:?}"));
                }
            })
            .ok();
    }
}

#[cfg(not(windows))]
mod imp {
    use super::*;
    use std::sync::mpsc;
    pub fn start(
        _spec_rx: mpsc::Receiver<String>,
        _initial: String,
        _out: mpsc::Sender<HotkeyEvent>,
    ) {
    }
}

pub use imp::start;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_specs() {
        assert_eq!(parse("F9"), Some((vec![], "f9".into())));
        assert_eq!(parse("ctrl + F9"), Some((vec!["ctrl".into()], "f9".into())));
        assert_eq!(parse("scroll lock"), Some((vec![], "scroll lock".into())));
        assert_eq!(parse(""), None);
    }
}
