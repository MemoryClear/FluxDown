/**
 * GET /api/download/:filename
 *
 * 代理下载私有仓库的 Release 资产。
 * 流程：查找最新 Release 中匹配的 asset → 用 token 获取下载 URL → 302 重定向。
 *
 * 这样用户的浏览器直接从 GitHub CDN 下载，不经过 Vercel serverless 中转流量。
 */

import type { APIRoute } from "astro";

export const prerender = false;

const GITHUB_REPO = import.meta.env.GITHUB_REPO || "user/x_down";
const GITHUB_TOKEN = import.meta.env.GITHUB_TOKEN || "";

interface GitHubAsset {
  name: string;
  url: string;
  browser_download_url: string;
}

interface GitHubRelease {
  draft: boolean;
  prerelease: boolean;
  assets: GitHubAsset[];
}

export const GET: APIRoute = async ({ params }) => {
  const { filename } = params;

  if (!filename) {
    return new Response(JSON.stringify({ error: "Missing filename" }), {
      status: 400,
      headers: { "Content-Type": "application/json" },
    });
  }

  if (!GITHUB_TOKEN) {
    return new Response(
      JSON.stringify({ error: "Server misconfigured: missing GITHUB_TOKEN" }),
      { status: 500, headers: { "Content-Type": "application/json" } },
    );
  }

  try {
    // 获取最新 Release
    const res = await fetch(
      `https://api.github.com/repos/${GITHUB_REPO}/releases?per_page=5`,
      {
        headers: {
          Authorization: `Bearer ${GITHUB_TOKEN}`,
          Accept: "application/vnd.github+json",
          "X-GitHub-Api-Version": "2022-11-28",
        },
      },
    );

    if (!res.ok) {
      return new Response(
        JSON.stringify({ error: `GitHub API error: ${res.status}` }),
        { status: 502, headers: { "Content-Type": "application/json" } },
      );
    }

    const releases: GitHubRelease[] = await res.json();
    const latest = releases.find((r) => !r.draft && !r.prerelease);

    if (!latest) {
      return new Response(
        JSON.stringify({ error: "No published release found" }),
        { status: 404, headers: { "Content-Type": "application/json" } },
      );
    }

    // 查找匹配的 asset
    const asset = latest.assets.find((a) => a.name === filename);

    if (!asset) {
      return new Response(
        JSON.stringify({ error: `Asset "${filename}" not found in latest release` }),
        { status: 404, headers: { "Content-Type": "application/json" } },
      );
    }

    // 通过 GitHub API 获取 asset 的实际下载 URL（带临时 token 的 CDN 链接）
    const assetRes = await fetch(asset.url, {
      headers: {
        Authorization: `Bearer ${GITHUB_TOKEN}`,
        Accept: "application/octet-stream",
      },
      redirect: "manual", // 不自动跟随重定向，捕获 302 的 Location
    });

    const downloadUrl = assetRes.headers.get("Location");

    if (!downloadUrl) {
      // 如果没有重定向（不太可能），回退到流式代理
      return new Response(
        JSON.stringify({ error: "Failed to get download URL from GitHub" }),
        { status: 502, headers: { "Content-Type": "application/json" } },
      );
    }

    // 302 重定向到 GitHub CDN 的临时签名 URL（该 URL 自带认证，有效期约 10 分钟）
    return new Response(null, {
      status: 302,
      headers: {
        Location: downloadUrl,
        "Cache-Control": "private, no-cache",
      },
    });
  } catch (err) {
    return new Response(
      JSON.stringify({ error: "Download failed", detail: String(err) }),
      { status: 500, headers: { "Content-Type": "application/json" } },
    );
  }
};
