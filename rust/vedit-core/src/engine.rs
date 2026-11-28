//! Sesi engine: pemilik tunggal state proyek + riwayat. Antarmuka berbasis JSON (kontrak versi `API_VERSION`).
use crate::history::History;
use crate::json::{parse, Value};
use crate::model::*;
use crate::ops::{self, Edit, EditError};

// ------------------------------------------------------------------ project <-> JSON

fn media_type_name(t: MediaType) -> &'static str {
    ops::media_name(t)
}

fn media_type_from(s: &str) -> Result<MediaType, String> {
    Ok(match s {
        "VIDEO" => MediaType::Video,
        "IMAGE" => MediaType::Image,
        "AUDIO" => MediaType::Audio,
        _ => return Err(format!("MediaType tidak dikenal: {s}")),
    })
}

fn track_type_from(s: &str) -> Result<TrackType, String> {
    Ok(match s {
        "VIDEO" => TrackType::Video,
        "AUDIO" => TrackType::Audio,
        _ => return Err(format!("TrackType tidak dikenal: {s}")),
    })
}

fn kv(pairs: Vec<(&str, Value)>) -> Value {
    Value::Obj(pairs.into_iter().map(|(k, v)| (k.to_string(), v)).collect())
}

fn field<'a>(v: &'a Value, k: &str) -> Result<&'a Value, String> {
    v.get(k).ok_or_else(|| format!("Field '{k}' tidak ada"))
}
fn f_str(v: &Value, k: &str) -> Result<String, String> {
    field(v, k)?
        .as_str()
        .map(|s| s.to_string())
        .ok_or_else(|| format!("Field '{k}' harus string"))
}
fn f_i64(v: &Value, k: &str) -> Result<i64, String> {
    field(v, k)?
        .as_i64()
        .ok_or_else(|| format!("Field '{k}' harus integer"))
}
fn f_i64_or(v: &Value, k: &str, d: i64) -> Result<i64, String> {
    match v.get(k) {
        None => Ok(d),
        Some(x) => x.as_i64().ok_or_else(|| format!("Field '{k}' harus integer")),
    }
}
fn f_f32_or(v: &Value, k: &str, d: f32) -> Result<f32, String> {
    match v.get(k) {
        None => Ok(d),
        Some(x) => x.as_f32().ok_or_else(|| format!("Field '{k}' harus angka")),
    }
}

pub fn source_to_value(s: &MediaSource) -> Value {
    kv(vec![
        ("id", Value::str(&s.id)),
        ("uri", Value::str(&s.uri)),
        ("type", Value::str(media_type_name(s.media_type))),
        ("displayName", Value::str(&s.display_name)),
        ("durationUs", Value::int(s.duration_us)),
        ("width", Value::int(s.width as i64)),
        ("height", Value::int(s.height as i64)),
        ("hasAudio", Value::Bool(s.has_audio)),
    ])
}

pub fn source_from_value(v: &Value) -> Result<MediaSource, String> {
    Ok(MediaSource {
        id: f_str(v, "id")?,
        uri: f_str(v, "uri")?,
        media_type: media_type_from(&f_str(v, "type")?)?,
        display_name: f_str(v, "displayName")?,
        duration_us: f_i64(v, "durationUs")?,
        width: f_i64_or(v, "width", 0)? as i32,
        height: f_i64_or(v, "height", 0)? as i32,
        has_audio: v.get("hasAudio").and_then(|x| x.as_bool()).unwrap_or(false),
    })
}

fn crop_from_value(v: &Value) -> Result<Option<Crop>, String> {
    if v.is_null() {
        return Ok(None);
    }
    Crop::new(
        f_f32_or(v, "left", 0.0)?,
        f_f32_or(v, "top", 0.0)?,
        f_f32_or(v, "right", 0.0)?,
        f_f32_or(v, "bottom", 0.0)?,
    )
    .map(Some)
}

