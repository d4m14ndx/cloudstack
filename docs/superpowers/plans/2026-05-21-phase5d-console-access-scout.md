# Phase 5d Console Access Scout

## Scope

This is an investigation note for wiring instance console access into the Phase 5
Next.js UI. It intentionally does not implement the BFF or UI behavior.

## Findings

### Command

The user-facing command is `createConsoleEndpoint`.

- API registration and authorization live in
  `api/src/main/java/org/apache/cloudstack/api/command/user/consoleproxy/CreateConsoleEndpointCmd.java:43`.
- The command has been available since CloudStack `4.18.0` and is authorized for
  `Admin`, `ResourceAdmin`, `DomainAdmin`, and `User` roles.
- `ManagementServerImpl` registers `CreateConsoleEndpointCmd` and
  `ListConsoleSessionsCmd` in the API command list.
- The legacy Vue UI checks whether `createConsoleEndpoint` is present in the
  user's API list before showing the console button.
- The only integration smoke coverage found is
  `test/integration/smoke/test_console_endpoint.py:test_console_endpoint_permissions`.

There is also a related admin/observability command, `listConsoleSessions`, but
that is not needed for launching an instance console in Phase 5.

### Parameters

`createConsoleEndpoint` accepts:

- `virtualmachineid` - required UUID of the instance.
- `token` - optional extra security token, used only when extra validation is
  enabled.
- `client-inet-address` - not declared as a normal `@Parameter`; the command
  reads it from the full URL parameter map via `ConsoleAccessUtils.CLIENT_INET_ADDRESS_KEY`.
  This becomes the console endpoint creator/source address recorded in session
  state. The current Phase 5 generic BFF can pass this key because
  `web/lib/cloudstack/request.ts` allows hyphenated parameter names.

The Phase 5 UI should normally send only `virtualmachineid`. Do not invent a
client-side `token`; the existing system only uses it when some caller has a
specific extra-validation flow.

### Response Shape

The command returns a synchronous `createconsoleendpointresponse` containing a
`consoleendpoint` object. Important fields:

- `success` - boolean result.
- `url` - launch URL when `success` is true; `null` on permission failures.
- `details` - human-readable failure reason.
- `websocket` - optional object with `host`, `port`, `path`, `token`, and `extra`.

The response does not return an async job id. It creates or denies the endpoint
immediately. Existing smoke coverage asserts a permitted user receives
`success == true` and non-empty `url`, while a different account receives
`success == false` and no URL.

### Server Behavior

`ConsoleAccessManagerImpl.generateConsoleEndpoint` performs the meaningful
server-side checks:

- Refuses when console services or ticket hash keys are not ready.
- Finds the VM and validates the calling account against it.
- Denies router, console proxy, secondary storage VM, unknown VM types, LXC, Edge
  zones, unsupported VM states, missing host, and missing console proxy URL root.
- Generates a fresh session UUID per call.
- Persists a console session row.
- For proxied VNC/noVNC, encrypts a `ConsoleProxyClientParam` into the console
  token and calls `setConsoleAccessForVm`, making the session one-use on the
  console proxy side.
- For external hypervisors/direct mode, returns the direct URL and does not call
  `setConsoleAccessForVm`.

The generated URL can include:

- `/resource/noVNC/vnc.html?autoconnect=true&show_dot=...&port=...&token=...`
- `/ajax?token=...` for Hyper-V or when noVNC is not the default.
- `&extra=...` when an extra security token is supplied.
- `&guest=windows` for Windows guest OS category.

That URL contains sensitive, short-lived session material. Treat it as a secret
for logging, persistence, browser history, analytics, and client state.

### Legacy UI Behavior

Legacy console access is implemented in `ui/src/components/widgets/Console.vue`.

Behavior to preserve:

- Show only when the route is a VM-like route, `listVirtualMachines` exists, and
  `createConsoleEndpoint` exists.
- Disable for `Stopped`, `Restoring`, `Error`, `Destroyed`, and
  `hostcontrolstate === 'Offline'`.
- Launch is user-initiated.
- Support both "open console" and "copy console URL" actions.
- If `resource.details['External:console_url']` exists, use that direct URL
  without calling `createConsoleEndpoint`.
- Otherwise call `postAPI('createConsoleEndpoint', { virtualmachineid: resource.id })`.
- Open the returned URL in a new tab only when `success` is true; otherwise show
  `details`.

Behavior to avoid or improve:

- Do not keep the returned URL in long-lived React state or server-rendered HTML.
- Do not log the URL, websocket token, or extra value.
- Do not prefetch or generate console URLs during page render. A call appears to
  persist session state and should stay click-triggered.
