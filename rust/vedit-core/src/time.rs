pub const US_PER_SECOND: i64 = 1_000_000;
/// Panjang klip minimum di timeline (100 ms).
pub const MIN_CLIP_US: i64 = 100_000;
/// Toleransi pembulatan antar-klip.
pub const EPS_US: i64 = 2;
pub const DEFAULT_IMAGE_DURATION_US: i64 = 5_000_000;

/// Setara `Math.round(double)`/`roundToLong` di Kotlin: setengah dibulatkan ke atas (floor(x + 0.5)).
pub fn round_half_up(x: f64) -> i64 {
    (x + 0.5).floor() as i64
}

pub fn frames_to_us(frame: i64, fps_num: i32, fps_den: i32) -> i64 {
    assert!(fps_num > 0 && fps_den > 0);
    let (n, d) = (fps_num as i64, fps_den as i64);
    (frame * US_PER_SECOND * d * 2 + n).div_euclid(2 * n)
}

pub fn us_to_frame(us: i64, fps_num: i32, fps_den: i32) -> i64 {
    assert!(fps_num > 0 && fps_den > 0);
    let (n, d) = (fps_num as i64, fps_den as i64);
    (us * n * 2 + US_PER_SECOND * d).div_euclid(2 * US_PER_SECOND * d)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rounding_matches_kotlin_math_round() {
        assert_eq!(round_half_up(0.5), 1);
        assert_eq!(round_half_up(1.5), 2);
        assert_eq!(round_half_up(-0.5), 0); // Math.round(-0.5) == 0
        assert_eq!(round_half_up(-1.5), -1);
        assert_eq!(round_half_up(2.4999), 2);
    }

    #[test]
    fn frame_conversion_round_trips_without_drift() {
        for &(n, d) in &[(30000, 1001), (24000, 1001), (25, 1), (60, 1), (30, 1)] {
            for f in (0..100_000).step_by(37) {
                assert_eq!(us_to_frame(frames_to_us(f, n, d), n, d), f, "{n}/{d} frame {f}");
            }
        }
    }
}
