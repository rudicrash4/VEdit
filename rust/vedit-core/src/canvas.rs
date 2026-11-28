use crate::model::{AspectPreset, CanvasSettings, Project};
use crate::time::round_half_up;

/// Kualitas keluaran = sisi pendek frame. `export_enabled` hanya true untuk yang terbukti di perangkat (720p).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum OutputQuality {
    Preview,
    P720,
    P1080,
}

impl OutputQuality {
    pub fn short_side(self) -> i32 {
        match self {
            OutputQuality::Preview => 480,
            OutputQuality::P720 => 720,
            OutputQuality::P1080 => 1080,
        }
    }
    pub fn export_enabled(self) -> bool {
        matches!(self, OutputQuality::P720)
    }
    pub fn from_name(s: &str) -> Option<OutputQuality> {
        Some(match s {
            "PREVIEW" => OutputQuality::Preview,
            "P720" => OutputQuality::P720,
            "P1080" => OutputQuality::P1080,
            _ => return None,
        })
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct CanvasSize {
    pub width: i32,
    pub height: i32,
}

fn even(x: f64) -> i32 {
    ((round_half_up(x / 2.0) * 2) as i32).max(2)
}

pub fn size(aspect: AspectPreset, short_side: i32) -> CanvasSize {
    let (w, h) = (aspect.w() as f64, aspect.h() as f64);
    let s = short_side as f64;
    if aspect.w() >= aspect.h() {
        CanvasSize {
            width: even(s * w / h),
            height: even(s),
        }
    } else {
        CanvasSize {
            width: even(s),
            height: even(s * h / w),
        }
    }
}

pub fn size_for(canvas: CanvasSettings, q: OutputQuality) -> CanvasSize {
    size(canvas.aspect, q.short_side())
}

/// Rencana export: ukuran mengikuti kanvas proyek; kualitas yang belum terbukti ditolak.
pub fn export_size(p: &Project, q: OutputQuality) -> Result<CanvasSize, String> {
    if !q.export_enabled() {
        return Err(format!("Kualitas {:?} belum diaktifkan untuk export", q));
    }
    Ok(size_for(p.canvas, q))
}

#[cfg(test)]
mod tests {
    use super::*;
    use AspectPreset::*;

    fn d(a: AspectPreset, q: OutputQuality) -> (i32, i32) {
        let s = size(a, q.short_side());
        (s.width, s.height)
    }

    #[test]
    fn all_presets_match_documented_sizes() {
        assert_eq!(d(R16_9, OutputQuality::P720), (1280, 720));
        assert_eq!(d(R9_16, OutputQuality::P720), (720, 1280));
        assert_eq!(d(R1_1, OutputQuality::P720), (720, 720));
        assert_eq!(d(R4_5, OutputQuality::P720), (720, 900));
        assert_eq!(d(R16_9, OutputQuality::P1080), (1920, 1080));
        assert_eq!(d(R9_16, OutputQuality::P1080), (1080, 1920));
        assert_eq!(d(R1_1, OutputQuality::P1080), (1080, 1080));
        assert_eq!(d(R4_5, OutputQuality::P1080), (1080, 1350));
        assert_eq!(d(R16_9, OutputQuality::Preview), (854, 480));
    }

    #[test]
    fn dimensions_even_and_1080_export_rejected() {
        for a in [R16_9, R9_16, R1_1, R4_5] {
            for q in [OutputQuality::Preview, OutputQuality::P720, OutputQuality::P1080] {
                let s = size(a, q.short_side());
                assert!(s.width % 2 == 0 && s.height % 2 == 0);
            }
        }
        let p = Project::create("p", "x", 0);
        assert!(export_size(&p, OutputQuality::P1080).is_err());
        assert_eq!(
            export_size(&p, OutputQuality::P720).unwrap(),
            CanvasSize {
                width: 1280,
                height: 720
            }
        );
    }
}
