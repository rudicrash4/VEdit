//! VEdit core: sumber kebenaran logika proyek/timeline.
//!
//! Satuan waktu tunggal: mikrodetik (`i64`). Tidak ada float sebagai otoritas posisi timeline.
//! Perilaku mengikuti implementasi Kotlin (`TimelineEngine`, `EditHistory`, `PreviewPlan`, `CanvasSpec`)
//! persis, termasuk pembulatan (`round_half_up` = `Math.round`) dan teks galat.

pub mod canvas;
pub mod engine;
pub mod history;
pub mod json;
pub mod model;
pub mod ops;
pub mod plan;
pub mod time;

/// Versi kontrak API bridge. Naikkan bila format perintah/hasil JSON berubah tidak kompatibel.
pub const API_VERSION: i32 = 1;
