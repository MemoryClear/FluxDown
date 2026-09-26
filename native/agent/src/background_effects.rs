//! 下载完成通知与活动下载期间的系统保持唤醒。

use std::collections::HashMap;

use fluxdown_protocol::{AgentSnapshot, SnapshotBody};
use tokio::sync::broadcast;
use tokio_util::sync::CancellationToken;

use crate::event_hub::AgentEventHub;

pub struct BackgroundEffects {
    events: AgentEventHub,
}

impl BackgroundEffects {
    #[must_use]
    pub fn new(events: AgentEventHub) -> Self {
        Self { events }
    }

    pub async fn run(self, cancel: CancellationToken) {
        let (mut receiver, snapshot) = self.events.subscribe_and_snapshot();
        let mut awake = None;
        let mut statuses = {
            let initial = agent_snapshot(snapshot);
            reconcile_awake(should_keep_awake(&initial), &mut awake).await;
            initial
                .daemon
                .tasks
                .iter()
                .map(|task| (task.task_id.clone(), task.status))
                .collect::<HashMap<_, _>>()
        };
        loop {
            tokio::select! {
                _ = cancel.cancelled() => return,
                event = receiver.recv() => {
                    match event {
                        Ok(_) | Err(broadcast::error::RecvError::Lagged(_)) => {
                            // 每个进度帧都会走到这里：只在锁内提取所需字段，不克隆整份快照。
                            let (completed, should_hold) = self.events.inspect(|snapshot| {
                                (
                                    take_new_completions(snapshot, &mut statuses),
                                    should_keep_awake(snapshot),
                                )
                            });
                            notify_completions(completed);
                            reconcile_awake(should_hold, &mut awake).await;
                        }
                        Err(broadcast::error::RecvError::Closed) => return,
                    }
                }
            }
        }
    }
}

fn agent_snapshot(snapshot: fluxdown_protocol::Snapshot) -> AgentSnapshot {
    match snapshot.body {
        SnapshotBody::Agent(snapshot) => *snapshot,
        SnapshotBody::Daemon(_) => AgentSnapshot::default(),
    }
}

/// 用最新任务状态原地刷新 `statuses`，返回本次新转为完成（status 3）且需要
/// 通知的文件名。首次出现的任务只登记不通知；已删除的任务从表中剔除。
fn take_new_completions(
    snapshot: &AgentSnapshot,
    statuses: &mut HashMap<String, i32>,
) -> Vec<String> {
    let enabled = preference_bool(snapshot, "download.notify_on_complete");
    let tasks = &snapshot.daemon.tasks;
    let mut completed = Vec::new();
    for task in tasks {
        match statuses.get_mut(&task.task_id) {
            Some(previous) => {
                if enabled && task.status == 3 && *previous != 3 {
                    completed.push(task.file_name.clone());
                }
                *previous = task.status;
            }
            None => {
                statuses.insert(task.task_id.clone(), task.status);
            }
        }
    }
    // 上面的循环保证 statuses ⊇ 当前任务；只有发生删除时长度才会更大。
    if statuses.len() != tasks.len() {
        statuses.retain(|task_id, _| tasks.iter().any(|task| task.task_id == *task_id));
    }
    completed
}

fn notify_completions(file_names: Vec<String>) {
    for file_name in file_names {
        tokio::task::spawn_blocking(move || {
            let _ = notify_rust::Notification::new()
                .summary("FluxDown")
                .body(&file_name)
                .show();
        });
    }
}

fn should_keep_awake(snapshot: &AgentSnapshot) -> bool {
    preference_bool(snapshot, "download.keep_awake")
        && snapshot
            .daemon
            .tasks
            .iter()
            .any(|task| matches!(task.status, 1 | 5))
}

