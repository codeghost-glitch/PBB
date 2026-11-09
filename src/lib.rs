#![allow(non_snake_case)]

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use std::fs;
use std::path::{Path, PathBuf};

const STREAM_CONFIG_JSON: &str = r#"{
  "app": "PBB",
  "streams": [
    {
      "name": "PBB All Access",
      "url": "https://absp-live.akamaized.net/dash/live/2099522/pbbcollab30allaccesslivestream/manifest.mpd",
      "kid": "5208606a1a5e4750bc7aaafa3bbad84c",
      "key": "246d9c7efc6d96d8d596aa294313e17b"
    },
    {
      "name": "PBB Dining Area",
      "url": "https://absp-live.akamaized.net/dash/live/2099522/pbbcollab30allaccesslivestream-diningarea/manifest.mpd",
      "kid": "ac87b335cfd44a8589f96a4f8534c674",
      "key": "4b9066c7778e74b899c2f57d977149b1"
    },
    {
      "name": "PBB External Den",
      "url": "https://absp-live.akamaized.net/dash/live/2099522/pbbcollab30allaccesslivestream-externalden/manifest.mpd",
      "kid": "01f5f16338aa4302b5fc5e3e2c50cd3d",
      "key": "0dbd0f05009fa64614de990f56b3f322"
    },
    {
      "name": "PBB Main Live",
      "url": "https://abslive.akamaized.net/dash/live/2099522/pinoybigbrothercollabedition30live/manifest.mpd",
      "kid": "02b0d6aa78d547af890c9e27113e81af",
      "key": "890160f8ece56b8cd232889acb74e276"
    }
  ]
}"#;

const QUALITY_AUTO: &str = "auto";
const QUALITY_HD: &str = "hd";
const QUALITY_SD: &str = "sd";

pub fn stream_config_json() -> &'static str {
    STREAM_CONFIG_JSON
}

fn normalize_quality(value: &str) -> String {
    match value.trim().to_ascii_lowercase().as_str() {
        QUALITY_HD => QUALITY_HD.to_string(),
        QUALITY_SD => QUALITY_SD.to_string(),
        _ => QUALITY_AUTO.to_string(),
    }
}

fn quality_file(files_dir: &str) -> PathBuf {
    Path::new(files_dir).join("default_quality.txt")
}

fn load_default_quality(files_dir: &str) -> String {
    fs::read_to_string(quality_file(files_dir))
        .map(|value| normalize_quality(&value))
        .unwrap_or_else(|_| QUALITY_AUTO.to_string())
}

fn save_default_quality(files_dir: &str, quality: &str) -> std::io::Result<()> {
    fs::write(quality_file(files_dir), normalize_quality(quality))
}

fn java_string<'local>(env: &JNIEnv<'local>, value: &str) -> jstring {
    match env.new_string(value) {
        Ok(result) => result.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_pbb_live_MainActivity_streamConfig<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    java_string(&env, stream_config_json())
}

#[no_mangle]
pub extern "system" fn Java_com_pbb_live_MainActivity_defaultQuality<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    files_dir: JString<'local>,
) -> jstring {
    let dir: String = env
        .get_string(&files_dir)
        .map(String::from)
        .unwrap_or_default();
    let quality = load_default_quality(&dir);
    java_string(&env, &quality)
}

#[no_mangle]
pub extern "system" fn Java_com_pbb_live_MainActivity_setDefaultQuality<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    files_dir: JString<'local>,
    quality: JString<'local>,
) {
    let dir: String = env
        .get_string(&files_dir)
        .map(String::from)
        .unwrap_or_default();
    let value: String = env
        .get_string(&quality)
        .map(String::from)
        .unwrap_or_default();
    let _ = save_default_quality(&dir, &value);
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn test_dir(tag: &str) -> PathBuf {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let path = std::env::temp_dir().join(format!("pbb-{tag}-{}-{nanos}", std::process::id()));
        fs::create_dir_all(&path).unwrap();
        path
    }

    #[test]
    fn normalizes_quality() {
        assert_eq!(normalize_quality("HD"), QUALITY_HD);
        assert_eq!(normalize_quality(" sd "), QUALITY_SD);
        assert_eq!(normalize_quality("4k"), QUALITY_AUTO);
        assert_eq!(normalize_quality(""), QUALITY_AUTO);
    }

    #[test]
    fn persists_default_quality() {
        let dir = test_dir("quality");
        let dir_str = dir.to_string_lossy().into_owned();
        assert_eq!(load_default_quality(&dir_str), QUALITY_AUTO);
        save_default_quality(&dir_str, "hd").unwrap();
        assert_eq!(load_default_quality(&dir_str), QUALITY_HD);
        save_default_quality(&dir_str, "nope").unwrap();
        assert_eq!(load_default_quality(&dir_str), QUALITY_AUTO);
        fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn config_has_four_streams_and_keys() {
        let json = stream_config_json();
        assert_eq!(json.matches(r#""url":"#).count(), 4);
        assert_eq!(json.matches(r#""kid":"#).count(), 4);
        assert_eq!(json.matches(r#""key":"#).count(), 4);
        assert!(json.contains("pbbcollab30allaccesslivestream/manifest.mpd"));
        assert!(json.contains("pinoybigbrothercollabedition30live/manifest.mpd"));
    }
}
