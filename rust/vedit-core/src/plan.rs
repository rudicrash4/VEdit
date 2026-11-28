//! Perencana komposisi/preview murni; port `PreviewPlan.kt`.
use crate::model::{Clip, Project, Track};

pub const MIN_GAP_US: i64 = 1_000;

#[derive(Clone, Debug, PartialEq)]
pub enum Item {
    ClipItem(Clip),
    Gap(i64),
}

/// Klip berurutan dengan celah eksplisit di depan dan di antara klip; tanpa celah di belakang.
pub fn sequence_items(track: &Track) -> Vec<Item> {
    let mut clips: Vec<&Clip> = track.clips.iter().collect();
    clips.sort_by_key(|c| c.start_us);
    let mut out = Vec::new();
    let mut cursor = 0i64;
    for c in clips {
        let gap = c.start_us - cursor;
        if gap >= MIN_GAP_US {
            out.push(Item::Gap(gap));
        }
        out.push(Item::ClipItem(c.clone()));
        cursor = cursor.max(c.end_us());
    }
    out
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Located {
    pub index: usize,
    pub offset_us: i64,
    pub in_gap: bool,
    pub at_end: bool,
}

pub fn locate(sorted: &[Clip], time_us: i64) -> Option<Located> {
    if sorted.is_empty() {
        return None;
    }
    let t = time_us.max(0);
    for (i, c) in sorted.iter().enumerate() {
        if t < c.start_us {
            return Some(Located {
                index: i,
                offset_us: 0,
                in_gap: true,
                at_end: false,
            });
        }
        if t < c.end_us() {
            return Some(Located {
                index: i,
                offset_us: t - c.start_us,
                in_gap: false,
                at_end: false,
            });
        }
    }
    let last = sorted.last().unwrap();
    Some(Located {
        index: sorted.len() - 1,
        offset_us: last.duration_us(),
        in_gap: false,
        at_end: true,
    })
}

pub fn to_timeline_us(sorted: &[Clip], index: usize, offset_us: i64) -> i64 {
    sorted[index.min(sorted.len() - 1)].start_us + offset_us
}

pub fn structure_changed(old: Option<&Project>, new: &Project) -> bool {
    match old {
        None => true,
        Some(o) => o.tracks != new.tracks || o.sources != new.sources || o.canvas != new.canvas,
    }
}

pub fn has_playable_content(p: &Project) -> bool {
    p.duration_us() > 0 && p.tracks.iter().any(|t| !t.clips.is_empty())
}

pub fn clamp_playhead(us: i64, duration_us: i64) -> i64 {
    us.clamp(0, duration_us.max(0))
}
