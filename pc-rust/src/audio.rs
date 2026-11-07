//! 소리: 스피커는 항상 열어 두고(Windows 기본 장치 = 헤드셋 꽂으면 헤드셋), 마이크는 말할 때만 엶.
//! 서버와 주고받는 소리는 16kHz 모노 16비트, 20ms(640바이트) 단위. 장치가 다른 속도만 되면 여기서 바꿔 줌.
//! 마이크 쪽에는 간단한 잡음 줄이기(저음 제거 + 조용할 때 바닥 잡음 줄이기)가 들어 있음.
use std::collections::VecDeque;
use std::sync::{Arc, Mutex};

pub const RATE: u32 = 16000;
pub const FRAME_SAMPLES: usize = 320; // 20ms
pub const FRAME_BYTES: usize = FRAME_SAMPLES * 2;

pub fn tone(freq: f32, ms: u32, vol: f32) -> Vec<i16> {
    let n = (RATE * ms / 1000) as usize;
    (0..n)
        .map(|i| {
            (32767.0 * vol * (2.0 * std::f32::consts::PI * freq * i as f32 / RATE as f32).sin())
                as i16
        })
        .collect()
}
/// 한 음(둥근 종소리 느낌): 바로 켜지고 decay 초에 걸쳐 잦아듦, 2배음 조금
pub fn note(freq: f32, ms: u32, decay: f32, vol: f32, harm2: f32) -> Vec<i16> {
    let n = (RATE * ms / 1000) as usize;
    (0..n)
        .map(|i| {
            let t = i as f32 / RATE as f32;
            let a = (t / 0.008).min(1.0) * (-t / decay).exp();
            let w = 2.0 * std::f32::consts::PI * freq * t;
            let s = w.sin() + harm2 * (2.0 * w).sin();
            (32767.0 * vol * a * s / (1.0 + harm2)) as i16
        })
        .collect()
}
fn seq(parts: &[(u32, Vec<i16>)]) -> Vec<i16> {
    let len = parts
        .iter()
        .map(|(off, p)| (RATE * off / 1000) as usize + p.len())
        .max()
        .unwrap_or(0);
    let mut out = vec![0i32; len];
    for (off, p) in parts {
        let o = (RATE * off / 1000) as usize;
        for (i, v) in p.iter().enumerate() {
            out[o + i] += *v as i32;
        }
    }
    out.into_iter()
        .map(|v| v.clamp(-32768, 32767) as i16)
        .collect()
}
/// 방송 들어옴: 둥근 '삐'
pub fn beep_in() -> Vec<i16> {
    note(880.0, 140, 0.06, 0.6, 0.15)
}
/// 글·사진 왔을 때: '톡톡' 두 번 (카톡처럼)
pub fn chime() -> Vec<i16> {
    seq(&[
        (0, note(784.0, 120, 0.05, 0.6, 0.3)),
        (150, note(784.0, 160, 0.07, 0.6, 0.3)),
    ])
}
pub fn beep_ok() -> Vec<i16> {
    tone(1200.0, 120, 0.25)
}
pub fn beep_no() -> Vec<i16> {
    let mut v = tone(300.0, 150, 0.25);
    v.extend(std::iter::repeat_n(0, 800));
    v.extend(tone(300.0, 150, 0.25));
    v
}

/// 재생 줄 (스피커 콜백이 꺼내 감). 처음엔 3조각이 모일 때까지 기다렸다가 틀어 끊김을 줄임 (Python판과 같음)
#[derive(Default)]
pub struct PlayQueue {
    q: VecDeque<Vec<i16>>,
    rem: Vec<i16>,
    waiting: bool,
}

