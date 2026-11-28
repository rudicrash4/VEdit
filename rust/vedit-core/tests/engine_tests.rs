use vedit_core::engine::*;
use vedit_core::history::History;
use vedit_core::json::parse;
use vedit_core::model::*;
use vedit_core::ops::{self, EditOk};
use vedit_core::plan;

const S: i64 = 1_000_000;

fn src(id: &str, t: MediaType, dur: i64) -> MediaSource {
    MediaSource {
        id: id.into(),
        uri: format!("content://{id}"),
        media_type: t,
        display_name: format!("{id}.x"),
        duration_us: dur,
        width: 1920,
        height: 1080,
        has_audio: t != MediaType::Image,
    }
}

fn base() -> Project {
    let mut p = Project::create("p", "Test", 0);
    p.sources = vec![
        src("m1", MediaType::Video, 10 * S),
        src("m2", MediaType::Audio, 8 * S),
        src("m3", MediaType::Image, 5 * S),
    ];
    p
}

fn ok(e: ops::Edit) -> Project {
    let EditOk { project, .. } = e.unwrap_or_else(|x| panic!("diharapkan Ok: {x:?}"));
    assert!(project.validate().is_empty(), "{:?}", project.validate());
    project
}

fn with_two() -> Project {
    let p = ok(ops::add_clip(&base(), "v1", "m1", "c1", None));
    ok(ops::add_clip(&p, "v1", "m1", "c2", None))
}

#[test]
fn add_appends_and_rejects_wrong_type_and_overlap() {
    let p = with_two();
    assert_eq!(p.clip("c1").unwrap().start_us, 0);
    assert_eq!(p.clip("c2").unwrap().start_us, 10 * S);
    assert_eq!(p.duration_us(), 20 * S);
    assert!(ops::add_clip(&p, "a1", "m1", "x", None).is_err());
    assert!(ops::add_clip(&p, "v1", "m2", "x", None).is_err());
    ok(ops::add_clip(&p, "a1", "m2", "a", None));
    assert_eq!(
        ops::add_clip(&p, "v1", "m1", "x", Some(5 * S)).unwrap_err().code,
        "overlap"
    );
    assert_eq!(
        ops::add_clip(&p, "a1", "m1", "x", None).unwrap_err().message,
        "Tipe media VIDEO tidak cocok untuk track AUDIO"
    );
}

#[test]
fn image_default_duration_and_extension() {
    let p = ok(ops::add_clip(&base(), "v1", "m3", "i", None));
    assert_eq!(p.clip("i").unwrap().duration_us(), 5 * S);
    let p = ok(ops::trim_end(&p, "i", 9 * S));
    assert_eq!(p.clip("i").unwrap().duration_us(), 9 * S);
}

#[test]
fn split_keeps_contiguity_and_coverage() {
    let p = ok(ops::split(&with_two(), "c1", 4 * S, "c1b"));
    let (l, r) = (p.clip("c1").unwrap(), p.clip("c1b").unwrap());
    assert_eq!(l.duration_us(), 4 * S);
    assert_eq!(l.end_us(), r.start_us);
    assert_eq!(l.source_out_us, r.source_in_us);
    assert_eq!(r.source_out_us, 10 * S);
    assert_eq!(
        ops::split(&with_two(), "c1", 50_000, "x").unwrap_err().message,
        "Titik potong harus minimal 100 ms dari tepi klip"
    );
}

#[test]
fn split_is_correct_for_non_unit_speed() {
    let mut p = ok(ops::add_clip(&base(), "v1", "m1", "c", None));
    p.tracks[0].clips[0].speed = 2.0; // 10 s sumber -> 5 s timeline
    assert_eq!(p.clip("c").unwrap().duration_us(), 5 * S);
    let p = ok(ops::split(&p, "c", 2 * S, "d"));
    assert_eq!(p.clip("c").unwrap().source_out_us, 4 * S);
    assert_eq!(p.clip("d").unwrap().source_in_us, 4 * S);
    assert_eq!(p.clip("d").unwrap().start_us, 2 * S);
}

