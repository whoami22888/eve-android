import { AGENTS } from "./agents";
import type {
  AgentEvent,
  AgentId,
  AgentRuntime,
  AgentState,
  AgentTask,
  AgentWorkspace,
} from "./types";

const now = () => Date.now();

export function createWorkspace(projectId: string, projectName: string, rootPath: string): AgentWorkspace {
  const agents: AgentState[] = AGENTS.map((agent) => ({
    ...agent,
    status: "idle",
    progress: 0,
  }));

  return { projectId, projectName, rootPath, tasks: [], agents, events: [] };
}

export class AgentOrchestrator {
  constructor(private readonly runtime: AgentRuntime) {}

  addTask(workspace: AgentWorkspace, task: Omit<AgentTask, "status" | "progress">): AgentTask {
    const created: AgentTask = { ...task, status: "queued", progress: 0 };
    workspace.tasks.push(created);
    this.record(workspace, {
      id: `${created.id}:queued`,
      timestamp: now(),
      agent: created.assignee,
      type: "task",
      message: `Queued: ${created.title}`,
    });
    return created;
  }

  async runTask(workspace: AgentWorkspace, taskId: string, signal?: AbortSignal): Promise<void> {
    const task = workspace.tasks.find((item) => item.id === taskId);
    if (!task) throw new Error(`Unknown task: ${taskId}`);

    task.status = "working";
    this.setAgent(workspace, task.assignee, { status: "working", currentTask: task.title, progress: 0 });

    try {
      for await (const event of this.runtime.execute({ workspace, task, signal })) {
        this.record(workspace, event);
        if (event.type === "status" && event.message === "done") task.progress = 100;
      }
      task.status = "done";
      task.progress = 100;
      this.setAgent(workspace, task.assignee, { status: "done", progress: 100 });
    } catch (error) {
      task.status = "error";
      this.setAgent(workspace, task.assignee, {
        status: "error",
        lastMessage: error instanceof Error ? error.message : "Agent execution failed",
      });
      throw error;
    }
  }

  private setAgent(workspace: AgentWorkspace, id: AgentId, patch: Partial<AgentState>) {
    const agent = workspace.agents.find((item) => item.id === id);
    if (agent) Object.assign(agent, patch);
  }

  private record(workspace: AgentWorkspace, event: AgentEvent) {
    workspace.events.push(event);
    const agent = workspace.agents.find((item) => item.id === event.agent);
    if (agent) agent.lastMessage = event.message;
  }
}
