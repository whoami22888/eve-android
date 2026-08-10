export type AgentId =
  | "planner"
  | "coder"
  | "reviewer"
  | "tester"
  | "security";

export type AgentStatus = "idle" | "queued" | "working" | "blocked" | "done" | "error";

export interface AgentDefinition {
  id: AgentId;
  name: string;
  role: string;
  description: string;
}

export interface AgentState extends AgentDefinition {
  status: AgentStatus;
  currentTask?: string;
  progress: number;
  lastMessage?: string;
}

export interface AgentTask {
  id: string;
  title: string;
  description: string;
  assignee: AgentId;
  status: AgentStatus;
  progress: number;
  dependsOn?: string[];
}

export interface AgentEvent {
  id: string;
  timestamp: number;
  agent: AgentId;
  type: "status" | "message" | "task" | "error";
  message: string;
}

export interface AgentWorkspace {
  projectId: string;
  projectName: string;
  rootPath: string;
  tasks: AgentTask[];
  agents: AgentState[];
  events: AgentEvent[];
}

export interface AgentExecutionContext {
  workspace: AgentWorkspace;
  task: AgentTask;
  signal?: AbortSignal;
}

export interface AgentRuntime {
  execute(context: AgentExecutionContext): AsyncIterable<AgentEvent>;
}
