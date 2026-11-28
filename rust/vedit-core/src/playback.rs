//! Penjadwal pemutaran (Preview Engine V2): mengubah proyek menjadi rencana segmen berurutan (klip/gambar/celah)
//! dengan waktu timeline dan pemetaan ke waktu sumber, plus geometri penempatan frame pada kanvas
//! (crop -> rotasi -> fit/fill). Murni dan deterministik; eksekusi (ExoPlayer, Surface, audio) ada di Kotlin.
use crate::model::*;
use crate::plan::MIN_GAP_US;
use crate::time::round_half_up;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SegKind {
    Video,
    Image,
    Gap,
    Audio,
}

impl SegKind {
    pub fn name(self) -> &'static str {
        match self {
            SegKind::Video => "VIDEO",
            SegKind::Image => "IMAGE",
            SegKind::Gap => "GAP",
            SegKind::Audio => "AUDIO",
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct Segment {
    pub kind: SegKind,
    /// Kosong untuk celah.
    pub clip_id: String,
    pub source_id: String,
    pub start_us: i64,
    pub end_us: i64,
    pub src_in_us: i64,
    pub src_out_us: i64,
    pub speed: f32,
    pub volume: f32,
    pub rotation_deg: i32,
    pub crop: Option<Crop>,
}

impl Segment {
    pub fn duration_us(&self) -> i64 {
        self.end_us - self.start_us
    }

    /// Waktu timeline -> posisi sumber (dibatasi ke [src_in, src_out]).
    pub fn source_pos_us(&self, t_us: i64) -> i64 {
        let p = self.src_in_us + round_half_up((t_us - self.start_us) as f64 * self.speed as f64);
        p.clamp(self.src_in_us, self.src_out_us)
    }

    /// Posisi sumber -> waktu timeline.
    pub fn timeline_pos_us(&self, src_us: i64) -> i64 {
        self.start_us + round_half_up((src_us - self.src_in_us) as f64 / self.speed as f64)
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct PlaybackPlan {
    /// Track video: segmen berurutan; celah di depan/antar klip eksplisit; tanpa celah di belakang.
    pub video: Vec<Segment>,
    /// Track audio tambahan: sama, hanya klip audio dan celah.
    pub audio: Vec<Segment>,
    pub duration_us: i64,
}

fn gap(start: i64, end: i64) -> Segment {
    Segment {
        kind: SegKind::Gap,
        clip_id: String::new(),
        source_id: String::new(),
        start_us: start,
        end_us: end,
        src_in_us: 0,
        src_out_us: 0,
        speed: 1.0,
        volume: 1.0,
        rotation_deg: 0,
        crop: None,
    }
}

fn track_segments(p: &Project, t: &Track) -> Vec<Segment> {
    let mut clips: Vec<&Clip> = t.clips.iter().collect();
    clips.sort_by_key(|c| c.start_us);
    let mut out = Vec::new();
    let mut cursor = 0i64;
    for c in clips {
        if c.start_us - cursor >= MIN_GAP_US {
            out.push(gap(cursor, c.start_us));
        }
        let kind = match p.source(&c.source_id).map(|s| s.media_type) {
            Some(MediaType::Image) => SegKind::Image,
            Some(MediaType::Audio) => SegKind::Audio,
            _ => SegKind::Video,
        };
        out.push(Segment {
            kind,
            clip_id: c.id.clone(),
            source_id: c.source_id.clone(),
            start_us: c.start_us,
            end_us: c.end_us(),
            src_in_us: c.source_in_us,
            src_out_us: c.source_out_us,
            speed: c.speed,
            volume: c.volume,
            rotation_deg: c.rotation_deg,
            crop: c.crop,
        });
        cursor = cursor.max(c.end_us());
    }
    out
}

pub fn build_plan(p: &Project) -> PlaybackPlan {
    let mut video = Vec::new();
    let mut audio = Vec::new();
    for t in &p.tracks {
        match t.track_type {
            TrackType::Video => video.extend(track_segments(p, t)),
            TrackType::Audio => audio.extend(track_segments(p, t)),
        }
    }
    PlaybackPlan {
        video,
        audio,
        duration_us: p.duration_us(),
    }
}

/// Indeks segmen yang memuat `t` (setengah-terbuka [start, end)); lubang < MIN_GAP jatuh ke segmen berikutnya.
/// `None` bila `t` di/ setelah akhir segmen terakhir.
pub fn index_at(segs: &[Segment], t_us: i64) -> Option<usize> {
    let t = t_us.max(0);
    segs.iter().position(|s| t < s.end_us)
}

// ------------------------------------------------------------------ geometri

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Layout {
    /// Matriks affine piksel-sumber -> piksel-view: [sx, kx, tx, ky, sy, ty] (urutan `Matrix.setValues` baris 0 dan 1).
    pub m: [f64; 6],
    /// Persegi tujuan (kiri, atas, kanan, bawah) terpotong ke view; area di luarnya harus disembunyikan.
    pub clip: [f64; 4],
}

/// Letak frame sumber (src_w x src_h piksel) di view (view_w x view_h): crop, lalu rotasi searah jarum jam
/// (kelipatan 90), lalu FIT (semua terlihat) atau FILL (memenuhi, tepi terpotong), dipusatkan.
pub fn layout(
    view_w: f64,
    view_h: f64,
    src_w: f64,
    src_h: f64,
    rotation_deg: i32,
    crop: Option<Crop>,
    fit: FitMode,
) -> Layout {
    let (l, t, r, b) = match crop {
        Some(c) => (c.left as f64, c.top as f64, c.right as f64, c.bottom as f64),
        None => (0.0, 0.0, 0.0, 0.0),
    };
    let (cx0, cy0) = (l * src_w, t * src_h);
    let (cw, ch) = (src_w * (1.0 - l - r), src_h * (1.0 - t - b));
    let rot = rotation_deg.rem_euclid(360);
    let (cos, sin) = match rot {
        90 => (0.0, 1.0),
        180 => (-1.0, 0.0),
        270 => (0.0, -1.0),
        _ => (1.0, 0.0),
    };
    let (rw, rh) = if rot == 90 || rot == 270 { (ch, cw) } else { (cw, ch) };
    let s = match fit {
        FitMode::Fit => (view_w / rw).min(view_h / rh),
        FitMode::Fill => (view_w / rw).max(view_h / rh),
    };
    let (mx, my) = (cx0 + cw / 2.0, cy0 + ch / 2.0); // pusat area crop di ruang sumber
    let sx = s * cos;
    let kx = -s * sin;
    let ky = s * sin;
    let sy = s * cos;
    let tx = view_w / 2.0 - (sx * mx + kx * my);
    let ty = view_h / 2.0 - (ky * mx + sy * my);
    let (dw, dh) = (rw * s, rh * s);
    let clip = [
        (view_w / 2.0 - dw / 2.0).max(0.0),
        (view_h / 2.0 - dh / 2.0).max(0.0),
        (view_w / 2.0 + dw / 2.0).min(view_w),
        (view_h / 2.0 + dh / 2.0).min(view_h),
    ];
    Layout {
        m: [sx, kx, tx, ky, sy, ty],
        clip,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const S: i64 = 1_000_000;

    fn src(id: &str, t: MediaType, dur: i64) -> MediaSource {
        MediaSource {
            id: id.into(),
            uri: "u".into(),
            media_type: t,
            display_name: "n".into(),
            duration_us: dur,
            width: 1920,
            height: 1080,
            has_audio: true,
        }
    }

    fn proj() -> Project {
        let mut p = Project::create("p", "x", 0);
        p.sources = vec![
            src("v", MediaType::Video, 10 * S),
            src("i", MediaType::Image, 5 * S),
            src("a", MediaType::Audio, 8 * S),
        ];
        p
    }

    fn put(p: &mut Project, track: usize, id: &str, source: &str, start: i64, in_us: i64, out_us: i64) {
        p.tracks[track].clips.push(Clip::new(id, source, start, in_us, out_us));
    }

    fn map(l: &Layout, x: f64, y: f64) -> (f64, f64) {
        (l.m[0] * x + l.m[1] * y + l.m[2], l.m[3] * x + l.m[4] * y + l.m[5])
    }

    fn near(a: (f64, f64), b: (f64, f64)) {
        assert!((a.0 - b.0).abs() < 1e-6 && (a.1 - b.1).abs() < 1e-6, "{a:?} != {b:?}");
    }

    #[test]
    fn video_then_image_then_gap_then_video() {
        let mut p = proj();
        put(&mut p, 0, "c1", "v", 0, 0, 4 * S);
        put(&mut p, 0, "c2", "i", 4 * S, 0, 3 * S);
        put(&mut p, 0, "c3", "v", 9 * S, 2 * S, 5 * S);
        let plan = build_plan(&p);
        let kinds: Vec<_> = plan.video.iter().map(|s| s.kind).collect();
        assert_eq!(kinds, [SegKind::Video, SegKind::Image, SegKind::Gap, SegKind::Video]);
        assert_eq!((plan.video[2].start_us, plan.video[2].end_us), (7 * S, 9 * S));
        assert_eq!(plan.duration_us, 12 * S);
        assert!(plan.audio.is_empty());
        // kontigu: akhir segmen = awal berikutnya
        for w in plan.video.windows(2) {
            assert_eq!(w[0].end_us, w[1].start_us);
        }
    }

    #[test]
    fn leading_gap_and_audio_track_are_separate() {
        let mut p = proj();
        put(&mut p, 0, "c1", "v", 2 * S, 0, 3 * S);
        put(&mut p, 1, "a1", "a", S, 0, 4 * S);
        let plan = build_plan(&p);
        assert_eq!(plan.video[0].kind, SegKind::Gap);
        assert_eq!(plan.video[0].end_us, 2 * S);
        assert_eq!(
            plan.audio.iter().map(|s| s.kind).collect::<Vec<_>>(),
            [SegKind::Gap, SegKind::Audio]
        );
    }

    #[test]
    fn index_at_boundaries_are_half_open() {
        let mut p = proj();
        put(&mut p, 0, "c1", "v", 0, 0, 4 * S);
        put(&mut p, 0, "c2", "v", 4 * S, 0, 2 * S);
        let plan = build_plan(&p);
        assert_eq!(index_at(&plan.video, 0), Some(0));
        assert_eq!(index_at(&plan.video, 4 * S - 1), Some(0));
        assert_eq!(index_at(&plan.video, 4 * S), Some(1));
        assert_eq!(index_at(&plan.video, 6 * S), None);
        assert_eq!(index_at(&plan.video, -5), Some(0));
    }

    #[test]
    fn mapping_with_trim_and_speed_round_trips() {
        let mut p = proj();
        put(&mut p, 0, "c1", "v", 3 * S, 2 * S, 8 * S);
        p.tracks[0].clips[0].speed = 2.0;
        let plan = build_plan(&p);
        let s = &plan.video[1];
        assert_eq!((s.start_us, s.end_us), (3 * S, 6 * S));
        assert_eq!(s.source_pos_us(3 * S), 2 * S);
        assert_eq!(s.source_pos_us(4 * S), 4 * S);
        assert_eq!(s.source_pos_us(99 * S), 8 * S); // dibatasi
        assert_eq!(s.timeline_pos_us(4 * S), 4 * S);
        for t in (3 * S..6 * S).step_by(123_457) {
            assert!((s.timeline_pos_us(s.source_pos_us(t)) - t).abs() <= 1);
        }
    }

    #[test]
    fn small_hole_does_not_create_gap_and_resolves_to_next() {
        let mut p = proj();
        put(&mut p, 0, "c1", "v", 0, 0, 4 * S);
        put(&mut p, 0, "c2", "v", 4 * S + 500, 0, 2 * S);
        let plan = build_plan(&p);
        assert_eq!(plan.video.len(), 2);
        assert_eq!(index_at(&plan.video, 4 * S + 100), Some(1));
    }

    #[test]
    fn layout_identity_when_aspects_match() {
        let l = layout(1280.0, 720.0, 1920.0, 1080.0, 0, None, FitMode::Fit);
        near(map(&l, 0.0, 0.0), (0.0, 0.0));
        near(map(&l, 1920.0, 1080.0), (1280.0, 720.0));
        assert_eq!(l.clip, [0.0, 0.0, 1280.0, 720.0]);
    }

    #[test]
    fn layout_fit_letterboxes_landscape_into_portrait_and_fill_covers() {
        // 1920x1080 ke kanvas 720x1280: FIT = lebar penuh, bar atas/bawah
        let l = layout(720.0, 1280.0, 1920.0, 1080.0, 0, None, FitMode::Fit);
        near(map(&l, 0.0, 0.0), (0.0, (1280.0 - 405.0) / 2.0));
        assert!((l.clip[1] - 437.5).abs() < 1e-6 && (l.clip[3] - 842.5).abs() < 1e-6);
        // FILL = tinggi penuh, kiri/kanan terpotong
        let f = layout(720.0, 1280.0, 1920.0, 1080.0, 0, None, FitMode::Fill);
        near(map(&f, 960.0, 540.0), (360.0, 640.0));
        assert_eq!((f.clip[0], f.clip[1], f.clip[2], f.clip[3]), (0.0, 0.0, 720.0, 1280.0));
        assert!(map(&f, 0.0, 0.0).0 < 0.0, "tepi kiri harus keluar dari view");
    }

    #[test]
    fn layout_rotation_is_clockwise() {
        // 200x100 diputar 90 searah jarum jam ke view 100x200: kiri-atas sumber -> kanan-atas view
        let l = layout(100.0, 200.0, 200.0, 100.0, 90, None, FitMode::Fit);
        near(map(&l, 0.0, 0.0), (100.0, 0.0));
        near(map(&l, 200.0, 0.0), (100.0, 200.0));
        near(map(&l, 0.0, 100.0), (0.0, 0.0));
        let r180 = layout(200.0, 100.0, 200.0, 100.0, 180, None, FitMode::Fit);
        near(map(&r180, 0.0, 0.0), (200.0, 100.0));
        let r270 = layout(100.0, 200.0, 200.0, 100.0, 270, None, FitMode::Fit);
        near(map(&r270, 0.0, 0.0), (0.0, 200.0));
        let neg = layout(100.0, 200.0, 200.0, 100.0, -90, None, FitMode::Fit);
        near(map(&neg, 0.0, 0.0), (0.0, 200.0));
    }

    #[test]
    fn layout_crop_maps_crop_rect_to_view() {
        let c = Crop::new(0.25, 0.0, 0.25, 0.0).unwrap(); // tengah 50% lebar
        let l = layout(100.0, 100.0, 400.0, 200.0, 0, Some(c), FitMode::Fit);
        // area crop = x 100..300 (200x200) -> view penuh 100x100
        near(map(&l, 100.0, 0.0), (0.0, 0.0));
        near(map(&l, 300.0, 200.0), (100.0, 100.0));
        assert_eq!(l.clip, [0.0, 0.0, 100.0, 100.0]);
        // dengan FIT ke view lebar, area di luar crop tidak boleh terlihat: clip = hanya persegi crop
        let w = layout(400.0, 100.0, 400.0, 200.0, 0, Some(c), FitMode::Fit);
        assert_eq!(w.clip, [150.0, 0.0, 250.0, 100.0]);
    }
}