impl PlayQueue {
    pub fn new() -> PlayQueue {
        PlayQueue {
            q: VecDeque::new(),
            rem: vec![],
            waiting: true,
        }
    }
    /// 실시간 조각: 너무 밀리면 오래된 것부터 버림
    pub fn push_live(&mut self, pcm: Vec<i16>) {
        self.q.push_back(pcm);
        while self.q.len() > 25 {
            self.q.pop_front();
        }
    }
    /// 효과음·클립 전체: 제한 없이
    pub fn push_all(&mut self, pcm: &[i16]) {
        for ch in pcm.chunks(FRAME_SAMPLES) {
            self.q.push_back(ch.to_vec());
        }
        self.waiting = false;
    }
    pub fn clear(&mut self) {
        self.q.clear();
        self.rem.clear();
        self.waiting = true;
    }
    pub fn is_empty(&self) -> bool {
        self.q.is_empty() && self.rem.is_empty()
    }
    /// 콜백: need 개의 표본을 채움 (모자라면 0)
    pub fn fill(&mut self, out: &mut [i16]) {
        let need = out.len();
        if self.waiting && self.q.len() < 3 {
            out.fill(0);
            return;
        }
        self.waiting = false;
        let mut buf = std::mem::take(&mut self.rem);
        while buf.len() < need {
            match self.q.pop_front() {
                Some(c) => buf.extend_from_slice(&c),
                None => break,
            }
        }
        if buf.len() < need {
            self.waiting = buf.is_empty();
            buf.resize(need, 0);
        }
        out.copy_from_slice(&buf[..need]);
        self.rem = buf[need..].to_vec();
    }
}

/// 마이크 잡음 줄이기: 1) 80Hz 아래 저음(웅웅거림·바람) 제거, 2) 말소리가 없는 동안의 바닥 잡음을 배워 두고 그 수준이면 1/4로 줄임
pub struct NoiseReducer {
    hp_prev_in: f32,
    hp_prev_out: f32,
    floor: f32, // 배운 바닥 잡음(RMS)
    gain: f32,  // 현재 적용 배율 (부드럽게 바뀜)
}

impl NoiseReducer {
    pub fn new() -> NoiseReducer {
        NoiseReducer {
            hp_prev_in: 0.0,
            hp_prev_out: 0.0,
            floor: 300.0,
            gain: 1.0,
        }
    }
    pub fn process(&mut self, frame: &mut [i16]) {
        // 1) 1차 고역 통과 (약 80Hz)
        let a = 0.969; // exp(-2*pi*80/16000)
        let mut sum = 0.0f64;
        for s in frame.iter_mut() {
            let x = *s as f32;
            let y = a * (self.hp_prev_out + x - self.hp_prev_in);
            self.hp_prev_in = x;
            self.hp_prev_out = y;
            *s = y.clamp(-32768.0, 32767.0) as i16;
            sum += (y as f64) * (y as f64);
        }
        let rms = (sum / frame.len().max(1) as f64).sqrt() as f32;
        // 2) 바닥 잡음 배우기: 조용할 땐 천천히 따라가고, 소리가 커지면 거의 안 올림
        if rms < self.floor * 1.5 {
            self.floor = self.floor * 0.95 + rms * 0.05;
        } else {
            self.floor = self.floor * 0.999 + rms * 0.001;
        }
        self.floor = self.floor.clamp(50.0, 3000.0);
        let target = if rms < self.floor * 2.0 { 0.25 } else { 1.0 };
        for s in frame.iter_mut() {
            self.gain += (target - self.gain) * 0.02; // 320표본 동안 서서히 → 뚝 끊기는 느낌 없이
            *s = (*s as f32 * self.gain) as i16;
        }
    }
}

impl Default for NoiseReducer {
    fn default() -> Self {
        Self::new()
    }
}

/// 표본 속도 바꾸기 (선형 보간). 장치가 16kHz 를 못 쓸 때만 씀
pub fn resample(input: &[i16], from: u32, to: u32) -> Vec<i16> {
    if from == to || input.is_empty() {
        return input.to_vec();
    }
    let n = (input.len() as u64 * to as u64 / from as u64) as usize;
    (0..n)
        .map(|i| {
            let pos = i as f64 * from as f64 / to as f64;
            let j = pos.floor() as usize;
            let f = (pos - j as f64) as f32;
            let a = input[j.min(input.len() - 1)] as f32;
            let b = input[(j + 1).min(input.len() - 1)] as f32;
            (a + (b - a) * f) as i16
        })
        .collect()
}

