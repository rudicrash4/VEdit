//! Operasi timeline murni; port persis `TimelineEngine.kt`. Setiap operasi mengembalikan Project baru
//! (input tidak diubah) atau galat dengan teks yang sama dengan versi Kotlin.
use crate::model::*;
use crate::time::{round_half_up, EPS_US, MIN_CLIP_US};

#[derive(Clone, Debug, PartialEq)]
pub struct EditOk {
    pub project: Project,
    pub selected: Option<String>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct EditError {
    pub code: &'static str,
    pub message: String,
}

pub type Edit = Result<EditOk, EditError>;

fn err(code: &'static str, msg: &str) -> Edit {
    Err(EditError {
        code,
        message: msg.to_string(),
    })
}

fn ok(project: Project, selected: Option<&str>) -> Edit {
    Ok(EditOk {
        project,
        selected: selected.map(|s| s.to_string()),
    })
}

fn with_track(p: &Project, track: Track) -> Project {
    let mut q = p.clone();
    for t in q.tracks.iter_mut() {
        if t.id == track.id {
            let mut clips = track.clips.clone();
            clips.sort_by_key(|c| c.start_us);
            *t = Track {
                id: track.id.clone(),
                track_type: track.track_type,
                clips,
            };
        }
    }
    q
}

fn overlaps(track: &Track, cand: &Clip) -> bool {
    track
        .clips
        .iter()
        .any(|c| c.id != cand.id && c.start_us < cand.end_us() - EPS_US && cand.start_us < c.end_us() - EPS_US)
}

fn replace(track: &Track, clip_id: &str, f: impl Fn(&Clip) -> Clip) -> Track {
    Track {
        id: track.id.clone(),
        track_type: track.track_type,
        clips: track
            .clips
            .iter()
            .map(|c| if c.id == clip_id { f(c) } else { c.clone() })
            .collect(),
    }
}

const NOT_FOUND: &str = "Klip tidak ditemukan";

pub fn add_clip(p: &Project, track_id: &str, source_id: &str, new_clip_id: &str, start_us: Option<i64>) -> Edit {
    let Some(track) = p.tracks.iter().find(|t| t.id == track_id) else {
        return err("track_missing", "Track tidak ada");
    };
    let Some(src) = p.source(source_id) else {
        return err("source_missing", "Sumber media tidak ada");
    };
    let compatible = match track.track_type {
        TrackType::Video => src.media_type == MediaType::Video || src.media_type == MediaType::Image,
        TrackType::Audio => src.media_type == MediaType::Audio,
    };
    if !compatible {
        // Teks memakai nama enum Kotlin (VIDEO/AUDIO/IMAGE) agar identik.
        return Err(EditError {
            code: "type_mismatch",
            message: format!(
                "Tipe media {} tidak cocok untuk track {}",
                media_name(src.media_type),
                track_name(track.track_type)
            ),
        });
    }
    if src.duration_us < MIN_CLIP_US {
        return err("too_short", "Durasi media terlalu pendek");
    }
    let start = start_us.unwrap_or_else(|| track.clips.iter().map(|c| c.end_us()).max().unwrap_or(0));
    if start < 0 {
        return err("negative_position", "Posisi negatif");
    }
    let clip = Clip::new(new_clip_id, source_id, start, 0, src.duration_us);
    if overlaps(track, &clip) {
        return err("overlap", "Tumpang tindih dengan klip lain");
    }
    let mut t = track.clone();
    t.clips.push(clip);
    ok(with_track(p, t), Some(new_clip_id))
}

pub fn media_name(t: MediaType) -> &'static str {
    match t {
        MediaType::Video => "VIDEO",
        MediaType::Image => "IMAGE",
        MediaType::Audio => "AUDIO",
    }
}

pub fn track_name(t: TrackType) -> &'static str {
    match t {
        TrackType::Video => "VIDEO",
        TrackType::Audio => "AUDIO",
    }
}

