import { OpenAPI } from "../../generated/core/OpenAPI";
import { request } from "../../generated/core/request";

export interface ContestProblem {
  id: string;
  problemCode: string;
  title: string;
  content: string;
  limits: { timeLimit: number; memoryLimit: number };
}
export interface DraftProblem {
  problemCode: string;
  sourceQuestionId?: string;
  title?: string;
  content?: string;
  limits?: { timeLimit: number; memoryLimit: number };
  cases?: Array<{ input: string; output: string }>;
}
export interface ContestDraft {
  expectedVersion?: number;
  title: string;
  contestType: string;
  capacity: number;
  registrationOpenAt: string;
  registrationCloseAt: string;
  startAt: string;
  endAt: string;
  problems: DraftProblem[];
}
export interface Contest {
  id: string;
  title: string;
  contestType: string;
  capacity: number;
  remainingCapacity: number;
  registrationOpenAt: string;
  registrationCloseAt: string;
  startAt: string;
  endAt: string;
  status: string;
  phase: string;
  version: number;
  publishedVersion: number;
  registrationReady: boolean;
  ruleVersion: string;
  registrationMode: string;
  serverTime: string;
  problems?: ContestProblem[];
  draft?: ContestDraft;
}
export interface Registration {
  requestId: string | null;
  status: string;
  qualificationValid: boolean;
  contestStatus: string;
}
export interface SubmissionStatus {
  id: string;
  executionStatus?: string;
  status?: number;
  acceptedAt?: string;
  judgeInfo?: {
    verdict?: string;
    message?: string;
    time?: number;
    memory?: number;
  };
}
type Method = "GET" | "POST" | "PUT";
export async function contestRequest<T>(
  path = "",
  method: Method = "GET",
  body?: unknown,
  key?: string
): Promise<T> {
  const response = await request<{ code: number; data: T; message?: string }>(
    OpenAPI,
    {
      method,
      url: `/api/contests${path}`,
      body,
      mediaType: body === undefined ? undefined : "application/json",
      headers: key ? { "Idempotency-Key": key } : undefined,
    }
  );
  if (response.code !== 0) throw new Error(response.message ?? "请求未完成");
  return response.data;
}
export async function submissionStatus(id: string): Promise<SubmissionStatus> {
  const response = await request<{ code: number; data: SubmissionStatus }>(
    OpenAPI,
    {
      method: "GET",
      url: `/api/submissions/${encodeURIComponent(id)}`,
    }
  );
  if (response.code !== 0) throw new Error("判题状态暂不可用");
  return response.data;
}
export function errorText(error: unknown): string {
  const failure = error as { body?: { message?: string }; message?: string };
  const message =
    failure?.body?.message ??
    failure?.message ??
    "服务暂不可用，请保留原请求重试";
  if (message === "REGISTRATION_LEDGER_LIMIT") {
    return "本场报名受理已达上限，已有报名仍可查询和确认";
  }
  return message;
}
const memory = new Map<string, { fingerprint: string; key: string }>();
/** Store request identity only. Never put source code or hidden judge data in browser storage. */
export async function stableKey(
  scope: string,
  payload: unknown
): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(JSON.stringify(payload))
  );
  const fingerprint = Array.from(new Uint8Array(digest), (b) =>
    b.toString(16).padStart(2, "0")
  ).join("");
  let pending = memory.get(scope);
  try {
    const saved = sessionStorage.getItem(scope);
    if (saved) pending = JSON.parse(saved);
  } catch {
    /* In-memory identity still survives network retries. */
  }
  if (pending?.fingerprint !== fingerprint) {
    pending = {
      fingerprint,
      key: Array.from(crypto.getRandomValues(new Uint8Array(16)), (b) =>
        b.toString(16).padStart(2, "0")
      ).join(""),
    };
  }
  memory.set(scope, pending);
  try {
    sessionStorage.setItem(scope, JSON.stringify(pending));
  } catch {
    /* Storage may be disabled. */
  }
  return pending.key;
}
export function clearKey(scope: string) {
  memory.delete(scope);
  try {
    sessionStorage.removeItem(scope);
  } catch {
    /* Storage may be disabled. */
  }
}
export const localTime = (value?: string) =>
  value ? new Date(value).toLocaleString() : "—";
export const phaseLabel: Record<string, string> = {
  DRAFT: "草稿",
  BEFORE_REGISTRATION: "报名未开始",
  REGISTRATION_OPEN: "报名中",
  WAITING_START: "等待开赛",
  RUNNING: "比赛中",
  FINALIZING: "结算中",
  FINISHED: "已结束",
  CANCELLED: "已取消",
};
