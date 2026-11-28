//! JSON minimal tanpa dependency (RFC 8259) untuk kontrak bridge. Angka disimpan sebagai teks asli
//! sehingga i64 dan f32 tidak kehilangan presisi. Objek mempertahankan urutan kunci.
use std::fmt::Write;

#[derive(Clone, Debug, PartialEq)]
pub enum Value {
    Null,
    Bool(bool),
    Num(String),
    Str(String),
    Arr(Vec<Value>),
    Obj(Vec<(String, Value)>),
}

const MAX_DEPTH: usize = 64;

impl Value {
    pub fn int(i: i64) -> Value {
        Value::Num(i.to_string())
    }
    pub fn f32(f: f32) -> Value {
        // Debug menghasilkan representasi terpendek yang round-trip dan selalu memuat ".0" untuk bilangan bulat.
        if f.is_finite() {
            Value::Num(format!("{:?}", f))
        } else {
            Value::Null
        }
    }
    pub fn f64(f: f64) -> Value {
        if f.is_finite() {
            Value::Num(format!("{:?}", f))
        } else {
            Value::Null
        }
    }
    pub fn str(s: &str) -> Value {
        Value::Str(s.to_string())
    }
    pub fn get(&self, key: &str) -> Option<&Value> {
        match self {
            Value::Obj(kv) => kv.iter().find(|(k, _)| k == key).map(|(_, v)| v),
            _ => None,
        }
    }
    pub fn as_str(&self) -> Option<&str> {
        if let Value::Str(s) = self {
            Some(s)
        } else {
            None
        }
    }
    pub fn as_i64(&self) -> Option<i64> {
        if let Value::Num(n) = self {
            n.parse::<i64>().ok()
        } else {
            None
        }
    }
    pub fn as_f32(&self) -> Option<f32> {
        if let Value::Num(n) = self {
            n.parse::<f32>().ok()
        } else {
            None
        }
    }
    pub fn as_f64(&self) -> Option<f64> {
        if let Value::Num(n) = self {
            n.parse::<f64>().ok().filter(|x| x.is_finite())
        } else {
            None
        }
    }
    pub fn as_bool(&self) -> Option<bool> {
        if let Value::Bool(b) = self {
            Some(*b)
        } else {
            None
        }
    }
    pub fn as_arr(&self) -> Option<&[Value]> {
        if let Value::Arr(a) = self {
            Some(a)
        } else {
            None
        }
    }
    pub fn is_null(&self) -> bool {
        matches!(self, Value::Null)
    }

    pub fn to_json(&self) -> String {
        let mut s = String::new();
        self.write(&mut s);
        s
    }

    fn write(&self, out: &mut String) {
        match self {
            Value::Null => out.push_str("null"),
            Value::Bool(b) => out.push_str(if *b { "true" } else { "false" }),
            Value::Num(n) => out.push_str(n),
            Value::Str(s) => write_str(s, out),
            Value::Arr(a) => {
                out.push('[');
                for (i, v) in a.iter().enumerate() {
                    if i > 0 {
                        out.push(',');
                    }
                    v.write(out);
                }
                out.push(']');
            }
            Value::Obj(kv) => {
                out.push('{');
                for (i, (k, v)) in kv.iter().enumerate() {
                    if i > 0 {
                        out.push(',');
                    }
                    write_str(k, out);
                    out.push(':');
                    v.write(out);
                }
                out.push('}');
            }
        }
    }
}

fn write_str(s: &str, out: &mut String) {
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => {
                let _ = write!(out, "\\u{:04x}", c as u32);
            }
            c => out.push(c),
        }
    }
    out.push('"');
}

pub fn parse(text: &str) -> Result<Value, String> {
    let mut p = Parser {
        b: text.as_bytes(),
        i: 0,
    };
    p.ws();
    let v = p.value(0)?;
    p.ws();
    if p.i != p.b.len() {
        return Err(format!("Karakter tak terduga di posisi {}", p.i));
    }
    Ok(v)
}

