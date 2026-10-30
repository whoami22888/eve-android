# Manus Hybrid: Render Free deployment gate

The dedicated Python cloud bridge forwards authenticated EVE Android task requests to a **separately deployed Hermes Agent**. It does not contain Hermes, model weights, private Manus services, or free inference.

## User-authorized Render workspace

- Render account: whoami22888@gmail.com, **My Workspace**, ID `tea-dbeniess728c73cqem2g`.
- Audit: existing `isles-of-mythos` web service on **free** plan, no Postgres, no Key Value. Keep the unrelated service untouched.
- Render free web services share **750 active instance-hours per month**, spin down after 15 minutes of inactivity, and lose local disk contents on restarts/redeploys.
- **Do not deploy until zero-charge eligibility is confirmed.** Render's Free plan has no fixed compute charge, but workspaces with a payment method can incur supplementary **bandwidth and build-minute charges**. Render's connector did not expose billing state or overage settings. Verify Billing (payment method, included usage, spend limits). If absolute zero charge is required and a payment method is on file, do not deploy because a universal zero-bandwidth-overage cap has not been established.

## Prerequisites for a working Manus-style task

1. Live Hermes gateway, on an HTTPS URL reachable from Render, with a valid API_SERVER_KEY.
2. Legitimate model provider configured for Hermes (no subscription bypass or arbitrary modded APK credential reuse).
3. Independent random bridge token for `AGENT_APP_TOKEN` (>=24 chars). Set both tokens and HERMES_BASE_URL in Render environment settings. Never commit credentials.
4. EVE Android PR #8 uses `GET /ready`, `POST /api/runs`, `GET /api/runs/{id}`, `POST /api/runs/{id}/stop`. The bridge will refuse startup without both secrets.

## Deployment after financial prerequisites

Create a *new* free Python Render web service from `whoami22888/eve-android`, branch `feat/manus-hybrid-free-cloud-bridge`, or import its root `render.yaml`. Region Singapore. Build: `pip install -r cloud_bridge/requirements.txt`. Start: `uvicorn cloud_bridge.app:app --host 0.0.0.0 --port $PORT`. Disable auto-deploy and do not create workers, paid databases or disks.

## Verification

Check `GET /health` (bridge-process only), authenticated `GET /ready` (Hermes reachable), and one actual run through create/status/stop. Keep the existing game service isolated. Free web services cannot guarantee always-on jobs or persistent agent memory.
