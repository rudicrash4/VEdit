//! Registri sesi engine berbasis handle integer. Logika murni (tanpa JNI) agar bisa diuji unit.
//! Handle tidak pernah dipakai ulang dan bukan pointer, jadi handle basi hanya menghasilkan galat (bukan crash).
use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use vedit_core::canvas::{self, OutputQuality};
use vedit_core::engine::Engine;
use vedit_core::json::Value;
use vedit_core::model::{AspectPreset, CanvasSettings, FitMode};

type Sessions = Mutex<HashMap<i64, Arc<Mutex<Engine>>>>;

fn sessions() -> &'static Sessions {
    static S: OnceLock<Sessions> = OnceLock::new();
    S.get_or_init(|| Mutex::new(HashMap::new()))
}

static NEXT: AtomicI64 = AtomicI64::new(1);

pub fn err(code: &str, message: &str) -> String {
    Value::Obj(vec![
        ("ok".into(), Value::Bool(false)),
        ("code".into(), Value::str(code)),
        ("message".into(), Value::str(message)),
    ])
    .to_json()
}

/// Menjalankan blok dan mengubah panic apa pun menjadi galat JSON (tidak pernah merambat melintasi JNI).
pub fn guarded(f: impl FnOnce() -> String) -> String {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(s) => s,
        Err(_) => err("native_panic", "Galat internal engine native"),
    }
}

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    // Mutex teracuni (panic sebelumnya) dipulihkan; state engine tetap konsisten karena operasi bersifat all-or-nothing.
    m.lock().unwrap_or_else(|p| p.into_inner())
}

fn session(handle: i64) -> Option<Arc<Mutex<Engine>>> {
    lock(sessions()).get(&handle).cloned()
}

pub fn api_version() -> i32 {
    vedit_core::API_VERSION
}

pub fn open(project_json: &str) -> String {
    guarded(|| match Engine::open(project_json) {
        Ok(e) => {
            let h = NEXT.fetch_add(1, Ordering::Relaxed);
            lock(sessions()).insert(h, Arc::new(Mutex::new(e)));
            Value::Obj(vec![("ok".into(), Value::Bool(true)), ("handle".into(), Value::int(h))]).to_json()
        }
        Err(m) => err("invalid_project", &m),
    })
}

pub fn with_engine(handle: i64, f: impl FnOnce(&mut Engine) -> String) -> String {
    guarded(|| match session(handle) {
        None => err("bad_handle", "Sesi engine tidak ada atau sudah ditutup"),
        Some(s) => f(&mut lock(&s)),
    })
}

pub fn apply(handle: i64, command_json: &str, now_ms: i64) -> String {
    with_engine(handle, |e| e.apply_json(command_json, now_ms))
}
pub fn undo(handle: i64) -> String {
    with_engine(handle, |e| e.undo_json())
}
pub fn redo(handle: i64) -> String {
    with_engine(handle, |e| e.redo_json())
}
pub fn snapshot(handle: i64) -> String {
    with_engine(handle, |e| e.snapshot_json())
}

pub fn close(handle: i64) {
    lock(sessions()).remove(&handle);
}

pub fn open_sessions() -> usize {
    lock(sessions()).len()
}

/// Ukuran frame untuk rasio + kualitas (tanpa sesi). `quality` = PREVIEW | P720 | P1080.
pub fn canvas_size(aspect: &str, fit: &str, quality: &str) -> String {
    guarded(|| {
        let Some(a) = AspectPreset::from_name(aspect) else {
            return err("bad_argument", "Aspect tidak dikenal");
        };
        let Some(q) = OutputQuality::from_name(quality) else {
            return err("bad_argument", "Kualitas tidak dikenal");
        };
        let f = if fit == "FILL" { FitMode::Fill } else { FitMode::Fit };
        let s = canvas::size_for(CanvasSettings { aspect: a, fit: f }, q);
        Value::Obj(vec![
            ("ok".into(), Value::Bool(true)),
            ("width".into(), Value::int(s.width as i64)),
            ("height".into(), Value::int(s.height as i64)),
            ("exportEnabled".into(), Value::Bool(q.export_enabled())),
        ])
        .to_json()
    })
}

