//! Bridge JNI: kelas Kotlin `id.vedit.editor.engine.NativeBridge` (object dengan `@JvmStatic external fun`).
//! Semua fungsi menerima/mengembalikan String JSON (kontrak versi `api_version()`), tidak pernah melempar
//! exception dan tidak pernah membiarkan panic keluar. Handle sesi berupa integer, bukan pointer.
pub mod registry;

use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;

fn read(env: &mut JNIEnv, s: &JString) -> Result<String, String> {
    env.get_string(s).map(String::from).map_err(|e| e.to_string())
}

fn out(env: &mut JNIEnv, s: String) -> jstring {
    match env.new_string(s) {
        Ok(j) => j.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn with_str(env: &mut JNIEnv, s: &JString, f: impl FnOnce(&str) -> String) -> jstring {
    let r = match read(env, s) {
        Ok(text) => registry::guarded(|| f(&text)),
        Err(m) => registry::err("bad_argument", &m),
    };
    out(env, r)
}

#[no_mangle]
pub extern "system" fn Java_id_vedit_editor_engine_NativeBridge_nativeApiVersion(_env: JNIEnv, _c: JClass) -> jint {
    registry::api_version()
}

#[no_mangle]
pub extern "system" fn Java_id_vedit_editor_engine_NativeBridge_nativeOpen(
    mut env: JNIEnv,
    _c: JClass,
    json: JString,
) -> jstring {
    with_str(&mut env, &json, registry::open)
}

#[no_mangle]
pub extern "system" fn Java_id_vedit_editor_engine_NativeBridge_nativeApply(
    mut env: JNIEnv,
    _c: JClass,
    handle: jlong,
    command: JString,
    now_ms: jlong,
) -> jstring {
    with_str(&mut env, &command, |c| registry::apply(handle, c, now_ms))
}

#[no_mangle]
pub extern "system" fn Java_id_vedit_editor_engine_NativeBridge_nativeUndo(
    mut env: JNIEnv,
    _c: JClass,
    handle: jlong,
) -> jstring {
    let r = registry::undo(handle);
    out(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_id_vedit_editor_engine_NativeBridge_nativeRedo(
    mut env: JNIEnv,
    _c: JClass,
    handle: jlong,
) -> jstring {
    let r = registry::redo(handle);
    out(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_id_vedit_editor_engine_NativeBridge_nativeSnapshot(
    mut env: JNIEnv,
    _c: JClass,
    handle: jlong,
) -> jstring {
    let r = registry::snapshot(handle);
    out(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_id_vedit_editor_engine_NativeBridge_nativeClose(_env: JNIEnv, _c: JClass, handle: jlong) {
    let _ = std::panic::catch_unwind(|| registry::close(handle));
}

#[no_mangle]
pub extern "system" fn Java_id_vedit_editor_engine_NativeBridge_nativeCanvasSize(
    mut env: JNIEnv,
    _c: JClass,
    aspect: JString,
    fit: JString,
    quality: JString,
) -> jstring {
    let r = match (read(&mut env, &aspect), read(&mut env, &fit), read(&mut env, &quality)) {
        (Ok(a), Ok(f), Ok(q)) => registry::canvas_size(&a, &f, &q),
        _ => registry::err("bad_argument", "Argumen string tidak terbaca"),
    };
    out(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_id_vedit_editor_engine_NativeBridge_nativePlan(
    mut env: JNIEnv,
    _c: JClass,
    json: JString,
) -> jstring {
    with_str(&mut env, &json, registry::plan)
}

#[no_mangle]
pub extern "system" fn Java_id_vedit_editor_engine_NativeBridge_nativeLayout(
    mut env: JNIEnv,
    _c: JClass,
    json: JString,
) -> jstring {
    with_str(&mut env, &json, registry::layout)
}