struct Parser<'a> {
    b: &'a [u8],
    i: usize,
}

impl<'a> Parser<'a> {
    fn ws(&mut self) {
        while self.i < self.b.len() && matches!(self.b[self.i], b' ' | b'\t' | b'\n' | b'\r') {
            self.i += 1;
        }
    }
    fn peek(&self) -> Option<u8> {
        self.b.get(self.i).copied()
    }
    fn expect(&mut self, c: u8) -> Result<(), String> {
        if self.peek() == Some(c) {
            self.i += 1;
            Ok(())
        } else {
            Err(format!("Diharapkan '{}' di posisi {}", c as char, self.i))
        }
    }
    fn lit(&mut self, word: &str, v: Value) -> Result<Value, String> {
        if self.b[self.i..].starts_with(word.as_bytes()) {
            self.i += word.len();
            Ok(v)
        } else {
            Err(format!("Token tidak valid di posisi {}", self.i))
        }
    }
    fn value(&mut self, depth: usize) -> Result<Value, String> {
        if depth > MAX_DEPTH {
            return Err("JSON terlalu dalam".into());
        }
        match self.peek() {
            None => Err("JSON berakhir tiba-tiba".into()),
            Some(b'n') => self.lit("null", Value::Null),
            Some(b't') => self.lit("true", Value::Bool(true)),
            Some(b'f') => self.lit("false", Value::Bool(false)),
            Some(b'"') => Ok(Value::Str(self.string()?)),
            Some(b'[') => {
                self.i += 1;
                let mut a = Vec::new();
                self.ws();
                if self.peek() == Some(b']') {
                    self.i += 1;
                    return Ok(Value::Arr(a));
                }
                loop {
                    self.ws();
                    a.push(self.value(depth + 1)?);
                    self.ws();
                    match self.peek() {
                        Some(b',') => self.i += 1,
                        Some(b']') => {
                            self.i += 1;
                            return Ok(Value::Arr(a));
                        }
                        _ => return Err(format!("Diharapkan ',' atau ']' di posisi {}", self.i)),
                    }
                }
            }
            Some(b'{') => {
                self.i += 1;
                let mut kv = Vec::new();
                self.ws();
                if self.peek() == Some(b'}') {
                    self.i += 1;
                    return Ok(Value::Obj(kv));
                }
                loop {
                    self.ws();
                    let k = self.string()?;
                    self.ws();
                    self.expect(b':')?;
                    self.ws();
                    let v = self.value(depth + 1)?;
                    kv.push((k, v));
                    self.ws();
                    match self.peek() {
                        Some(b',') => self.i += 1,
                        Some(b'}') => {
                            self.i += 1;
                            return Ok(Value::Obj(kv));
                        }
                        _ => return Err(format!("Diharapkan ',' atau '}}' di posisi {}", self.i)),
                    }
                }
            }
            Some(c) if c == b'-' || c.is_ascii_digit() => self.number(),
            Some(c) => Err(format!("Karakter tak terduga '{}' di posisi {}", c as char, self.i)),
        }
    }
    fn number(&mut self) -> Result<Value, String> {
        let start = self.i;
        if self.peek() == Some(b'-') {
            self.i += 1;
        }
        let mut digits = 0;
        while let Some(c) = self.peek() {
            if c.is_ascii_digit() || matches!(c, b'.' | b'e' | b'E' | b'+' | b'-') {
                if c.is_ascii_digit() {
                    digits += 1;
                }
                self.i += 1;
            } else {
                break;
            }
        }
        let t = std::str::from_utf8(&self.b[start..self.i]).unwrap();
        if digits == 0 || t.parse::<f64>().is_err() {
            return Err(format!("Angka tidak valid '{}'", t));
        }
        Ok(Value::Num(t.to_string()))
    }
    fn hex4(&mut self) -> Result<u32, String> {
        if self.i + 4 > self.b.len() {
            return Err("Escape \\u terpotong".into());
        }
        let s = std::str::from_utf8(&self.b[self.i..self.i + 4]).map_err(|_| "Escape \\u tidak valid")?;
        let v = u32::from_str_radix(s, 16).map_err(|_| "Escape \\u tidak valid".to_string())?;
        self.i += 4;
        Ok(v)
    }
    fn string(&mut self) -> Result<String, String> {
        self.expect(b'"')?;
        let mut out: Vec<u8> = Vec::new();
        loop {
            let Some(c) = self.peek() else {
                return Err("String tidak ditutup".into());
            };
            self.i += 1;
            match c {
                b'"' => break,
                b'\\' => {
                    let Some(e) = self.peek() else {
                        return Err("Escape terpotong".into());
                    };
                    self.i += 1;
                    let ch = match e {
                        b'"' => '"',
                        b'\\' => '\\',
                        b'/' => '/',
                        b'b' => '\u{8}',
                        b'f' => '\u{c}',
                        b'n' => '\n',
                        b'r' => '\r',
                        b't' => '\t',
                        b'u' => {
                            let hi = self.hex4()?;
                            if (0xD800..0xDC00).contains(&hi) {
                                if self.b[self.i..].starts_with(b"\\u") {
                                    self.i += 2;
                                    let lo = self.hex4()?;
                                    if !(0xDC00..0xE000).contains(&lo) {
                                        return Err("Pasangan surrogate tidak valid".into());
                                    }
                                    char::from_u32(0x10000 + ((hi - 0xD800) << 10) + (lo - 0xDC00))
                                        .ok_or("Kode tidak valid")?
                                } else {
                                    return Err("Surrogate tunggal".into());
                                }
                            } else if (0xDC00..0xE000).contains(&hi) {
                                return Err("Surrogate tunggal".into());
                            } else {
                                char::from_u32(hi).ok_or("Kode tidak valid")?
                            }
                        }
                        _ => return Err(format!("Escape tidak dikenal '\\{}'", e as char)),
                    };
                    let mut buf = [0u8; 4];
                    out.extend_from_slice(ch.encode_utf8(&mut buf).as_bytes());
                }
                c if c < 0x20 => return Err("Karakter kontrol dalam string".into()),
                c => out.push(c),
            }
        }
        String::from_utf8(out).map_err(|_| "UTF-8 tidak valid".to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip_and_precision() {
        let t = r#"{"a":[1,-2,3.5,1e3,true,false,null],"s":"x\"y\\z\n","big":9223372036854775807,"f":0.9}"#;
        let v = parse(t).unwrap();
        assert_eq!(v.get("big").unwrap().as_i64(), Some(i64::MAX));
        assert_eq!(v.get("f").unwrap().as_f32(), Some(0.9f32));
        assert_eq!(parse(&v.to_json()).unwrap(), v);
    }

    #[test]
    fn unicode_and_surrogates() {
        let v = parse(r#""Ü ü 😀""#).unwrap();
        assert_eq!(v.as_str(), Some("Ü ü 😀"));
        assert_eq!(parse(&v.to_json()).unwrap(), v);
        assert!(parse(r#""\ud83d""#).is_err());
    }

    #[test]
    fn rejects_malformed() {
        for bad in [
            "",
            "{",
            "[1,]",
            "{\"a\"}",
            "nul",
            "01x",
            "\"abc",
            "{\"a\":1} x",
            "-",
            "[1 2]",
        ] {
            assert!(parse(bad).is_err(), "{bad}");
        }
        let deep = "[".repeat(200) + &"]".repeat(200);
        assert!(parse(&deep).is_err());
    }

    #[test]
    fn f32_always_has_decimal_point_for_integers() {
        assert_eq!(Value::f32(1.0).to_json(), "1.0");
        assert_eq!(Value::f32(0.5).to_json(), "0.5");
        assert_eq!(Value::f32(f32::NAN), Value::Null);
    }
}
