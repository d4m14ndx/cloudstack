# Deploy Wizard smoke coverage plan

Initial runnable coverage now lives in `web/tests/e2e/deploy-wizard.spec.ts`. Keep this plan as the deeper backlog for advanced options, guarded-launch edge cases, and async pending-progress coverage.

## Target file

`web/tests/e2e/deploy-wizard.spec.ts`

## Entry point

- Navigate to `/`.
- Open the wizard with `page.getByRole("button", { name: "Deploy", exact: true }).click()`.
- Assert the dialog with `page.getByRole("dialog", { name: "Deploy instance" })`.
- The command palette route is also available via the `cloudstack:open-deploy-wizard` event, but the topbar button is the most user-realistic smoke entry.

## Mocked BFF commands

Use the shared mocked BFF helper to intercept these browser requests:

- `GET /api/cs/listZones`
- `GET /api/cs/listTemplates?templatefilter=executable&details=min&showunique=true`
- `GET /api/cs/listServiceOfferings`
- `GET /api/cs/listDiskOfferings`
- `GET /api/cs/listNetworks?listall=true`
- `GET /api/cs/listSecurityGroups?listall=true`
- `GET /api/cs/listSSHKeyPairs`
- `GET /api/cs/listProjects?listall=true`
- `GET /api/cs/listAffinityGroups?listall=true`
- `POST /api/cs/deployVirtualMachine`
- `GET /api/cs/queryAsyncJobResult?jobid=job-smoke-1`

Suggested catalog payloads can mirror `web/lib/mock-data.ts`, but the smoke fixtures should be smaller:

- Zone: `z-smoke-1`, name `smoke-zone`, allocation state `Enabled`.
- Template: `tmpl-ubuntu`, name `Ubuntu 24.04 LTS`, OS `Ubuntu`, size `3000000000`.
- Service offering: `so-small`, name `Compute-S`, `cpunumber: 1`, `memory: 2048`.
- Disk offerings:
  - `do-fixed`, name `Root default`, `disksize: 42949672960`, `customized: false`.
  - `do-custom`, name `Custom data disk`, `iscustomized: true`.
- Network: `net-smoke`, name `smoke-vpc`, CIDR `10.44.0.0/24`, gateway `10.44.0.1`.
- Security group: `sg-default`, name `default`.
- SSH key: `ssh-platform`, name `platform-admin`.
- Project: `project-smoke`, name `smoke-project`.
- Affinity group: `ag-spread`, name `spread-smoke`, type `host anti-affinity`.

Deploy response:

```json
{
  "deployvirtualmachineresponse": {
    "id": "vm-smoke-1",
    "jobid": "job-smoke-1"
  }
}
```

Async job polling responses:

```json
[
  {
    "queryasyncjobresultresponse": {
      "jobid": "job-smoke-1",
      "jobstatus": 0,
      "jobprocstatus": 40
    }
  },
  {
    "queryasyncjobresultresponse": {
      "jobid": "job-smoke-1",
      "jobstatus": 1,
      "jobresult": {
        "virtualmachine": {
          "id": "vm-smoke-1",
          "name": "smoke-vm-01",
          "state": "Running"
        }
      }
    }
  }
]
```

## Spec 1: catalog load

Flow:

1. Route all catalog endpoints.
2. Open the Deploy Wizard.
3. Wait for `Loading catalog` to disappear.
4. Assert defaults and catalog-backed options are visible.

Selectors and assertions:

- `page.getByRole("dialog", { name: "Deploy instance" })`
- `page.getByLabel("Name")` should have value `vm-smoke-zone-preview`.
- `page.getByRole("button", { name: /smoke-zone/i })`
- `page.getByRole("button", { name: /Ubuntu 24\.04 LTS/i })`
- `page.getByRole("button", { name: "Continue" })` should be enabled.

Expected network assertions:

- All nine catalog endpoints are requested once.
- Catalog requests are `GET`.
- No deploy or async-job request is made during load.

## Spec 2: advanced option entry

Flow:

1. Open the wizard with catalog mocks.
2. Fill `Name` with `smoke-vm-01`.
3. Select project `smoke-project`.
4. Continue through size and network.
5. On storage, select `Custom data disk`, enter `75` in `Size`.
6. On access, select SSH key `platform-admin`, affinity group `spread-smoke / host anti-affinity`, turn off `Start after deploy`, and edit `User data`.
7. Continue to Review.

