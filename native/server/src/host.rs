//! [`ServerApiHost`] —— `fluxdown_api::service::ApiHost` 的 headless 实现。
//!
//! 读操作直查 [`Db`]（Clone）；写操作打包 [`ActorCmd`] + oneshot 经 mpsc
//! 进 actor 事件循环串行执行（照抄 `hub/src/api_host.rs` 的读写分离）。
//!
//! 与桌面唯一的语义差异：[`submit_external`](ApiHost::submit_external)
//! （脚本接管 / aria2 兼容入口）没有确认弹框可弹 —— headless 环境直接
//! 创建任务，透传 `file_size` 提示。

use async_trait::async_trait;
use fluxdown_api::service::{ApiError, ApiHost};
use fluxdown_api::types::{CreateTaskRequest, DownloadRequest, QueueDto, TaskDto};
use fluxdown_engine::db::Db;
use tokio::sync::{mpsc, oneshot};

use crate::actor::ActorCmd;

/// headless 服务器的 API 宿主。
#[derive(Clone)]
pub struct ServerApiHost {
    db: Db,
    cmd_tx: mpsc::Sender<ActorCmd>,
    /// 演示模式：`Some(url)` 时仅允许下载该 URL（`FLUXDOWN_DEMO_URL`）。
    demo_url: Option<String>,
}

/// 演示模式守卫：`demo_url` 已设置且请求 URL 与之不符（trim 后精确比较）
/// 时拒绝创建任务。所有任务创建入口（管理 API / 脚本接管 / aria2 兼容）
/// 都收敛到 [`ServerApiHost`]，在此拦截即覆盖全部路径。
pub fn demo_guard(demo_url: Option<&str>, url: &str) -> Result<(), ApiError> {
    match demo_url {
        Some(allowed) if url.trim() != allowed => Err(ApiError::BadRequest(
            "demo mode: only the designated demo file can be downloaded".to_string(),
        )),
        _ => Ok(()),
    }
}

impl ServerApiHost {
    pub fn new(db: Db, cmd_tx: mpsc::Sender<ActorCmd>, demo_url: Option<String>) -> Self {
        Self {
            db,
            cmd_tx,
            demo_url,
        }
    }

    /// 发送命令并等待回执。actor 侧断开 → 503。
    pub async fn send_cmd<T>(
        &self,
        make: impl FnOnce(oneshot::Sender<T>) -> ActorCmd,
    ) -> Result<T, ApiError> {
        let (ack, rx) = oneshot::channel();
        self.cmd_tx
            .send(make(ack))
            .await
            .map_err(|_| ApiError::Unavailable)?;
        rx.await.map_err(|_| ApiError::Unavailable)
    }

    /// 任务存在性检查（写操作前置），不存在 → 404。
    async fn ensure_task_exists(&self, task_id: &str) -> Result<(), ApiError> {
        match self.db.load_task_by_id(task_id).await {
            Ok(Some(_)) => Ok(()),
            Ok(None) => Err(ApiError::NotFound),
            Err(e) => Err(ApiError::Internal(e.to_string())),
        }
    }
}

#[async_trait]
impl ApiHost for ServerApiHost {
    async fn list_tasks(&self) -> Result<Vec<TaskDto>, ApiError> {
        self.db
            .load_all_tasks()
            .await
            .map(|tasks| tasks.into_iter().map(TaskDto::from).collect())
            .map_err(|e| ApiError::Internal(e.to_string()))
    }

    async fn get_task(&self, task_id: &str) -> Result<Option<TaskDto>, ApiError> {
        self.db
            .load_task_by_id(task_id)
            .await
            .map(|t| t.map(TaskDto::from))
            .map_err(|e| ApiError::Internal(e.to_string()))
    }

    async fn create_task(&self, req: CreateTaskRequest) -> Result<String, ApiError> {
        demo_guard(self.demo_url.as_deref(), &req.url)?;
        self.send_cmd(|ack| ActorCmd::CreateTask {
            req: Box::new(req),
            hint_file_size: 0,
            ack,
        })
        .await?
        .ok_or_else(|| ApiError::Internal("failed to persist task".to_string()))
    }

    async fn delete_task(&self, task_id: &str, delete_files: bool) -> Result<(), ApiError> {
        self.ensure_task_exists(task_id).await?;
        self.send_cmd(|ack| ActorCmd::DeleteTask {
            task_id: task_id.to_string(),
            delete_files,
            ack,
        })
        .await
    }

    async fn pause_task(&self, task_id: &str) -> Result<(), ApiError> {
        self.ensure_task_exists(task_id).await?;
        self.send_cmd(|ack| ActorCmd::PauseTask {
            task_id: task_id.to_string(),
            ack,
        })
        .await
    }

    async fn continue_task(&self, task_id: &str) -> Result<(), ApiError> {
        self.ensure_task_exists(task_id).await?;
        self.send_cmd(|ack| ActorCmd::ContinueTask {
            task_id: task_id.to_string(),
            ack,
        })
        .await
    }

    async fn pause_all(&self) -> Result<(), ApiError> {
        self.send_cmd(|ack| ActorCmd::PauseAll { ack }).await
    }

    async fn continue_all(&self) -> Result<(), ApiError> {
        self.send_cmd(|ack| ActorCmd::ContinueAll { ack }).await
    }

    async fn list_queues(&self) -> Result<Vec<QueueDto>, ApiError> {
        self.db
            .load_all_queues()
            .await
            .map(|qs| qs.into_iter().map(QueueDto::from).collect())
            .map_err(|e| ApiError::Internal(e.to_string()))
    }

    /// headless 无确认弹框：外部下载请求直接创建任务，透传 file_size 提示。
    async fn submit_external(&self, req: DownloadRequest) -> Result<(), ApiError> {
        demo_guard(self.demo_url.as_deref(), &req.url)?;
        let create = CreateTaskRequest {
            url: req.url,
            file_name: req.filename,
            save_dir: req.save_dir,
            segments: 0,
            cookies: req.cookies,
            referrer: req.referrer,
            proxy_url: String::new(),
            user_agent: String::new(),
            queue_id: String::new(),
            checksum: String::new(),
            headers: req.headers,
        };
        self.send_cmd(|ack| ActorCmd::CreateTask {
            req: Box::new(create),
            hint_file_size: req.file_size.unwrap_or(0),
            ack,
        })
        .await?
        .ok_or_else(|| ApiError::Internal("failed to persist task".to_string()))?;
        Ok(())
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests {
    use super::demo_guard;

    const DEMO: &str = "https://example.com/demo.bin";

    #[test]
    fn demo_guard_disabled_allows_any_url() {
        assert!(demo_guard(None, "https://evil.example/anything.iso").is_ok());
    }

    #[test]
    fn demo_guard_allows_exact_demo_url_with_surrounding_whitespace() {
        assert!(demo_guard(Some(DEMO), DEMO).is_ok());
        // 客户端（多行输入框/脚本）常带换行或空格，trim 后仍应放行。
        assert!(demo_guard(Some(DEMO), &format!("  {DEMO}\n")).is_ok());
    }

    #[test]
    fn demo_guard_rejects_other_urls() {
        for url in [
            "https://evil.example/anything.iso",
            // 前缀伪装：demo URL 追加查询串/路径不得放行（精确比较）。
            &format!("{DEMO}?x=1"),
            &format!("{DEMO}/../secret"),
            // 大小写变体也不放行（URL path 大小写敏感，保守精确匹配）。
            "https://example.com/DEMO.bin",
            "",
        ] {
            assert!(
                demo_guard(Some(DEMO), url).is_err(),
                "should reject {url:?}"
            );
        }
    }
}