/// 여러 채널 → 모노 (평균)
pub fn to_mono(input: &[i16], channels: usize) -> Vec<i16> {
    if channels <= 1 {
        return input.to_vec();
    }
    input
        .chunks(channels)
        .map(|c| (c.iter().map(|x| *x as i32).sum::<i32>() / channels as i32) as i16)
        .collect()
}

pub fn bytes_to_i16(b: &[u8]) -> Vec<i16> {
    b.as_chunks::<2>()
        .0
        .iter()
        .map(|c| i16::from_le_bytes(*c))
        .collect()
}
pub fn i16_to_bytes(v: &[i16]) -> Vec<u8> {
    v.iter().flat_map(|s| s.to_le_bytes()).collect()
}

/// 마이크에서 나온 소리를 20ms 조각으로 잘라 보내 주는 모음통
pub struct MicChunker {
    buf: Vec<i16>,
    nr: NoiseReducer,
    pub on_frame: Box<dyn FnMut(Vec<u8>) + Send>,
}

impl MicChunker {
    pub fn new(on_frame: Box<dyn FnMut(Vec<u8>) + Send>) -> MicChunker {
        MicChunker {
            buf: vec![],
            nr: NoiseReducer::new(),
            on_frame,
        }
    }
    pub fn push(&mut self, mono16k: &[i16]) {
        self.buf.extend_from_slice(mono16k);
        while self.buf.len() >= FRAME_SAMPLES {
            let mut frame: Vec<i16> = self.buf.drain(..FRAME_SAMPLES).collect();
            self.nr.process(&mut frame);
            (self.on_frame)(i16_to_bytes(&frame));
        }
    }
}

pub type SharedQueue = Arc<Mutex<PlayQueue>>;