#[test]
fn trim_limits() {
    let p = with_two();
    let t = ok(ops::trim_start(&p, "c2", 12 * S));
    assert_eq!(t.clip("c2").unwrap().source_in_us, 2 * S);
    assert_eq!(ops::trim_start(&p, "c2", 5 * S).unwrap_err().code, "no_source_left");
    assert_eq!(ops::trim_end(&p, "c1", 11 * S).unwrap_err().code, "past_source_end");
    assert!(ops::trim_end(&p, "c1", 50_000).is_err());
    let t = ok(ops::trim_end(&p, "c1", 6 * S));
    assert_eq!(t.clip("c1").unwrap().end_us(), 6 * S);
}

#[test]
fn delete_ripple_and_duplicate() {
    let p = with_two();
    let d = ok(ops::delete(&p, "c1", false));
    assert_eq!(d.clip("c2").unwrap().start_us, 10 * S);
    let d = ok(ops::delete(&p, "c1", true));
    assert_eq!(d.clip("c2").unwrap().start_us, 0);
    let u = ok(ops::duplicate(&p, "c1", "c1d"));
    assert_eq!(u.clip("c1d").unwrap().start_us, 10 * S);
    assert_eq!(u.clip("c2").unwrap().start_us, 20 * S);
}

#[test]
fn reorder_packs_and_clamps_index() {
    let p = ok(ops::add_clip(&with_two(), "v1", "m3", "c3", None));
    let r = ok(ops::reorder(&p, "c3", 0));
    let ids: Vec<_> = r.tracks[0].clips.iter().map(|c| c.id.as_str()).collect();
    assert_eq!(ids, ["c3", "c1", "c2"]);
    assert_eq!(r.clip("c1").unwrap().start_us, 5 * S);
    let r = ok(ops::reorder(&p, "c1", 99));
    assert_eq!(r.tracks[0].clips.last().unwrap().id, "c1");
}

#[test]
fn rotation_crop_canvas() {
    let p = with_two();
    assert_eq!(
        ok(ops::set_rotation(&p, "c1", -90)).clip("c1").unwrap().rotation_deg,
        270
    );
    assert_eq!(
        ok(ops::set_rotation(&p, "c1", 450)).clip("c1").unwrap().rotation_deg,
        90
    );
    assert!(ops::set_rotation(&p, "c1", 45).is_err());
    assert!(Crop::new(0.5, 0.0, 0.5, 0.0).is_err());
    assert!(Crop::new(-0.1, 0.0, 0.0, 0.0).is_err());
    let c = Crop::new(0.1, 0.0, 0.1, 0.0).unwrap();
    assert_eq!(ok(ops::set_crop(&p, "c1", Some(c))).clip("c1").unwrap().crop, Some(c));
    let q = ok(ops::set_canvas(
        &p,
        CanvasSettings {
            aspect: AspectPreset::R9_16,
            fit: FitMode::Fill,
        },
    ));
    assert_eq!(q.canvas.aspect, AspectPreset::R9_16);
}

#[test]
fn history_undo_redo_and_limit() {
    let mut h = History::new(base());
    let a = ok(ops::add_clip(h.current(), "v1", "m1", "c1", None));
    h.apply(a.clone());
    h.apply(a.clone()); // no-op diabaikan
    assert!(h.can_undo());
    assert!(h.undo().is_some());
    assert!(h.undo().is_none());
    assert_eq!(h.redo().unwrap(), &a);
    let mut h = History::with_limit(base(), 3);
    for i in 0..6 {
        let mut p = h.current().clone();
        p.name = format!("n{i}");
        h.apply(p);
    }
    let mut n = 0;
    while h.undo().is_some() {
        n += 1;
    }
    assert_eq!(n, 3);
}

