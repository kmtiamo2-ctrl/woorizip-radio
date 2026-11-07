//! 자동 업데이트: NAS 서버의 `/update/latest.json` 을 보고 새 버전이면 설치 파일을 받아 둠 (sha256 + **서명 확인**).
//! 적용은 창이 한가할 때(말하지 않고, 창이 숨겨져 있을 때): 받은 exe를 `--minimized --updated` 로 실행하면
//! 그 exe(새 버전)가 지금 프로그램에 '종료해 줘'를 보내고 설치 폴더를 바꿔 끼운 뒤 트레이로 다시 켜짐 (platform::install).
//!
//! 서명: 릴리스를 만들 때 GitHub 비밀 `PC_SIGN_KEY`(ed25519 비밀 열쇠)로 exe 를 서명해 `.sig` 를 같이 올림.
//! 여기에는 공개 열쇠만 들어 있어, NAS 나 중간 경로가 뚫려 가짜 exe 가 와도 설치하지 않음.
use crate::app::Shared;
use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use sha2::{Digest, Sha256};
use std::path::PathBuf;

/// 릴리스 서명 공개 열쇠 (ed25519, 32바이트 hex). 비밀 열쇠는 GitHub 비밀에만 있음
pub const PUBKEY_HEX: &str = "98eb3b14e5f731fdc08fdd50fafebf69bf6f1f02ac95ed8308dda9e8c1f08521";

pub const FIRST_CHECK_SECS: u64 = 120;
pub const CHECK_EVERY_SECS: u64 = 12 * 3600;

/// 빌드 때 CI 가 넣어 주는 버전 (`v73` 처럼). 없으면 개발 빌드(0)
pub fn version() -> String {
    option_env!("WOORIZIP_VERSION").unwrap_or("dev").to_string()
}
pub fn version_number(v: &str) -> u64 {
    v.chars()
        .filter(|c| c.is_ascii_digit())
        .collect::<String>()
        .parse()
        .unwrap_or(0)
}

pub fn update_dir() -> PathBuf {
    let base = std::env::var_os("LOCALAPPDATA")
        .map(PathBuf::from)
        .unwrap_or_else(crate::config::data_dir);
    base.join(crate::platform::APP_NAME).join("update")
}

pub fn sha256_file(p: &std::path::Path) -> Option<String> {
    let mut f = std::fs::File::open(p).ok()?;
    let mut h = Sha256::new();
    std::io::copy(&mut f, &mut h).ok()?;
    Some(hex(&h.finalize()))
}

pub fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}
pub fn unhex(s: &str) -> Option<Vec<u8>> {
    let s = s.trim();
    if !s.len().is_multiple_of(2) {
        return None;
    }
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).ok())
        .collect()
}

/// 파일 서명 확인: sig 는 64바이트 hex. 공개 열쇠가 비어 있으면(개발 빌드) 항상 실패
pub fn verify_file(path: &std::path::Path, sig_hex: &str) -> Result<(), String> {
    verify_bytes(
        &std::fs::read(path).map_err(|e| e.to_string())?,
        sig_hex,
        PUBKEY_HEX,
    )
}

pub fn verify_bytes(data: &[u8], sig_hex: &str, pubkey_hex: &str) -> Result<(), String> {
    let pk: [u8; 32] = unhex(pubkey_hex)
        .and_then(|v| v.try_into().ok())
        .ok_or("공개 열쇠가 없어요")?;
    let vk = VerifyingKey::from_bytes(&pk).map_err(|e| e.to_string())?;
    let sig: [u8; 64] = unhex(sig_hex)
        .and_then(|v| v.try_into().ok())
        .ok_or("서명 형식이 달라요")?;
    vk.verify(data, &Signature::from_bytes(&sig))
        .map_err(|_| "서명이 맞지 않아요".to_string())
}

/// CI 용: 비밀 열쇠(32바이트 hex seed)로 파일을 서명해 hex 로 돌려줌
pub fn sign_file(seed_hex: &str, path: &std::path::Path) -> Result<String, String> {
    let seed: [u8; 32] = unhex(seed_hex)
        .and_then(|v| v.try_into().ok())
        .ok_or("열쇠 형식이 달라요 (32바이트 hex)")?;
    let sk = SigningKey::from_bytes(&seed);
    let data = std::fs::read(path).map_err(|e| e.to_string())?;
    Ok(hex(&sk.sign(&data).to_bytes()))
}

pub fn pubkey_of(seed_hex: &str) -> Result<String, String> {
    let seed: [u8; 32] = unhex(seed_hex)
        .and_then(|v| v.try_into().ok())
        .ok_or("열쇠 형식이 달라요 (32바이트 hex)")?;
    Ok(hex(SigningKey::from_bytes(&seed)
        .verifying_key()
        .as_bytes()))
}

/// 명령줄 보조 기능: `--sign <seed_hex> <file>` / `--verify <sig_hex> <file>` / `--pubkey <seed_hex>`. 처리했으면 true
pub fn cli(args: &[String]) -> bool {
    let get = |i: usize| args.get(i).cloned().unwrap_or_default();
    match args.get(1).map(|s| s.as_str()) {
        Some("--sign") => {
            match sign_file(&get(2), std::path::Path::new(&get(3))) {
                Ok(s) => println!("{s}"),
                Err(e) => {
                    eprintln!("{e}");
                    std::process::exit(2);
                }
            }
            true
        }
        Some("--verify") => {
            match verify_file(std::path::Path::new(&get(3)), &get(2)) {
                Ok(()) => println!("OK"),
                Err(e) => {
                    eprintln!("{e}");
                    std::process::exit(2);
                }
            }
            true
        }
        Some("--pubkey") => {
            match pubkey_of(&get(2)) {
                Ok(s) => println!("{s}"),
                Err(e) => {
                    eprintln!("{e}");
                    std::process::exit(2);
                }
            }
            true
        }
        _ => false,
    }
}