// ---------------- 실제 장치 (Windows: cpal/WASAPI) ----------------
#[cfg(windows)]
mod device {
    use super::*;
    use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};

    pub struct Audio {
        pub queue: SharedQueue,
        pub error: Option<String>,
        out: Option<cpal::Stream>,
        inp: Option<cpal::Stream>,
        host: cpal::Host,
    }

    // cpal::Stream 은 Send 가 아니지만 우리는 한 스레드(화면 스레드)에서만 다룸
    unsafe impl Send for Audio {}

    impl Audio {
        pub fn new() -> Audio {
            let host = cpal::default_host();
            let queue: SharedQueue = Arc::new(Mutex::new(PlayQueue::new()));
            let mut a = Audio {
                queue,
                error: None,
                out: None,
                inp: None,
                host,
            };
            a.open_output();
            a
        }

        fn open_output(&mut self) {
            let Some(dev) = self.host.default_output_device() else {
                self.error = Some("스피커 장치가 없어요".into());
                return;
            };
            let cfg = match dev.default_output_config() {
                Ok(c) => c,
                Err(e) => {
                    self.error = Some(format!("스피커를 열 수 없어요: {e}"));
                    return;
                }
            };
            let rate = cfg.sample_rate().0;
            let ch = cfg.channels() as usize;
            let queue = self.queue.clone();
            let mut carry: Vec<i16> = vec![]; // 16k → 장치 속도로 바꾼 뒤 남은 것
            let data_cb = move |out: &mut [f32], _: &cpal::OutputCallbackInfo| {
                let frames = out.len() / ch;
                // 장치가 요구하는 만큼을 16k 기준으로 환산해 받아 온 뒤 변환
                while carry.len() < frames {
                    let need16 =
                        ((frames - carry.len()) as u64 * RATE as u64 / rate as u64) as usize + 2;
                    let mut tmp = vec![0i16; need16];
                    queue.lock().unwrap().fill(&mut tmp);
                    carry.extend(resample(&tmp, RATE, rate));
                }
                for (i, fr) in out.chunks_mut(ch).enumerate() {
                    let v = carry[i] as f32 / 32768.0;
                    for s in fr.iter_mut() {
                        *s = v;
                    }
                }
                carry.drain(..frames);
            };
            match dev.build_output_stream(
                &cfg.config(),
                data_cb,
                |e| crate::log::info(&format!("스피커 오류: {e}")),
                None,
            ) {
                Ok(s) => {
                    if let Err(e) = s.play() {
                        self.error = Some(format!("스피커 시작 실패: {e}"));
                    }
                    self.out = Some(s);
                }
                Err(e) => self.error = Some(format!("스피커를 열 수 없어요: {e}")),
            }
        }

        pub fn start_mic(&mut self, on_frame: Box<dyn FnMut(Vec<u8>) + Send>) -> bool {
            let Some(dev) = self.host.default_input_device() else {
                return false;
            };
            let Ok(cfg) = dev.default_input_config() else {
                return false;
            };
            let rate = cfg.sample_rate().0;
            let ch = cfg.channels() as usize;
            let mut chunker = MicChunker::new(on_frame);
            let cb = move |data: &[f32], _: &cpal::InputCallbackInfo| {
                let as16: Vec<i16> = data
                    .iter()
                    .map(|x| (x.clamp(-1.0, 1.0) * 32767.0) as i16)
                    .collect();
                let mono = to_mono(&as16, ch);
                chunker.push(&resample(&mono, rate, RATE));
            };
            match dev.build_input_stream(
                &cfg.config(),
                cb,
                |e| crate::log::info(&format!("마이크 오류: {e}")),
                None,
            ) {
                Ok(s) => {
                    if s.play().is_err() {
                        return false;
                    }
                    self.inp = Some(s);
                    true
                }
                Err(e) => {
                    crate::log::info(&format!("마이크를 열 수 없어요: {e}"));
                    false
                }
            }
        }

        pub fn stop_mic(&mut self) {
            self.inp = None;
        }
        pub fn mic_on(&self) -> bool {
            self.inp.is_some()
        }
    }
}

// ---------------- 소리 장치가 없는 환경 (리눅스 점검용): 줄만 돌리고 소리는 안 남 ----------------
#[cfg(not(windows))]
mod device {
    use super::*;

    pub struct Audio {
        pub queue: SharedQueue,
        pub error: Option<String>,
        mic: Option<std::sync::mpsc::Sender<()>>,
    }

    impl Audio {
        pub fn new() -> Audio {
            let queue: SharedQueue = Arc::new(Mutex::new(PlayQueue::new()));
            // 스피커 흉내: 20ms 마다 한 조각씩 비움 (재생 시간이 실제처럼 흐르게)
            let q = queue.clone();
            std::thread::spawn(move || loop {
                std::thread::sleep(std::time::Duration::from_millis(20));
                let mut tmp = [0i16; FRAME_SAMPLES];
                q.lock().unwrap().fill(&mut tmp);
            });
            Audio {
                queue,
                error: Some("이 운영체제에서는 소리를 쓸 수 없어요 (Windows 전용)".into()),
                mic: None,
            }
        }
        pub fn start_mic(&mut self, on_frame: Box<dyn FnMut(Vec<u8>) + Send>) -> bool {
            // 마이크 흉내: 20ms 마다 조용한 조각 (시험용)
            let (tx, rx) = std::sync::mpsc::channel::<()>();
            std::thread::spawn(move || {
                let mut ch = MicChunker::new(on_frame);
                while rx.try_recv().is_err() {
                    std::thread::sleep(std::time::Duration::from_millis(20));
                    ch.push(&[0i16; FRAME_SAMPLES]);
                }
            });
            self.mic = Some(tx);
            true
        }
        pub fn stop_mic(&mut self) {
            self.mic = None;
        }
        pub fn mic_on(&self) -> bool {
            self.mic.is_some()
        }
    }
}

