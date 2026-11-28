use crate::time::{round_half_up, EPS_US};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MediaType {
    Video,
    Image,
    Audio,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TrackType {
    Video,
    Audio,
}

#[derive(Clone, Debug, PartialEq)]
pub struct MediaSource {
    pub id: String,
    pub uri: String,
    pub media_type: MediaType,
    pub display_name: String,
    pub duration_us: i64,
    pub width: i32,
    pub height: i32,
    pub has_audio: bool,
}

/// Pecahan sisi yang dipotong (0..1). Dibuat lewat [`Crop::new`] agar invarian terjaga.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Crop {
    pub left: f32,
    pub top: f32,
    pub right: f32,
    pub bottom: f32,
}

impl Crop {
    pub fn new(left: f32, top: f32, right: f32, bottom: f32) -> Result<Crop, String> {
        if !(left >= 0.0 && top >= 0.0 && right >= 0.0 && bottom >= 0.0) {
            return Err("Crop tidak boleh negatif".into());
        }
        if !(left + right <= 0.9 && top + bottom <= 0.9) {
            return Err("Crop menyisakan area terlalu kecil".into());
        }
        Ok(Crop {
            left,
            top,
            right,
            bottom,
        })
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct Clip {
    pub id: String,
    pub source_id: String,
    pub start_us: i64,
    pub source_in_us: i64,
    pub source_out_us: i64,
    pub speed: f32,
    pub volume: f32,
    pub rotation_deg: i32,
    pub crop: Option<Crop>,
}

impl Clip {
    pub fn new(id: &str, source_id: &str, start_us: i64, source_in_us: i64, source_out_us: i64) -> Clip {
        Clip {
            id: id.into(),
            source_id: source_id.into(),
            start_us,
            source_in_us,
            source_out_us,
            speed: 1.0,
            volume: 1.0,
            rotation_deg: 0,
            crop: None,
        }
    }

    /// Durasi di timeline setelah pemetaan kecepatan (float hanya untuk konversi, hasil integer).
    pub fn duration_us(&self) -> i64 {
        round_half_up((self.source_out_us - self.source_in_us) as f64 / self.speed as f64)
    }

    pub fn end_us(&self) -> i64 {
        self.start_us + self.duration_us()
    }

    pub fn timeline_to_source_us(&self, timeline_us: i64) -> i64 {
        self.source_in_us + round_half_up((timeline_us - self.start_us) as f64 * self.speed as f64)
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct Track {
    pub id: String,
    pub track_type: TrackType,
    pub clips: Vec<Clip>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AspectPreset {
    R16_9,
    R9_16,
    R1_1,
    R4_5,
}

impl AspectPreset {
    pub fn w(self) -> i32 {
        match self {
            AspectPreset::R16_9 => 16,
            AspectPreset::R9_16 => 9,
            AspectPreset::R1_1 => 1,
            AspectPreset::R4_5 => 4,
        }
    }
    pub fn h(self) -> i32 {
        match self {
            AspectPreset::R16_9 => 9,
            AspectPreset::R9_16 => 16,
            AspectPreset::R1_1 => 1,
            AspectPreset::R4_5 => 5,
        }
    }
    pub fn name(self) -> &'static str {
        match self {
            AspectPreset::R16_9 => "R16_9",
            AspectPreset::R9_16 => "R9_16",
            AspectPreset::R1_1 => "R1_1",
            AspectPreset::R4_5 => "R4_5",
        }
    }
    pub fn from_name(s: &str) -> Option<AspectPreset> {
        Some(match s {
            "R16_9" => AspectPreset::R16_9,
            "R9_16" => AspectPreset::R9_16,
            "R1_1" => AspectPreset::R1_1,
            "R4_5" => AspectPreset::R4_5,
            _ => return None,
        })
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FitMode {
    Fit,
    Fill,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct CanvasSettings {
    pub aspect: AspectPreset,
    pub fit: FitMode,
}

impl Default for CanvasSettings {
    fn default() -> Self {
        CanvasSettings {
            aspect: AspectPreset::R16_9,
            fit: FitMode::Fit,
        }
    }
}

pub const CURRENT_SCHEMA: i32 = 2;
pub const VIDEO_TRACK: &str = "v1";
pub const AUDIO_TRACK: &str = "a1";

#[derive(Clone, Debug, PartialEq)]
pub struct Project {
    pub schema_version: i32,
    pub id: String,
    pub name: String,
    pub sources: Vec<MediaSource>,
    pub tracks: Vec<Track>,
    pub created_at_ms: i64,
    pub modified_at_ms: i64,
    pub canvas: CanvasSettings,
}

impl Project {
    pub fn create(id: &str, name: &str, now_ms: i64) -> Project {
        Project {
            schema_version: CURRENT_SCHEMA,
            id: id.into(),
            name: name.into(),
            sources: vec![],
            tracks: vec![
                Track {
                    id: VIDEO_TRACK.into(),
                    track_type: TrackType::Video,
                    clips: vec![],
                },
                Track {
                    id: AUDIO_TRACK.into(),
                    track_type: TrackType::Audio,
                    clips: vec![],
                },
            ],
            created_at_ms: now_ms,
            modified_at_ms: now_ms,
            canvas: CanvasSettings::default(),
        }
    }

    pub fn duration_us(&self) -> i64 {
        self.tracks
            .iter()
            .map(|t| t.clips.iter().map(|c| c.end_us()).max().unwrap_or(0))
            .max()
            .unwrap_or(0)
    }

    pub fn source(&self, id: &str) -> Option<&MediaSource> {
        self.sources.iter().find(|s| s.id == id)
    }

    pub fn clip(&self, id: &str) -> Option<&Clip> {
        self.tracks.iter().find_map(|t| t.clips.iter().find(|c| c.id == id))
    }

    pub fn track_of(&self, clip_id: &str) -> Option<&Track> {
        self.tracks.iter().find(|t| t.clips.iter().any(|c| c.id == clip_id))
    }

    /// Daftar pelanggaran invarian; kosong = valid. Teks sama dengan `Project.validate()` Kotlin.
    pub fn validate(&self) -> Vec<String> {
        let mut errors = Vec::new();
        for t in &self.tracks {
            let mut sorted: Vec<&Clip> = t.clips.iter().collect();
            sorted.sort_by_key(|c| c.start_us); // stabil, seperti sortedBy
            for (i, c) in sorted.iter().enumerate() {
                let src = self.source(&c.source_id);
                if src.is_none() {
                    errors.push(format!("Klip {}: sumber {} tidak ada", c.id, c.source_id));
                }
                if c.start_us < 0 {
                    errors.push(format!("Klip {}: start negatif", c.id));
                }
                if c.source_in_us < 0 || c.source_out_us <= c.source_in_us {
                    errors.push(format!("Klip {}: in/out tidak valid", c.id));
                }
                if let Some(s) = src {
                    if s.media_type != MediaType::Image && c.source_out_us > s.duration_us + EPS_US {
                        errors.push(format!("Klip {}: out melewati durasi sumber", c.id));
                    }
                }
                if !(c.speed > 0.0 && c.speed.is_finite()) {
                    errors.push(format!("Klip {}: kecepatan tidak valid", c.id));
                }
                if c.rotation_deg % 90 != 0 || !(0..=270).contains(&c.rotation_deg) {
                    errors.push(format!("Klip {}: rotasi tidak valid", c.id));
                }
                if i > 0 && sorted[i - 1].end_us() > c.start_us + EPS_US {
                    errors.push(format!("Klip {}: tumpang tindih dengan {}", c.id, sorted[i - 1].id));
                }
            }
        }
        errors
    }
}