fn clip_to_value(c: &Clip) -> Value {
    kv(vec![
        ("id", Value::str(&c.id)),
        ("sourceId", Value::str(&c.source_id)),
        ("startUs", Value::int(c.start_us)),
        ("sourceInUs", Value::int(c.source_in_us)),
        ("sourceOutUs", Value::int(c.source_out_us)),
        ("speed", Value::f32(c.speed)),
        ("volume", Value::f32(c.volume)),
        ("rotationDeg", Value::int(c.rotation_deg as i64)),
        (
            "crop",
            match c.crop {
                None => Value::Null,
                Some(k) => kv(vec![
                    ("left", Value::f32(k.left)),
                    ("top", Value::f32(k.top)),
                    ("right", Value::f32(k.right)),
                    ("bottom", Value::f32(k.bottom)),
                ]),
            },
        ),
    ])
}

fn clip_from_value(v: &Value) -> Result<Clip, String> {
    Ok(Clip {
        id: f_str(v, "id")?,
        source_id: f_str(v, "sourceId")?,
        start_us: f_i64(v, "startUs")?,
        source_in_us: f_i64(v, "sourceInUs")?,
        source_out_us: f_i64(v, "sourceOutUs")?,
        speed: f_f32_or(v, "speed", 1.0)?,
        volume: f_f32_or(v, "volume", 1.0)?,
        rotation_deg: f_i64_or(v, "rotationDeg", 0)? as i32,
        crop: match v.get("crop") {
            None => None,
            Some(c) => crop_from_value(c)?,
        },
    })
}

pub fn project_to_value(p: &Project) -> Value {
    kv(vec![
        ("schemaVersion", Value::int(p.schema_version as i64)),
        ("id", Value::str(&p.id)),
        ("name", Value::str(&p.name)),
        ("sources", Value::Arr(p.sources.iter().map(source_to_value).collect())),
        (
            "tracks",
            Value::Arr(
                p.tracks
                    .iter()
                    .map(|t| {
                        kv(vec![
                            ("id", Value::str(&t.id)),
                            ("type", Value::str(ops::track_name(t.track_type))),
                            ("clips", Value::Arr(t.clips.iter().map(clip_to_value).collect())),
                        ])
                    })
                    .collect(),
            ),
        ),
        ("createdAtMs", Value::int(p.created_at_ms)),
        ("modifiedAtMs", Value::int(p.modified_at_ms)),
        (
            "canvas",
            kv(vec![
                ("aspect", Value::str(p.canvas.aspect.name())),
                (
                    "fit",
                    Value::str(if p.canvas.fit == FitMode::Fill { "FILL" } else { "FIT" }),
                ),
            ]),
        ),
    ])
}

fn canvas_from_value(v: &Value) -> Result<CanvasSettings, String> {
    let aspect = match v.get("aspect") {
        None => AspectPreset::R16_9,
        Some(a) => {
            let s = a.as_str().ok_or("canvas.aspect harus string")?;
            AspectPreset::from_name(s).ok_or_else(|| format!("Aspect tidak dikenal: {s}"))?
        }
    };
    let fit = match v.get("fit").and_then(|x| x.as_str()) {
        None | Some("FIT") => FitMode::Fit,
        Some("FILL") => FitMode::Fill,
        Some(o) => return Err(format!("FitMode tidak dikenal: {o}")),
    };
    Ok(CanvasSettings { aspect, fit })
}

