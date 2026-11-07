//! 서버와 주고받는 메시지 중 1일차에 쓰는 것 (나머지는 serde_json::Value 로 그대로 받아 다음 날 붙임)
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Serialize)]
pub struct Hello<'a> {
    #[serde(rename = "type")]
    pub kind: &'static str,
    pub name: &'a str,
    pub key: &'a str,
    pub device: &'a str,
    #[serde(rename = "where")]
    pub where_: &'a str,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Welcome {
    pub members: Vec<String>,
    pub you: String,
    #[serde(rename = "where")]
    pub where_: String,
    #[serde(default)]
    pub parents: Vec<String>,
    /// 서버 names.json 이 정한 화면 이름표 (첫째 → 실제 이름 등). 앱 안에는 실제 이름이 없음
    #[serde(default)]
    pub names: HashMap<String, String>,
}

impl Welcome {
    /// 화면에 보일 이름: 나는 "나", 나머지는 이름표가 있으면 그 이름
    pub fn disp<'a>(&'a self, name: &'a str) -> &'a str {
        if name == self.you {
            return "나";
        }
        self.names.get(name).map(|s| s.as_str()).unwrap_or(name)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn welcome_parses_with_and_without_names() {
        let w: Welcome = serde_json::from_str(r#"{"type":"welcome","members":["엄마","아빠"],"you":"아빠","where":"home","parents":["엄마","아빠"],"names":{"엄마":"ㅇ"}}"#).unwrap();
        assert_eq!(w.disp("엄마"), "ㅇ");
        assert_eq!(w.disp("아빠"), "나");
        let w2: Welcome =
            serde_json::from_str(r#"{"members":["엄마"],"you":"엄마","where":"out"}"#).unwrap();
        assert_eq!(w2.disp("첫째"), "첫째");
        assert!(w2.names.is_empty());
    }
}