#[test]
fn plan_gaps_locate_and_structure() {
    let mut p = with_two();
    p = ok(ops::move_clip(&p, "c2", 12 * S));
    let items = plan::sequence_items(&p.tracks[0]);
    assert_eq!(items.len(), 3);
    assert_eq!(items[1], plan::Item::Gap(2 * S));
    let sorted = p.tracks[0].clips.clone();
    let l = plan::locate(&sorted, 10 * S + 1).unwrap();
    assert_eq!((l.index, l.in_gap), (1, true));
    let l = plan::locate(&sorted, 99 * S).unwrap();
    assert!(l.at_end);
    let mut q = p.clone();
    q.name = "rename".into();
    assert!(!plan::structure_changed(Some(&p), &q));
    q.canvas.fit = FitMode::Fill;
    assert!(plan::structure_changed(Some(&p), &q));
}

#[test]
fn engine_json_round_trip_apply_undo_redo() {
    let mut p = base();
    p.sources[0].display_name = "Ü \"quote\".mp4".into();
    let json = project_to_value(&p).to_json();
    let mut e = Engine::open(&json).unwrap();
    let r = e.apply_json(
        r#"{"op":"addClip","trackId":"v1","sourceId":"m1","newClipId":"c1"}"#,
        777,
    );
    let v = parse(&r).unwrap();
    assert_eq!(v.get("ok").unwrap().as_bool(), Some(true));
    assert_eq!(v.get("selected").unwrap().as_str(), Some("c1"));
    assert_eq!(v.get("canUndo").unwrap().as_bool(), Some(true));
    assert_eq!(e.project().modified_at_ms, 777);
    // proyek dari snapshot dapat dibaca ulang dan identik
    let again = project_from_value(&parse(&r).unwrap().get("project").unwrap().clone()).unwrap();
    assert_eq!(&again, e.project());
    // galat: state tidak berubah
    let before = e.project().clone();
    let r = e.apply_json(r#"{"op":"split","clipId":"c1","atUs":1,"newClipId":"z"}"#, 1000);
    let v = parse(&r).unwrap();
    assert_eq!(v.get("ok").unwrap().as_bool(), Some(false));
    assert_eq!(v.get("code").unwrap().as_str(), Some("split_edge"));
    assert_eq!(e.project(), &before);
    // perintah rusak tidak panic
    for bad in [
        "",
        "{",
        r#"{"op":"nope"}"#,
        r#"{"op":"split"}"#,
        r#"{"op":"setCrop","clipId":"c1","crop":{"left":0.8,"right":0.8}}"#,
    ] {
        let v = parse(&e.apply_json(bad, 1)).unwrap();
        assert_eq!(v.get("ok").unwrap().as_bool(), Some(false), "{bad}");
    }
    e.undo_json();
    assert!(e.project().clip("c1").is_none());
    let v = parse(&e.redo_json()).unwrap();
    assert_eq!(v.get("canRedo").unwrap().as_bool(), Some(false));
    assert!(e.project().clip("c1").is_some());
}

#[test]
fn project_decode_rejects_other_schema_and_invalid() {
    let p = base();
    let json = project_to_value(&p)
        .to_json()
        .replace("\"schemaVersion\":2", "\"schemaVersion\":1");
    assert!(Engine::open(&json).is_err());
    let mut bad = with_two();
    bad.tracks[0].clips[1].start_us = 5 * S;
    assert!(Engine::open(&project_to_value(&bad).to_json()).is_err());
}

#[test]
fn legacy_v1_default_canvas_when_missing() {
    let mut v = project_to_value(&base());
    if let vedit_core::json::Value::Obj(kv) = &mut v {
        kv.retain(|(k, _)| k != "canvas");
    }
    let e = Engine::open(&v.to_json()).unwrap();
    assert_eq!(e.project().canvas, CanvasSettings::default());
}