/// Membaca dokumen proyek schema 2. Dokumen schema lain ditolak (migrasi dilakukan di sisi Kotlin sebelum dikirim).
pub fn project_from_value(v: &Value) -> Result<Project, String> {
    let schema = f_i64(v, "schemaVersion")? as i32;
    if schema != CURRENT_SCHEMA {
        return Err(format!(
            "Schema {schema} tidak didukung core (butuh {CURRENT_SCHEMA}); migrasikan dulu"
        ));
    }
    let sources = field(v, "sources")
        .ok()
        .and_then(|s| s.as_arr())
        .unwrap_or(&[])
        .iter()
        .map(source_from_value)
        .collect::<Result<Vec<_>, _>>()?;
    let mut tracks = Vec::new();
    for t in field(v, "tracks")?.as_arr().ok_or("tracks harus array")? {
        let clips = match t.get("clips").and_then(|c| c.as_arr()) {
            Some(a) => a.iter().map(clip_from_value).collect::<Result<Vec<_>, _>>()?,
            None => vec![],
        };
        tracks.push(Track {
            id: f_str(t, "id")?,
            track_type: track_type_from(&f_str(t, "type")?)?,
            clips,
        });
    }
    let p = Project {
        schema_version: schema,
        id: f_str(v, "id")?,
        name: f_str(v, "name")?,
        sources,
        tracks,
        created_at_ms: f_i64(v, "createdAtMs")?,
        modified_at_ms: f_i64(v, "modifiedAtMs")?,
        canvas: match v.get("canvas") {
            None => CanvasSettings::default(),
            Some(c) => canvas_from_value(c)?,
        },
    };
    let errs = p.validate();
    if !errs.is_empty() {
        return Err(format!("Proyek tidak valid: {}", errs.join("; ")));
    }
    Ok(p)
}

// ------------------------------------------------------------------ commands

pub enum Command {
    AddClip {
        track_id: String,
        source_id: String,
        new_clip_id: String,
        start_us: Option<i64>,
    },
    Split {
        clip_id: String,
        at_us: i64,
        new_clip_id: String,
    },
    TrimStart {
        clip_id: String,
        new_start_us: i64,
    },
    TrimEnd {
        clip_id: String,
        new_end_us: i64,
    },
    Delete {
        clip_id: String,
        ripple: bool,
    },
    Duplicate {
        clip_id: String,
        new_clip_id: String,
    },
    Move {
        clip_id: String,
        new_start_us: i64,
    },
    Reorder {
        clip_id: String,
        new_index: i32,
    },
    SetRotation {
        clip_id: String,
        degrees: i32,
    },
    SetCrop {
        clip_id: String,
        crop: Option<Crop>,
    },
    SetCanvas {
        canvas: CanvasSettings,
    },
    AddSources {
        sources: Vec<MediaSource>,
    },
}

pub fn command_from_value(v: &Value) -> Result<Command, String> {
    let op = f_str(v, "op")?;
    Ok(match op.as_str() {
        "addClip" => Command::AddClip {
            track_id: f_str(v, "trackId")?,
            source_id: f_str(v, "sourceId")?,
            new_clip_id: f_str(v, "newClipId")?,
            start_us: match v.get("startUs") {
                None => None,
                Some(x) if x.is_null() => None,
                Some(x) => Some(x.as_i64().ok_or("startUs harus integer")?),
            },
        },
        "split" => Command::Split {
            clip_id: f_str(v, "clipId")?,
            at_us: f_i64(v, "atUs")?,
            new_clip_id: f_str(v, "newClipId")?,
        },
        "trimStart" => Command::TrimStart {
            clip_id: f_str(v, "clipId")?,
            new_start_us: f_i64(v, "newStartUs")?,
        },
        "trimEnd" => Command::TrimEnd {
            clip_id: f_str(v, "clipId")?,
            new_end_us: f_i64(v, "newEndUs")?,
        },
        "delete" => Command::Delete {
            clip_id: f_str(v, "clipId")?,
            ripple: v.get("ripple").and_then(|x| x.as_bool()).unwrap_or(false),
        },
        "duplicate" => Command::Duplicate {
            clip_id: f_str(v, "clipId")?,
            new_clip_id: f_str(v, "newClipId")?,
        },
        "move" => Command::Move {
            clip_id: f_str(v, "clipId")?,
            new_start_us: f_i64(v, "newStartUs")?,
        },
        "reorder" => Command::Reorder {
            clip_id: f_str(v, "clipId")?,
            new_index: f_i64(v, "newIndex")? as i32,
        },
        "setRotation" => Command::SetRotation {
            clip_id: f_str(v, "clipId")?,
            degrees: f_i64(v, "degrees")? as i32,
        },
        "setCrop" => Command::SetCrop {
            clip_id: f_str(v, "clipId")?,
            crop: match v.get("crop") {
                None => None,
                Some(c) => crop_from_value(c)?,
            },
        },
        "setCanvas" => Command::SetCanvas {
            canvas: canvas_from_value(v)?,
        },
        "addSources" => Command::AddSources {
            sources: field(v, "sources")?
                .as_arr()
                .ok_or("sources harus array")?
                .iter()
                .map(source_from_value)
                .collect::<Result<Vec<_>, _>>()?,
        },
        other => return Err(format!("Operasi tidak dikenal: {other}")),
    })
}