pub use device::Audio;

impl Audio {
    pub fn play_live(&self, bytes: &[u8]) {
        self.queue.lock().unwrap().push_live(bytes_to_i16(bytes));
    }
    pub fn effect(&self, pcm: &[i16]) {
        self.queue.lock().unwrap().push_all(pcm);
    }
    /// 클립 전체 재생: 앞에 알림음을 붙임
    pub fn play_all(&self, pcm: &[i16]) {
        let mut q = self.queue.lock().unwrap();
        q.clear();
        q.push_all(&beep_in());
        q.push_all(&[0i16; 800]);
        q.push_all(pcm);
    }
    pub fn stop_playback(&self) {
        self.queue.lock().unwrap().clear();
    }
    pub fn idle(&self) -> bool {
        self.queue.lock().unwrap().is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn queue_waits_for_three_then_plays_and_pads() {
        let mut q = PlayQueue::new();
        let mut out = [1i16; 4];
        q.push_live(vec![5; 4]);
        q.fill(&mut out);
        assert_eq!(out, [0; 4]); // 아직 기다림
        q.push_live(vec![6; 4]);
        q.push_live(vec![7; 2]);
        q.fill(&mut out);
        assert_eq!(out, [5; 4]);
        q.fill(&mut out);
        assert_eq!(out, [6; 4]);
        q.fill(&mut out);
        assert_eq!(out, [7, 7, 0, 0]); // 모자라면 0으로 채움
        assert!(q.is_empty());
    }

    #[test]
    fn resample_and_mono() {
        let v: Vec<i16> = (0..48).map(|i| i * 100).collect();
        let r = resample(&v, 48000, 16000);
        assert_eq!(r.len(), 16);
        assert_eq!(r[1], 300);
        assert_eq!(to_mono(&[100, 300, -200, 200], 2), vec![200, 0]);
        assert_eq!(bytes_to_i16(&i16_to_bytes(&[-5, 7])), vec![-5, 7]);
    }

    #[test]
    fn chunker_emits_20ms_frames_with_noise_reduction() {
        let got = Arc::new(Mutex::new(vec![]));
        let g = got.clone();
        let mut ch = MicChunker::new(Box::new(move |b| g.lock().unwrap().push(b)));
        ch.push(&vec![0i16; 500]);
        assert_eq!(got.lock().unwrap().len(), 1);
        ch.push(&vec![0i16; 140]);
        assert_eq!(got.lock().unwrap().len(), 2);
        assert!(got.lock().unwrap().iter().all(|f| f.len() == FRAME_BYTES));
    }

    #[test]
    fn noise_reducer_keeps_speech_and_softens_silence() {
        let mut nr = NoiseReducer::new();
        // 조용한 바닥 잡음 1초
        for _ in 0..50 {
            let mut f: Vec<i16> = (0..FRAME_SAMPLES)
                .map(|i| if i % 2 == 0 { 120 } else { -120 })
                .collect();
            nr.process(&mut f);
        }
        let mut quiet: Vec<i16> = (0..FRAME_SAMPLES)
            .map(|i| if i % 2 == 0 { 120 } else { -120 })
            .collect();
        nr.process(&mut quiet);
        let q_rms =
            (quiet.iter().map(|x| (*x as f64).powi(2)).sum::<f64>() / FRAME_SAMPLES as f64).sqrt();
        assert!(q_rms < 60.0, "조용할 땐 줄여야 함: {q_rms}");
        // 말소리(큰 400Hz)
        let mut loud: Vec<i16> = tone(400.0, 20, 0.5);
        for _ in 0..3 {
            loud = tone(400.0, 20, 0.5);
            nr.process(&mut loud);
        }
        let l_rms =
            (loud.iter().map(|x| (*x as f64).powi(2)).sum::<f64>() / loud.len() as f64).sqrt();
        assert!(l_rms > 8000.0, "말소리는 거의 그대로여야 함: {l_rms}");
    }
}
