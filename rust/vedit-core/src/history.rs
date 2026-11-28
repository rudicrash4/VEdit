use crate::model::Project;
use std::collections::VecDeque;

/// Undo/redo berbasis snapshot; port `EditHistory.kt` (batas 100).
pub struct History {
    current: Project,
    undo: VecDeque<Project>,
    redo: VecDeque<Project>,
    limit: usize,
}

impl History {
    pub fn new(initial: Project) -> History {
        History {
            current: initial,
            undo: VecDeque::new(),
            redo: VecDeque::new(),
            limit: 100,
        }
    }
    pub fn with_limit(initial: Project, limit: usize) -> History {
        History {
            limit,
            ..History::new(initial)
        }
    }
    pub fn current(&self) -> &Project {
        &self.current
    }
    pub fn can_undo(&self) -> bool {
        !self.undo.is_empty()
    }
    pub fn can_redo(&self) -> bool {
        !self.redo.is_empty()
    }
    pub fn apply(&mut self, next: Project) {
        if next == self.current {
            return;
        }
        self.undo.push_back(std::mem::replace(&mut self.current, next));
        if self.undo.len() > self.limit {
            self.undo.pop_front();
        }
        self.redo.clear();
    }
    pub fn undo(&mut self) -> Option<&Project> {
        let prev = self.undo.pop_back()?;
        self.redo.push_back(std::mem::replace(&mut self.current, prev));
        Some(&self.current)
    }
    pub fn redo(&mut self) -> Option<&Project> {
        let next = self.redo.pop_back()?;
        self.undo.push_back(std::mem::replace(&mut self.current, next));
        Some(&self.current)
    }
}