pub fn run_command(p: &Project, c: Command) -> Edit {
    match c {
        Command::AddClip {
            track_id,
            source_id,
            new_clip_id,
            start_us,
        } => ops::add_clip(p, &track_id, &source_id, &new_clip_id, start_us),
        Command::Split {
            clip_id,
            at_us,
            new_clip_id,
        } => ops::split(p, &clip_id, at_us, &new_clip_id),
        Command::TrimStart { clip_id, new_start_us } => ops::trim_start(p, &clip_id, new_start_us),
        Command::TrimEnd { clip_id, new_end_us } => ops::trim_end(p, &clip_id, new_end_us),
        Command::Delete { clip_id, ripple } => ops::delete(p, &clip_id, ripple),
        Command::Duplicate { clip_id, new_clip_id } => ops::duplicate(p, &clip_id, &new_clip_id),
        Command::Move { clip_id, new_start_us } => ops::move_clip(p, &clip_id, new_start_us),
        Command::Reorder { clip_id, new_index } => ops::reorder(p, &clip_id, new_index),
        Command::SetRotation { clip_id, degrees } => ops::set_rotation(p, &clip_id, degrees),
        Command::SetCrop { clip_id, crop } => ops::set_crop(p, &clip_id, crop),
        Command::SetCanvas { canvas } => ops::set_canvas(p, canvas),
        Command::AddSources { sources } => ops::add_sources(p, sources),
    }
}

// ------------------------------------------------------------------ session

pub struct Engine {
    history: History,
}

fn envelope_err(code: &str, message: &str) -> String {
    kv(vec![
        ("ok", Value::Bool(false)),
        ("code", Value::str(code)),
        ("message", Value::str(message)),
    ])
    .to_json()
}

impl Engine {
    pub fn open(project_json: &str) -> Result<Engine, String> {
        let v = parse(project_json)?;
        Ok(Engine {
            history: History::new(project_from_value(&v)?),
        })
    }

    pub fn from_project(p: Project) -> Engine {
        Engine {
            history: History::new(p),
        }
    }

    pub fn project(&self) -> &Project {
        self.history.current()
    }

    fn snapshot(&self, selected: Option<&str>) -> String {
        kv(vec![
            ("ok", Value::Bool(true)),
            ("project", project_to_value(self.history.current())),
            ("canUndo", Value::Bool(self.history.can_undo())),
            ("canRedo", Value::Bool(self.history.can_redo())),
            ("selected", selected.map(Value::str).unwrap_or(Value::Null)),
        ])
        .to_json()
    }

    pub fn snapshot_json(&self) -> String {
        self.snapshot(None)
    }

    /// Menerapkan perintah JSON. Berhasil: stempel `modifiedAtMs = now_ms`, masuk riwayat, kembalikan snapshot.
    /// Ditolak: kembalikan galat dan state TIDAK berubah.
    pub fn apply_json(&mut self, command_json: &str, now_ms: i64) -> String {
        let cmd = match parse(command_json).and_then(|v| command_from_value(&v)) {
            Ok(c) => c,
            Err(m) => return envelope_err("bad_command", &m),
        };
        match run_command(self.history.current(), cmd) {
            Ok(done) => {
                let mut p = done.project;
                p.modified_at_ms = now_ms;
                self.history.apply(p);
                self.snapshot(done.selected.as_deref())
            }
            Err(EditError { code, message }) => envelope_err(code, &message),
        }
    }

