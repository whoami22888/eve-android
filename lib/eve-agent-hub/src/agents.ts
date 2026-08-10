import type { AgentDefinition, AgentId } from "./types";

export const AGENTS: readonly AgentDefinition[] = [
  {
    id: "planner",
    name: "Planner",
    role: "Orchestrator",
    description: "Breaks requests into dependency-aware tasks and coordinates the other agents.",
  },
  {
    id: "coder",
    name: "Coder",
    role: "Implementation",
    description: "Creates and edits project code, configuration and tests within the workspace.",
  },
  {
    id: "reviewer",
    name: "Reviewer",
    role: "Code review",
    description: "Reviews proposed changes for correctness, maintainability and regressions.",
  },
  {
    id: "tester",
    name: "Tester",
    role: "Verification",
    description: "Runs available checks/builds and turns failures into actionable tasks.",
  },
  {
    id: "security",
    name: "Security",
    role: "Security review",
    description: "Checks permissions, dependencies, secrets and unsafe implementation patterns.",
  },
];

export const AGENT_ORDER: readonly AgentId[] = [
  "planner",
  "coder",
  "reviewer",
  "tester",
  "security",
];