pub fn split(p: &Project, clip_id: &str, at_us: i64, new_clip_id: &str) -> Edit {
    let Some(track) = p.track_of(clip_id) else {
        return err("not_found", NOT_FOUND);
    };
    let clip = p.clip(clip_id).unwrap();
    if at_us - clip.start_us < MIN_CLIP_US || clip.end_us() - at_us < MIN_CLIP_US {
        return err("split_edge", "Titik potong harus minimal 100 ms dari tepi klip");
    }
    let src_at = clip.timeline_to_source_us(at_us);
    let mut left = clip.clone();
    left.source_out_us = src_at;
    let mut right = clip.clone();
    right.id = new_clip_id.to_string();
    right.start_us = left.end_us();
    right.source_in_us = src_at;
    let mut clips: Vec<Clip> = track.clips.iter().filter(|c| c.id != clip_id).cloned().collect();
    clips.push(left);
    clips.push(right);
    let t = Track {
        id: track.id.clone(),
        track_type: track.track_type,
        clips,
    };
    ok(with_track(p, t), Some(new_clip_id))
}

pub fn trim_start(p: &Project, clip_id: &str, new_start_us: i64) -> Edit {
    let Some(track) = p.track_of(clip_id) else {
        return err("not_found", NOT_FOUND);
    };
    let clip = p.clip(clip_id).unwrap();
    if new_start_us < 0 {
        return err("negative_position", "Posisi negatif");
    }
    let new_in = clip.source_in_us + round_half_up((new_start_us - clip.start_us) as f64 * clip.speed as f64);
    if new_in < 0 {
        return err("no_source_left", "Tidak ada sumber lagi di sisi kiri");
    }
    let mut trimmed = clip.clone();
    trimmed.start_us = new_start_us;
    trimmed.source_in_us = new_in;
    if new_in >= clip.source_out_us || trimmed.duration_us() < MIN_CLIP_US {
        return err("too_short", "Klip akan lebih pendek dari 100 ms");
    }
    if overlaps(track, &trimmed) {
        return err("overlap", "Tumpang tindih dengan klip sebelumnya");
    }
    let t = replace(track, clip_id, |_| trimmed.clone());
    ok(with_track(p, t), Some(clip_id))
}

pub fn trim_end(p: &Project, clip_id: &str, new_end_us: i64) -> Edit {
    let Some(track) = p.track_of(clip_id) else {
        return err("not_found", NOT_FOUND);
    };
    let clip = p.clip(clip_id).unwrap();
    let Some(src) = p.source(&clip.source_id) else {
        return err("source_missing", "Sumber media tidak ada");
    };
    let new_out = clip.source_out_us + round_half_up((new_end_us - clip.end_us()) as f64 * clip.speed as f64);
    if src.media_type != MediaType::Image && new_out > src.duration_us {
        return err("past_source_end", "Melewati akhir media sumber");
    }
    let mut trimmed = clip.clone();
    trimmed.source_out_us = new_out;
    if new_out <= clip.source_in_us || trimmed.duration_us() < MIN_CLIP_US {
        return err("too_short", "Klip akan lebih pendek dari 100 ms");
    }
    if overlaps(track, &trimmed) {
        return err("overlap", "Tumpang tindih dengan klip berikutnya");
    }
    let t = replace(track, clip_id, |_| trimmed.clone());
    ok(with_track(p, t), Some(clip_id))
}

pub fn delete(p: &Project, clip_id: &str, ripple: bool) -> Edit {
    let Some(track) = p.track_of(clip_id) else {
        return err("not_found", NOT_FOUND);
    };
    let clip = p.clip(clip_id).unwrap();
    let dur = clip.duration_us();
    let clips: Vec<Clip> = track
        .clips
        .iter()
        .filter(|c| c.id != clip_id)
        .map(|c| {
            let mut c = c.clone();
            if ripple && c.start_us >= clip.end_us() - EPS_US {
                c.start_us -= dur;
            }
            c
        })
        .collect();
    let t = Track {
        id: track.id.clone(),
        track_type: track.track_type,
        clips,
    };
    ok(with_track(p, t), None)
}