async fn reconcile_awake(should_hold: bool, awake: &mut Option<keepawake::KeepAwake>) {
    if should_hold && awake.is_none() {
        match tokio::task::spawn_blocking(|| {
            keepawake::Builder::default()
                .idle(true)
                .sleep(true)
                .reason("FluxDown active download")
                .app_name("FluxDown")
                .app_reverse_domain("dev.zerx.fluxdown")
                .create()
        })
        .await
        {
            Ok(Ok(guard)) => *awake = Some(guard),
            Ok(Err(error)) => tracing::warn!(error = %error, "could not inhibit sleep"),
            Err(error) => tracing::warn!(error = %error, "keep-awake worker failed"),
        }
    } else if !should_hold {
        *awake = None;
    }
}

fn preference_bool(snapshot: &AgentSnapshot, key: &str) -> bool {
    snapshot
        .preferences
        .values
        .get(key)
        .and_then(serde_json::Value::as_bool)
        .unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use std::collections::HashMap;

    use fluxdown_protocol::{AgentPreferencesDto, AgentSnapshot, TaskDto};
    use serde_json::json;

    use super::{preference_bool, take_new_completions};

    fn task(task_id: &str, status: i32) -> Result<TaskDto, serde_json::Error> {
        serde_json::from_value(json!({
            "taskId": task_id,
            "url": "https://example.com/file",
            "fileName": format!("{task_id}.bin"),
            "saveDir": "/tmp",
            "status": status,
            "downloadedBytes": 0,
            "totalBytes": 100,
            "errorMessage": "",
            "createdAt": "1",
            "proxyUrl": "",
            "queueId": "main",
            "checksum": ""
        }))
    }

    #[test]
    fn completions_fire_once_per_transition_and_forget_deleted_tasks()
    -> Result<(), serde_json::Error> {
        let mut snapshot = AgentSnapshot::default();
        snapshot
            .preferences
            .values
            .insert("download.notify_on_complete".to_owned(), json!(true));
        let mut statuses = HashMap::new();

        // 首次出现的任务（含已完成的）只登记，不通知。
        snapshot.daemon.tasks = vec![task("a", 1)?, task("b", 3)?];
        assert!(take_new_completions(&snapshot, &mut statuses).is_empty());

        // a 从下载中转为完成：恰好通知一次，重复帧不再通知。
        snapshot.daemon.tasks = vec![task("a", 3)?, task("b", 3)?];
        assert_eq!(take_new_completions(&snapshot, &mut statuses), ["a.bin"]);
        assert!(take_new_completions(&snapshot, &mut statuses).is_empty());

        // b 被删除后以同 id 重新出现并直接是完成态：视为新任务，不通知。
        snapshot.daemon.tasks = vec![task("a", 3)?];
        assert!(take_new_completions(&snapshot, &mut statuses).is_empty());
        assert!(!statuses.contains_key("b"));
        snapshot.daemon.tasks = vec![task("a", 3)?, task("b", 3)?];
        assert!(take_new_completions(&snapshot, &mut statuses).is_empty());

        // 关闭通知开关后，跃迁仍被记录但不产生通知。
        snapshot
            .preferences
            .values
            .insert("download.notify_on_complete".to_owned(), json!(false));
        snapshot.daemon.tasks = vec![task("a", 1)?, task("b", 3)?];
        assert!(take_new_completions(&snapshot, &mut statuses).is_empty());
        snapshot.daemon.tasks = vec![task("a", 3)?, task("b", 3)?];
        assert!(take_new_completions(&snapshot, &mut statuses).is_empty());
        assert_eq!(statuses.get("a"), Some(&3));
        Ok(())
    }

    #[test]
    fn namespaced_agent_preferences_drive_background_effects() {
        let mut snapshot = AgentSnapshot {
            preferences: AgentPreferencesDto::default(),
            ..AgentSnapshot::default()
        };
        snapshot
            .preferences
            .values
            .insert("download.keep_awake".to_owned(), json!(true));
        assert!(preference_bool(&snapshot, "download.keep_awake"));
        assert!(!preference_bool(&snapshot, "download.notify_on_complete"));
    }
}