- Avoid showing/copying the URL from a passive server component.
- Consider `rel="noopener noreferrer"` behavior for any direct anchor/open flow.

## Recommended Phase 5 Design

Keep this as a tiny helper plus client-side button flow; the existing generic
`/api/cs/[command]` BFF can already proxy the command safely if the helper sends
only safe JSON.

Suggested implementation slice:

1. Add `web/lib/cloudstack/instance-console.ts`.
   - Export `createConsoleEndpoint(virtualMachineId, options?)`.
   - POST to `/api/cs/createConsoleEndpoint` with
     `{ virtualmachineid: virtualMachineId }`.
   - Normalize the response to:
     `{ success: true, url, websocket? }` or
     `{ success: false, details }`.
   - Reject malformed successful responses with missing/empty `url`.
   - Preserve `websocket` fields in the type for later noVNC embedding work, but
     do not use them in the first UI slice.

2. Add `web/lib/cloudstack/instance-console.test.ts`.
   - `createConsoleEndpoint posts virtualmachineid only to the BFF`
   - `createConsoleEndpoint normalizes successful URL responses`
   - `createConsoleEndpoint returns failure details without a URL`
   - `createConsoleEndpoint rejects malformed successful responses`
   - `createConsoleEndpoint does not pass sessionkey command or response`

3. Add a small client component, likely
   `web/components/instances/instance-console.tsx`.
   - Props: `id`, `name`, `state`, optional `hostControlState`, optional
     `externalConsoleUrl`.
   - Disable for stopped/restoring/error/destroyed/offline states.
   - On click, if `externalConsoleUrl` exists, `window.open` it.
   - Otherwise call the helper and open the returned URL in a new tab.
   - Keep status text minimal: idle, launching, unavailable/error.
   - Do not render the URL into the DOM.

4. Wire into `web/app/(app)/instances/[id]/page.tsx`.
   - Replace the current placeholder `ConsolePanel` body with the client
     component.
   - Pass only detail fields needed by the client component.
   - Consider extending `InstanceDetail` to expose
     `details['External:console_url']` from `listVirtualMachines` if the API
     response includes VM details in Phase 5.

5. Optional later slice: if Phase 5 wants embedded noVNC instead of opening the
   CloudStack console page, use the `websocket` response fields. That should be a
   separate security/design pass because it moves more console session material
   into the Next.js UI.

## Risks And Notes

- `createConsoleEndpoint` is synchronous, but it has side effects: session
  persistence and one-use session registration. Avoid automatic calls.
- Returned `url`, `websocket.token`, and `websocket.extra` are sensitive.
- The existing API annotation says `responseHasSensitiveInfo = false`, but the
  generated URL contains a console token. Phase 5 should treat it as sensitive
  anyway.
- The generic BFF currently proxies arbitrary valid command names. That is
  acceptable for this small slice, but console launch should still go through a
  typed helper so UI code cannot accidentally pass extra parameters.
- Legacy direct external console URLs bypass `createConsoleEndpoint`. Preserve
  the behavior only for URLs already present in VM details; do not let the client
  submit arbitrary direct console URLs.
- Permission and state checks belong to CloudStack. The Next.js UI should only
  mirror obvious disabled states for ergonomics, not as the security boundary.

## Source References

- `api/src/main/java/org/apache/cloudstack/api/command/user/consoleproxy/CreateConsoleEndpointCmd.java:43-110`
- `api/src/main/java/org/apache/cloudstack/api/response/CreateConsoleEndpointResponse.java:29-43`
- `api/src/main/java/org/apache/cloudstack/api/response/ConsoleEndpointWebsocketResponse.java:29-47`
- `server/src/main/java/org/apache/cloudstack/consoleproxy/ConsoleAccessManagerImpl.java:276-320`
- `server/src/main/java/org/apache/cloudstack/consoleproxy/ConsoleAccessManagerImpl.java:346-427`
- `server/src/main/java/org/apache/cloudstack/consoleproxy/ConsoleAccessManagerImpl.java:513-547`
- `server/src/main/java/org/apache/cloudstack/consoleproxy/ConsoleAccessManagerImpl.java:564-632`
- `services/console-proxy/server/src/main/java/com/cloud/consoleproxy/ConsoleProxy.java:190-220`
- `ui/src/components/widgets/Console.vue:20-100`
- `test/integration/smoke/test_console_endpoint.py:99-122`
- `web/app/(app)/instances/[id]/page.tsx:53-145`
- `web/lib/cloudstack/request.ts`
- `web/app/api/cs/[command]/route-core.ts`