pub fn duplicate(p: &Project, clip_id: &str, new_clip_id: &str) -> Edit {
    let Some(track) = p.track_of(clip_id) else {
        return err("not_found", NOT_FOUND);
    };
    let clip = p.clip(clip_id).unwrap();
    let mut copy = clip.clone();
    copy.id = new_clip_id.to_string();
    copy.start_us = clip.end_us();
    let cd = copy.duration_us();
    let mut clips: Vec<Clip> = track
        .clips
        .iter()
        .map(|c| {
            let mut c = c.clone();
            if c.start_us >= clip.end_us() - EPS_US {
                c.start_us += cd;
            }
            c
        })
        .collect();
    clips.push(copy);
    let t = Track {
        id: track.id.clone(),
        track_type: track.track_type,
        clips,
    };
    ok(with_track(p, t), Some(new_clip_id))
}

pub fn move_clip(p: &Project, clip_id: &str, new_start_us: i64) -> Edit {
    let Some(track) = p.track_of(clip_id) else {
        return err("not_found", NOT_FOUND);
    };
    let clip = p.clip(clip_id).unwrap();
    if new_start_us < 0 {
        return err("negative_position", "Posisi negatif");
    }
    let mut moved = clip.clone();
    moved.start_us = new_start_us;
    if overlaps(track, &moved) {
        return err("overlap", "Tumpang tindih dengan klip lain");
    }
    let t = replace(track, clip_id, |_| moved.clone());
    ok(with_track(p, t), Some(clip_id))
}

pub fn reorder(p: &Project, clip_id: &str, new_index: i32) -> Edit {
    let Some(track) = p.track_of(clip_id) else {
        return err("not_found", NOT_FOUND);
    };
    let mut ordered: Vec<Clip> = track.clips.clone();
    ordered.sort_by_key(|c| c.start_us);
    let clip = ordered.iter().find(|c| c.id == clip_id).unwrap().clone();
    let mut rest: Vec<Clip> = ordered.iter().filter(|c| c.id != clip_id).cloned().collect();
    let idx = (new_index.max(0) as usize).min(rest.len());
    rest.insert(idx, clip);
    let mut cursor = ordered[0].start_us;
    let packed: Vec<Clip> = rest
        .into_iter()
        .map(|mut c| {
            c.start_us = cursor;
            cursor = c.end_us();
            c
        })
        .collect();
    let t = Track {
        id: track.id.clone(),
        track_type: track.track_type,
        clips: packed,
    };
    ok(with_track(p, t), Some(clip_id))
}

pub fn set_rotation(p: &Project, clip_id: &str, degrees: i32) -> Edit {
    let Some(track) = p.track_of(clip_id) else {
        return err("not_found", NOT_FOUND);
    };
    if degrees % 90 != 0 {
        return err("bad_rotation", "Rotasi harus kelipatan 90");
    }
    let d = degrees.rem_euclid(360);
    let t = replace(track, clip_id, |c| {
        let mut c = c.clone();
        c.rotation_deg = d;
        c
    });
    ok(with_track(p, t), Some(clip_id))
}

pub fn set_crop(p: &Project, clip_id: &str, crop: Option<Crop>) -> Edit {
    let Some(track) = p.track_of(clip_id) else {
        return err("not_found", NOT_FOUND);
    };
    let t = replace(track, clip_id, |c| {
        let mut c = c.clone();
        c.crop = crop;
        c
    });
    ok(with_track(p, t), Some(clip_id))
}

pub fn set_canvas(p: &Project, canvas: CanvasSettings) -> Edit {
    let mut q = p.clone();
    q.canvas = canvas;
    ok(q, None)
}

pub fn add_sources(p: &Project, sources: Vec<MediaSource>) -> Edit {
    let mut q = p.clone();
    q.sources.extend(sources);
    ok(q, None)
}

pub fn clip_at<'a>(p: &'a Project, track_id: &str, time_us: i64) -> Option<&'a Clip> {
    p.tracks
        .iter()
        .find(|t| t.id == track_id)?
        .clips
        .iter()
        .find(|c| time_us >= c.start_us && time_us < c.end_us())
}