    pub fn undo_json(&mut self) -> String {
        self.history.undo();
        self.snapshot(None)
    }

    pub fn redo_json(&mut self) -> String {
        self.history.redo();
        self.snapshot(None)
    }
}

// ------------------------------------------------------------------ rencana pemutaran (Preview V2)

use crate::playback::{self, Segment};

fn crop_to_value(c: Option<Crop>) -> Value {
    match c {
        None => Value::Null,
        Some(k) => kv(vec![
            ("left", Value::f32(k.left)),
            ("top", Value::f32(k.top)),
            ("right", Value::f32(k.right)),
            ("bottom", Value::f32(k.bottom)),
        ]),
    }
}

fn segment_to_value(s: &Segment) -> Value {
    kv(vec![
        ("kind", Value::str(s.kind.name())),
        ("clipId", Value::str(&s.clip_id)),
        ("sourceId", Value::str(&s.source_id)),
        ("startUs", Value::int(s.start_us)),
        ("endUs", Value::int(s.end_us)),
        ("srcInUs", Value::int(s.src_in_us)),
        ("srcOutUs", Value::int(s.src_out_us)),
        ("speed", Value::f32(s.speed)),
        ("volume", Value::f32(s.volume)),
        ("rotationDeg", Value::int(s.rotation_deg as i64)),
        ("crop", crop_to_value(s.crop)),
    ])
}

/// Rencana pemutaran dari dokumen proyek (schema terkini). Hasil: amplop JSON.
pub fn plan_json(project_json: &str) -> Result<String, String> {
    let p = project_from_value(&parse(project_json)?)?;
    let plan = playback::build_plan(&p);
    Ok(kv(vec![
        ("ok", Value::Bool(true)),
        ("video", Value::Arr(plan.video.iter().map(segment_to_value).collect())),
        ("audio", Value::Arr(plan.audio.iter().map(segment_to_value).collect())),
        ("durationUs", Value::int(plan.duration_us)),
    ])
    .to_json())
}

fn f_f64(v: &Value, k: &str) -> Result<f64, String> {
    field(v, k)?.as_f64().ok_or_else(|| format!("Field '{k}' harus angka"))
}

/// Geometri penempatan frame. Argumen: {viewW,viewH,srcW,srcH,rotationDeg,crop|null,fit}.
pub fn layout_json(args_json: &str) -> Result<String, String> {
    let v = parse(args_json)?;
    let (vw, vh, sw, sh) = (
        f_f64(&v, "viewW")?,
        f_f64(&v, "viewH")?,
        f_f64(&v, "srcW")?,
        f_f64(&v, "srcH")?,
    );
    if !(vw > 0.0 && vh > 0.0 && sw > 0.0 && sh > 0.0) {
        return Err("Ukuran view/sumber harus positif".into());
    }
    let rot = f_i64_or(&v, "rotationDeg", 0)? as i32;
    let crop = match v.get("crop") {
        None => None,
        Some(c) => crop_from_value(c)?,
    };
    let fit = if v.get("fit").and_then(|x| x.as_str()) == Some("FILL") {
        FitMode::Fill
    } else {
        FitMode::Fit
    };
    let l = playback::layout(vw, vh, sw, sh, rot, crop, fit);
    Ok(kv(vec![
        ("ok", Value::Bool(true)),
        ("m", Value::Arr(l.m.iter().map(|x| Value::f64(*x)).collect())),
        ("clip", Value::Arr(l.clip.iter().map(|x| Value::f64(*x)).collect())),
    ])
    .to_json())
}
