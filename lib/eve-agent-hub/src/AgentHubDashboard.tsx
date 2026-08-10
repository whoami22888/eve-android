import React from "react";
import { Bot, CheckCircle2, Circle, Loader2, ShieldCheck, TestTube2, Wrench } from "lucide-react";
import type { AgentState, AgentStatus, AgentWorkspace } from "./types";

const icons: Record<AgentState["id"], React.ReactNode> = {
  planner: <Bot size={18} />,
  coder: <Wrench size={18} />,
  reviewer: <CheckCircle2 size={18} />,
  tester: <TestTube2 size={18} />,
  security: <ShieldCheck size={18} />,
};

const statusLabel: Record<AgentStatus, string> = {
  idle: "Ready",
  queued: "Queued",
  working: "Working",
  blocked: "Blocked",
  done: "Done",
  error: "Error",
};

export interface AgentHubDashboardProps {
  workspace: AgentWorkspace;
  onRun?: () => void;
  onStop?: () => void;
}

export function AgentHubDashboard({ workspace, onRun, onStop }: AgentHubDashboardProps) {
  const active = workspace.agents.filter((agent) => agent.status === "working").length;
  const progress = workspace.tasks.length
    ? Math.round(workspace.tasks.reduce((sum, task) => sum + task.progress, 0) / workspace.tasks.length)
    : 0;

  return (
    <section className="eve-agent-hub" aria-label="Eve Agent Hub">
      <header className="eve-agent-hub__header">
        <div>
          <p className="eve-agent-hub__eyebrow">EVE AGENT HUB</p>
          <h1>{workspace.projectName}</h1>
          <p>{active ? `${active} agent${active === 1 ? "" : "s"} working` : "Ready for a task"}</p>
        </div>
        <div className="eve-agent-hub__actions">
          <button type="button" onClick={onRun}>Run</button>
          <button type="button" onClick={onStop}>Stop</button>
        </div>
      </header>

      <div className="eve-agent-hub__progress">
        <div><span>Project progress</span><strong>{progress}%</strong></div>
        <progress max={100} value={progress} />
      </div>

      <div className="eve-agent-hub__agents">
        {workspace.agents.map((agent) => (
          <article className="eve-agent-card" key={agent.id}>
            <div className="eve-agent-card__icon">{icons[agent.id]}</div>
            <div className="eve-agent-card__body">
              <strong>{agent.name}</strong>
              <span>{agent.role}</span>
              <small>{agent.currentTask ?? agent.lastMessage ?? agent.description}</small>
            </div>
            <div className={`eve-agent-card__status eve-agent-card__status--${agent.status}`}>
              {agent.status === "working" ? <Loader2 size={14} className="eve-spin" /> : <Circle size={10} />}
              {statusLabel[agent.status]}
            </div>
          </article>
        ))}
      </div>
    </section>
  );
}