/// 받아 둔 새 버전 (경로, 번호)
#[derive(Debug, Clone)]
pub struct Ready {
    pub path: PathBuf,
    pub number: u64,
}

/// 새 버전이 있으면 받아 두고 Some. 연결이 없거나 최신이면 None
pub fn check(state: &Shared) -> Result<Option<Ready>, String> {
    let my = version_number(&version());
    if my == 0 {
        return Ok(None); // 개발 빌드는 업데이트 안 함
    }
    if !state.lock().unwrap().connected {
        return Ok(None);
    }
    let base = crate::files::http_base(state);
    let info: serde_json::Value = ureq::get(&format!("{base}/update/latest.json"))
        .timeout(std::time::Duration::from_secs(15))
        .call()
        .map_err(|e| e.to_string())?
        .into_string()
        .map_err(|e| e.to_string())
        .and_then(|t| serde_json::from_str(&t).map_err(|e| e.to_string()))?;
    let num = info.get("number").and_then(|v| v.as_u64()).unwrap_or(0);
    let Some(pc) = info.get("pc").filter(|p| p.is_object()) else {
        return Ok(None);
    };
    if num <= my {
        return Ok(None);
    }
    let file = pc
        .get("file")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let sha = pc
        .get("sha256")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let sig = pc
        .get("sig")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    if file.is_empty()
        || !file.starts_with("WoorizipRadio")
        || file.contains('/')
        || file.contains('\\')
    {
        return Err("설치 파일 이름이 이상해요".into());
    }
    if sig.is_empty() {
        return Err(format!(
            "v{num} 설치 파일에 서명이 없어 받지 않았어요 (GitHub 비밀 PC_SIGN_KEY 필요)"
        ));
    }
    let dir = update_dir();
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let dest = dir.join(&file);
    if !(dest.exists() && sha256_file(&dest).as_deref() == Some(sha.as_str())) {
        let tmp = dest.with_extension("part");
        let r = ureq::get(&format!("{base}/update/{file}"))
            .timeout(std::time::Duration::from_secs(600))
            .call()
            .map_err(|e| e.to_string())?;
        let mut reader = r.into_reader();
        let mut f = std::fs::File::create(&tmp).map_err(|e| e.to_string())?;
        let mut h = Sha256::new();
        let mut buf = vec![0u8; 256 * 1024];
        loop {
            let n = std::io::Read::read(&mut reader, &mut buf).map_err(|e| e.to_string())?;
            if n == 0 {
                break;
            }
            std::io::Write::write_all(&mut f, &buf[..n]).map_err(|e| e.to_string())?;
            h.update(&buf[..n]);
        }
        drop(f);
        if hex(&h.finalize()) != sha {
            let _ = std::fs::remove_file(&tmp);
            return Err("받은 파일이 손상됨 (sha256 불일치)".into());
        }
        std::fs::rename(&tmp, &dest).map_err(|e| e.to_string())?;
    }
    if let Err(e) = verify_file(&dest, &sig) {
        let _ = std::fs::remove_file(&dest);
        return Err(format!(
            "v{num} 설치 파일 서명 확인 실패: {e} — 설치하지 않음"
        ));
    }
    crate::log::info(&format!(
        "새 버전 받아 둠: v{num} ({}) · 서명 확인됨",
        dest.display()
    ));
    Ok(Some(Ready {
        path: dest,
        number: num,
    }))
}

/// 받아 둔 새 버전 실행 → 그쪽이 우리를 끄고 교체함
pub fn apply(r: &Ready) -> Result<(), String> {
    crate::log::info(&format!("업데이트 적용: v{}", r.number));
    std::process::Command::new(&r.path)
        .args(["--minimized", "--updated"])
        .current_dir(r.path.parent().unwrap_or(std::path::Path::new(".")))
        .spawn()
        .map(|_| ())
        .map_err(|e| e.to_string())
}

/// 설치가 끝난 뒤(새 버전에서) 받아 둔 옛 파일 정리
pub fn cleanup() {
    let my = version_number(&version());
    if let Ok(rd) = std::fs::read_dir(update_dir()) {
        for e in rd.flatten() {
            let name = e.file_name().to_string_lossy().to_string();
            if name.ends_with(".part") || version_number(&name) <= my {
                let _ = std::fs::remove_file(e.path());
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sign_and_verify_roundtrip() {
        let seed = "11".repeat(32);
        let pk = pubkey_of(&seed).unwrap();
        let dir = std::env::temp_dir().join(format!("wz-sig-{}", std::process::id()));
        let _ = std::fs::create_dir_all(&dir);
        let p = dir.join("a.bin");
        std::fs::write(&p, b"hello family").unwrap();
        let sig = sign_file(&seed, &p).unwrap();
        assert!(verify_bytes(b"hello family", &sig, &pk).is_ok());
        assert!(verify_bytes(b"hello famiLy", &sig, &pk).is_err());
        assert!(verify_bytes(b"hello family", &sig, &"22".repeat(32)).is_err());
        assert_eq!(version_number("WoorizipRadio-PC-v73.exe"), 73);
        assert_eq!(version_number("dev"), 0);
        assert_eq!(sha256_file(&p).unwrap().len(), 64);
    }
}
