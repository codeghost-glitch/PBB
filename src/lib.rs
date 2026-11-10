#![allow(non_snake_case)]

use jni::objects::{JClass, JString};
use jni::sys::{jfloat, jstring};
use jni::JNIEnv;
use std::fs;
use std::path::{Path, PathBuf};

const STREAM_CONFIG_JSON: &str = r#"{
  "app": "PBB",
  "streams": [
    {
      "name": "PBB All Access",
      "url": "https://absp-live.akamaized.net/dash/live/2099522/pbbcollab30allaccesslivestream/manifest.mpd",
      "kid": "8150b74d16324bb4b2ff62e3e202fc7a",
      "key": "0c5ed4f9db9d4ee61bbc91b3a51ef8b5"
    },
    {
      "name": "PBB Dining Area",
      "url": "https://absp-live.akamaized.net/dash/live/2099522/pbbcollab30allaccesslivestream-diningarea/manifest.mpd",
      "kid": "ea6588d030c94ca89925ac0c49974430",
      "key": "3e6de4afee1eff8e14f9506b76ac9ee6"
    },
    {
      "name": "PBB External Den",
      "url": "https://absp-live.akamaized.net/dash/live/2099522/pbbcollab30allaccesslivestream-externalden/manifest.mpd",
      "kid": "b750a40736a24e60b5a4f256208b92c5",
      "key": "af07292597f692726dcac2bd297ddf8e"
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

const VOLUME_FILE: &str = "app_volume.txt";
const BRIGHTNESS_FILE: &str = "app_brightness.txt";

fn setting_path(files_dir: &str, name: &str) -> PathBuf {
    Path::new(files_dir).join(name)
}

fn read_value(files_dir: &str, name: &str) -> Option<String> {
    fs::read_to_string(setting_path(files_dir, name)).ok()
}

fn write_value(files_dir: &str, name: &str, value: &str) -> std::io::Result<()> {
    fs::write(setting_path(files_dir, name), value)
}

fn load_default_quality(files_dir: &str) -> String {
    read_value(files_dir, "default_quality.txt")
        .map(|value| normalize_quality(&value))
        .unwrap_or_else(|| QUALITY_AUTO.to_string())
}

fn save_default_quality(files_dir: &str, quality: &str) -> std::io::Result<()> {
    write_value(
        files_dir,
        "default_quality.txt",
        &normalize_quality(quality),
    )
}

/// Parses a stored 0..1 level. Returns None for anything unusable.
fn parse_unit(raw: &str) -> Option<f32> {
    raw.trim()
        .parse::<f32>()
        .ok()
        .filter(|value| value.is_finite())
        .map(|value| value.clamp(0.0, 1.0))
}

/// A level the user has set, or -1.0 when the app has never stored one.
fn load_unit(files_dir: &str, name: &str) -> f32 {
    read_value(files_dir, name)
        .and_then(|raw| parse_unit(&raw))
        .unwrap_or(-1.0)
}

fn save_unit(files_dir: &str, name: &str, value: f32) {
    if !value.is_finite() {
        return;
    }
    let _ = write_value(files_dir, name, &format!("{}", value.clamp(0.0, 1.0)));
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

fn java_unit<'local>(env: &mut JNIEnv<'local>, files_dir: &JString<'local>, name: &str) -> f32 {
    let dir: String = env
        .get_string(files_dir)
        .map(String::from)
        .unwrap_or_default();
    load_unit(&dir, name)
}

fn store_unit<'local>(
    env: &mut JNIEnv<'local>,
    files_dir: &JString<'local>,
    name: &str,
    value: jfloat,
) {
    let dir: String = env
        .get_string(files_dir)
        .map(String::from)
        .unwrap_or_default();
    save_unit(&dir, name, value);
}

#[no_mangle]
pub extern "system" fn Java_com_pbb_live_MainActivity_appVolume<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    files_dir: JString<'local>,
) -> jfloat {
    java_unit(&mut env, &files_dir, VOLUME_FILE)
}

#[no_mangle]
pub extern "system" fn Java_com_pbb_live_MainActivity_setAppVolume<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    files_dir: JString<'local>,
    value: jfloat,
) {
    store_unit(&mut env, &files_dir, VOLUME_FILE, value);
}

#[no_mangle]
pub extern "system" fn Java_com_pbb_live_MainActivity_appBrightness<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    files_dir: JString<'local>,
) -> jfloat {
    java_unit(&mut env, &files_dir, BRIGHTNESS_FILE)
}

#[no_mangle]
pub extern "system" fn Java_com_pbb_live_MainActivity_setAppBrightness<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    files_dir: JString<'local>,
    value: jfloat,
) {
    store_unit(&mut env, &files_dir, BRIGHTNESS_FILE, value);
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
    fn parses_unit_levels() {
        assert_eq!(parse_unit("0.5"), Some(0.5));
        assert_eq!(parse_unit(" 1 "), Some(1.0));
        assert_eq!(parse_unit("2.0"), Some(1.0));
        assert_eq!(parse_unit("-3"), Some(0.0));
        assert_eq!(parse_unit("abc"), None);
        assert_eq!(parse_unit("NaN"), None);
    }

    #[test]
    fn remembers_app_levels() {
        let dir = test_dir("levels");
        let dir_str = dir.to_string_lossy().into_owned();
        assert_eq!(load_unit(&dir_str, VOLUME_FILE), -1.0);
        save_unit(&dir_str, VOLUME_FILE, 0.42);
        assert_eq!(load_unit(&dir_str, VOLUME_FILE), 0.42);
        save_unit(&dir_str, BRIGHTNESS_FILE, 0.9);
        assert_eq!(load_unit(&dir_str, BRIGHTNESS_FILE), 0.9);
        save_unit(&dir_str, VOLUME_FILE, 5.0);
        assert_eq!(load_unit(&dir_str, VOLUME_FILE), 1.0);
        fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn config_has_three_streams_and_keys() {
        let json = stream_config_json();
        assert_eq!(json.matches(r#""url":"#).count(), 3);
        assert_eq!(json.matches(r#""kid":"#).count(), 3);
        assert_eq!(json.matches(r#""key":"#).count(), 3);
        assert!(json.contains("pbbcollab30allaccesslivestream/manifest.mpd"));
        assert!(json.contains("pbbcollab30allaccesslivestream-diningarea/manifest.mpd"));
        assert!(json.contains("pbbcollab30allaccesslivestream-externalden/manifest.mpd"));
        assert!(!json.contains("pinoybigbrothercollabedition30live/manifest.mpd"));
    }
}
