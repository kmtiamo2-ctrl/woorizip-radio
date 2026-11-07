//! 작업 표시줄 시계 옆 아이콘 (별 모양, 연결되면 초록·아니면 회색). 메뉴: 열기 / 완전히 종료(가족 암호).
//! 창을 닫아도 여기 남아 계속 듣고 있음. Windows 전용 (다른 운영체제는 빈 껍데기).

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum TrayEvent {
    Open,
    Quit,
}

/// 별 모양 아이콘 그림 (RGBA)
pub fn star_rgba(color: [u8; 3], size: usize) -> Vec<u8> {
    let mut out = vec![0u8; size * size * 4];
    let c = size as f32 / 2.0;
    let r_out = c * 0.95;
    let r_in = c * 0.42;
    // 별의 꼭짓점 10개 (바깥·안쪽 번갈아)
    let pts: Vec<(f32, f32)> = (0..10)
        .map(|i| {
            let a = -std::f32::consts::FRAC_PI_2 + i as f32 * std::f32::consts::PI / 5.0;
            let r = if i % 2 == 0 { r_out } else { r_in };
            (c + r * a.cos(), c + r * a.sin())
        })
        .collect();
    let inside = |x: f32, y: f32| {
        // 다각형 안쪽 판정 (짝홀 규칙)
        let mut hit = false;
        let n = pts.len();
        let mut j = n - 1;
        for i in 0..n {
            let (xi, yi) = pts[i];
            let (xj, yj) = pts[j];
            if (yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi {
                hit = !hit;
            }
            j = i;
        }
        hit
    };
    for y in 0..size {
        for x in 0..size {
            // 4배 샘플로 가장자리를 부드럽게
            let mut cover = 0;
            for sy in 0..2 {
                for sx in 0..2 {
                    if inside(
                        x as f32 + 0.25 + sx as f32 * 0.5,
                        y as f32 + 0.25 + sy as f32 * 0.5,
                    ) {
                        cover += 1;
                    }
                }
            }
            let i = (y * size + x) * 4;
            out[i] = color[0];
            out[i + 1] = color[1];
            out[i + 2] = color[2];
            out[i + 3] = (cover * 255 / 4) as u8;
        }
    }
    out
}

pub const GREEN: [u8; 3] = [34, 197, 94];
pub const GREY: [u8; 3] = [100, 116, 139];
pub const BLUE: [u8; 3] = [37, 99, 235];

#[cfg(windows)]
mod imp {
    use super::*;
    use tray_icon::menu::{Menu, MenuEvent, MenuId, MenuItem, PredefinedMenuItem};
    use tray_icon::{
        Icon, MouseButton, MouseButtonState, TrayIcon, TrayIconBuilder, TrayIconEvent,
    };

    pub struct Tray {
        icon: Option<TrayIcon>,
        open_id: MenuId,
        quit_id: MenuId,
        connected: Option<bool>,
    }

    impl Tray {
        pub fn new() -> Tray {
            let menu = Menu::new();
            let open = MenuItem::new("열기", true, None);
            let quit = MenuItem::new("완전히 종료 (가족 암호)", true, None);
            let _ = menu.append(&open);
            let _ = menu.append(&PredefinedMenuItem::separator());
            let _ = menu.append(&quit);
            let icon = Icon::from_rgba(star_rgba(BLUE, 32), 32, 32).ok();
            let mut b = TrayIconBuilder::new()
                .with_menu(Box::new(menu))
                .with_tooltip("우리집")
                .with_menu_on_left_click(false);
            if let Some(i) = icon {
                b = b.with_icon(i);
            }
            let tray = match b.build() {
                Ok(t) => Some(t),
                Err(e) => {
                    crate::log::info(&format!("트레이 아이콘 실패: {e}"));
                    None
                }
            };
            Tray {
                icon: tray,
                open_id: open.id().clone(),
                quit_id: quit.id().clone(),
                connected: None,
            }
        }

        /// 연결 상태가 바뀌었을 때만 아이콘·설명 갱신
        pub fn set_connected(&mut self, connected: bool) {
            if self.connected == Some(connected) {
                return;
            }
            self.connected = Some(connected);
            if let Some(t) = &self.icon {
                let _ = t.set_icon(
                    Icon::from_rgba(star_rgba(if connected { GREEN } else { GREY }, 32), 32, 32)
                        .ok(),
                );
                let _ = t.set_tooltip(Some(if connected {
                    "우리집 · 연결됨"
                } else {
                    "우리집 · 연결 안 됨"
                }));
            }
        }

        /// 매 프레임 불러 쌓인 클릭·메뉴 이벤트를 꺼냄
        pub fn poll(&mut self) -> Vec<TrayEvent> {
            let mut out = vec![];
            while let Ok(ev) = MenuEvent::receiver().try_recv() {
                if ev.id == self.open_id {
                    out.push(TrayEvent::Open);
                } else if ev.id == self.quit_id {
                    out.push(TrayEvent::Quit);
                }
            }
            while let Ok(ev) = TrayIconEvent::receiver().try_recv() {
                match ev {
                    TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    }
                    | TrayIconEvent::DoubleClick {
                        button: MouseButton::Left,
                        ..
                    } => out.push(TrayEvent::Open),
                    _ => {}
                }
            }
            out
        }
    }
}

#[cfg(not(windows))]
mod imp {
    use super::*;
    pub struct Tray;
    impl Tray {
        pub fn new() -> Tray {
            Tray
        }
        pub fn set_connected(&mut self, _c: bool) {}
        pub fn poll(&mut self) -> Vec<TrayEvent> {
            vec![]
        }
    }
}

pub use imp::Tray;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn star_has_opaque_center_and_clear_corner() {
        let px = star_rgba(GREEN, 32);
        let at = |x: usize, y: usize| px[(y * 32 + x) * 4 + 3];
        assert_eq!(at(16, 16), 255);
        assert_eq!(at(0, 0), 0);
        assert_eq!(px[(16 * 32 + 16) * 4], 34);
    }
}