Selectors and assertions:

- `page.getByLabel("Name").fill("smoke-vm-01")`
- Current branch needs accessible names added before this can stay role-only:
  - Project select should expose `Project`.
  - SSH key select should expose `SSH key`.
  - Affinity group select should expose `Affinity group`.
  - Start switch should expose `Start after deploy`.
- Until reconciled, use a local helper scoped to the dialog, not global CSS selectors.
- `page.getByRole("button", { name: /Compute-S/i }).click()`
- `page.getByRole("button", { name: /smoke-vpc/i }).click()`
- `page.getByRole("button", { name: /Custom data disk/i }).click()`
- `page.getByLabel("Size").fill("75")`
- `page.getByLabel("User data").fill("#cloud-config\npackages:\n  - nginx")`
- Review table should contain:
  - `smoke-vm-01`
  - `smoke-project`
  - `Custom data disk`
  - `75 GiB`
  - `platform-admin`
  - `spread-smoke`
  - `No` for `Start after deploy`

## Spec 3: guarded launch submit

Flow:

1. Open the wizard.
2. Navigate to storage.
3. Select `Custom data disk`.
4. Clear or enter `0` in `Size`.
5. Continue should be disabled and Review should not be reachable.
6. Enter `75`, continue to Review, and assert `Launch instance` is enabled.

Selectors and assertions:

- `page.getByRole("button", { name: "Continue" })` is disabled when the custom disk size is invalid.
- `page.getByRole("button", { name: "Review" })` can be clicked, but `page.getByRole("button", { name: "Launch instance" })` remains disabled until `Size` is valid if Review is reached through sidebar navigation.
- After a valid size, `Launch instance` is enabled.
- Assert no `POST /api/cs/deployVirtualMachine` occurs while launch is guarded.

Expected submit body after valid launch:

```json
{
  "name": "smoke-vm-01",
  "displayname": "vm-smoke-zone-preview",
  "zoneid": "z-smoke-1",
  "templateid": "tmpl-ubuntu",
  "serviceofferingid": "so-small",
  "networkids": "net-smoke",
  "diskofferingid": "do-custom",
  "size": "75",
  "sshkeypairs": "platform-admin",
  "userdata": "I2Nsb3VkLWNvbmZpZwpwYWNrYWdlczoKICAtIG5naW54",
  "startvm": "false",
  "projectid": "project-smoke",
  "affinitygroupids": "ag-spread"
}
```

Note: `displayname` remains the catalog-generated default unless the test also fills `Display name`.

## Spec 4: async job polling display

Flow:

1. Complete a valid Review state.
2. Click `Launch instance`.
3. Fulfill deploy response with `job-smoke-1`.
4. Fulfill first async-job request as pending with `jobprocstatus: 40`.
5. Fulfill second async-job request as success.

Selectors and assertions:

- Immediately after submit: `page.getByText("Submitting deployment")`.
- During pending poll: `page.getByText("Job job-smoke-1 / 40%")`.
- After success: `page.getByText("smoke-vm-01 Running")`.
- Badge text changes from `Launch ready` to `Launching` to `Launched`.
- `Launch instance` is disabled while submitting or polling.

Timing note:

- `waitForDeployJob` waits up to 1 second between pending polls. A harness-level fake timer helper would make this deterministic. Without fake timers, respond to the first poll as success in the main smoke and keep the pending-progress case as a separate focused spec with an increased timeout.

## Minimal reconciliation notes

- Add accessible labels to the project, SSH key, affinity group, and start-after-deploy controls in `web/components/deploy-wizard/deploy-wizard.tsx` when the harness lands. This is testability-neutral and improves the production accessibility tree.
- If the shared BFF helper exposes CloudStack command names instead of raw paths, map the endpoints above by command:
  - `listZones`, `listTemplates`, `listServiceOfferings`, `listDiskOfferings`, `listNetworks`, `listSecurityGroups`, `listSSHKeyPairs`, `listProjects`, `listAffinityGroups`, `deployVirtualMachine`, `queryAsyncJobResult`.
- Do not add Playwright config or npm scripts in this branch unless `phase5e-smoke-harness` has been merged first.