pub fn plan(project_json: &str) -> String {
    guarded(|| vedit_core::engine::plan_json(project_json).unwrap_or_else(|m| err("invalid_project", &m)))
}

pub fn layout(args_json: &str) -> String {
    guarded(|| vedit_core::engine::layout_json(args_json).unwrap_or_else(|m| err("bad_argument", &m)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use vedit_core::engine::project_to_value;
    use vedit_core::json::parse;
    use vedit_core::model::Project;

    fn open_empty() -> i64 {
        let json = project_to_value(&Project::create("p", "x", 0)).to_json();
        parse(&open(&json)).unwrap().get("handle").unwrap().as_i64().unwrap()
    }

    #[test]
    fn open_apply_close_lifecycle_and_stale_handle() {
        let h = open_empty();
        let v = parse(&snapshot(h)).unwrap();
        assert_eq!(v.get("ok").unwrap().as_bool(), Some(true));
        close(h);
        let v = parse(&snapshot(h)).unwrap();
        assert_eq!(v.get("code").unwrap().as_str(), Some("bad_handle"));
        close(h); // ganda: aman
        assert_eq!(
            parse(&apply(999_999, "{}", 0)).unwrap().get("code").unwrap().as_str(),
            Some("bad_handle")
        );
    }

    #[test]
    fn invalid_project_is_an_error_not_a_panic() {
        for bad in ["", "{", "[]", "{\"schemaVersion\":2}"] {
            assert_eq!(
                parse(&open(bad)).unwrap().get("ok").unwrap().as_bool(),
                Some(false),
                "{bad}"
            );
        }
    }

    #[test]
    fn panics_are_contained() {
        let r = guarded(|| panic!("boom"));
        assert_eq!(parse(&r).unwrap().get("code").unwrap().as_str(), Some("native_panic"));
    }

    #[test]
    fn concurrent_sessions_are_independent() {
        let handles: Vec<_> = (0..8).map(|_| std::thread::spawn(open_empty)).collect();
        let hs: Vec<i64> = handles.into_iter().map(|t| t.join().unwrap()).collect();
        let mut sorted = hs.clone();
        sorted.sort();
        sorted.dedup();
        assert_eq!(sorted.len(), 8);
        for h in hs {
            close(h);
        }
    }

    #[test]
    fn plan_and_layout_api() {
        let json = project_to_value(&Project::create("p", "x", 0)).to_json();
        let v = parse(&plan(&json)).unwrap();
        assert_eq!(v.get("ok").unwrap().as_bool(), Some(true));
        assert_eq!(v.get("durationUs").unwrap().as_i64(), Some(0));
        assert_eq!(parse(&plan("{")).unwrap().get("ok").unwrap().as_bool(), Some(false));
        let l = parse(&layout(
            r#"{"viewW":100,"viewH":100,"srcW":100,"srcH":100,"rotationDeg":0,"crop":null,"fit":"FIT"}"#,
        ))
        .unwrap();
        assert_eq!(l.get("ok").unwrap().as_bool(), Some(true));
        assert_eq!(l.get("m").unwrap().as_arr().unwrap().len(), 6);
        for bad in [r#"{}"#, r#"{"viewW":0,"viewH":1,"srcW":1,"srcH":1}"#, "x"] {
            assert_eq!(
                parse(&layout(bad)).unwrap().get("ok").unwrap().as_bool(),
                Some(false),
                "{bad}"
            );
        }
    }

    #[test]
    fn canvas_size_api() {
        let v = parse(&canvas_size("R9_16", "FIT", "P720")).unwrap();
        assert_eq!(
            (v.get("width").unwrap().as_i64(), v.get("height").unwrap().as_i64()),
            (Some(720), Some(1280))
        );
        assert_eq!(v.get("exportEnabled").unwrap().as_bool(), Some(true));
        assert_eq!(
            parse(&canvas_size("R1_1", "FIT", "P1080"))
                .unwrap()
                .get("exportEnabled")
                .unwrap()
                .as_bool(),
            Some(false)
        );
        assert_eq!(
            parse(&canvas_size("X", "FIT", "P720"))
                .unwrap()
                .get("ok")
                .unwrap()
                .as_bool(),
            Some(false)
        );
    }
}
