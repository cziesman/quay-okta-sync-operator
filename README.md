# Quay Okta Sync Operator

A Java Operator SDK-based OpenShift operator. It watches `QuayOktaSync` custom resources and periodically discovers relevant Okta groups, derives Quay team names from their names, aggregates membership from all groups that resolve to the same team, and reconciles membership through the Quay API.

## Discovery behavior

The Operator lists groups from the configured Okta organization using the Okta Groups API and follows pagination links. It automatically selects groups that match `spec.groupNamePattern`; the default is:

`^[^-]+-[^-]+-([^-]+)-[^-]+$`

This matches four non-empty hyphen-separated segments in the form `<environment>-<division>-<team>-<role>`. Capture group 1 (the third segment) becomes the Quay team name. For example, `prod-iowa-farmers-admin`, `dev-iowa-farmers-member`, and `prod-texas-farmers-admin` all map to the `farmers` team. Membership is unioned across all discovered groups mapping to a given team before Quay is reconciled.

There is no per-group allowlist. `teamNameOverrides` is the only per-group configuration: each entry can include an exact group whose name does not match the normal pattern, or change the destination team name for a matching group. Override entries are matched by exact Okta group name and take precedence over the parser. Overrides are also discovered even when their group name does not match the pattern.

All managed teams are created under the single `spec.quayOrganization`, with the configured `spec.quayRole`. `spec.oktaUsernameAttribute` is the Okta profile attribute whose value must match the Quay username; the default is `login`.

## Important implementation notes

- This is a starter operator project, not a certified Red Hat operator.
- It synchronizes team membership through Quay's API. It does **not** configure Quay's OIDC Directory Sync UI setting.
- Verify the exact Quay 3.15 API behavior and token permissions against a test organization before production. API responses and endpoint permissions can vary by deployment/configuration.
- The Okta API token needs permission to list groups and read group memberships. The Quay token needs permission to read the organization/team and manage team membership; it may also need permission to create teams.
- Starts in `dryRun: true`. Review logs before setting `dryRun: false`.
- `removeExtraMembers: true` can remove manually managed team members. Membership removals are based on the union of all discovered source groups mapped to each team; enable only if the Operator owns membership for those teams.
- The Operator will not remove members from a team if no discovered group maps to that team in the current cycle. Review group-name matching and overrides before enabling membership removal.
- This starter uses one operator replica and periodic reconciliation. Do not scale it horizontally without leader election or another single-active-controller strategy.
- Errors are logged and reflected in the custom resource status. Keep TLS certificate validation enabled.

## Build

Requires JDK 17+ and Maven 3.9+.

```bash
mvn clean package
```

## Install on OpenShift

Create the namespace and install the CRD/RBAC resources:

```bash
oc new-project quay-okta-sync-system
oc apply -f config/crd/bases/identity.example.com_quayoktasyncs.yaml
oc apply -f config/rbac/serviceaccount.yaml
oc apply -f config/rbac/role.yaml
oc apply -f config/rbac/rolebinding.yaml
```

Create credentials securely (prefer an external secret manager or sealed/external-secrets workflow rather than committing a populated Secret):

```bash
oc -n quay-okta-sync-system create secret generic quay-okta-sync-credentials \\
  --from-literal=okta-api-token='<OKTA_TOKEN>' \\
  --from-literal=quay-api-token='<QUAY_TOKEN>'
```

Build and push the image using your approved OpenShift build process, then apply the deployment and sample custom resource:

```bash
oc apply -f deploy/operator-deployment.yaml
oc apply -f config/samples/identity_v1alpha1_quayoktasync.yaml
```

Update the image field in `deploy/operator-deployment.yaml` to your image location.

## Custom resource settings

- `oktaBaseUrl`: Okta organization URL.
- `quayBaseUrl`: Quay URL.
- `credentialsSecret`: Secret name in the same namespace as the custom resource; keys are `okta-api-token` and `quay-api-token`.
- `intervalSeconds`: reconciliation interval; minimum 60 seconds.
- `dryRun`: keep true for initial validation.
- `groupNamePattern`: Java regular expression for relevant group names. Capture group 1 must contain the team name. The default matches the four-segment convention.
- `oktaUsernameAttribute`: Okta user profile attribute used as the Quay username, default `login`.
- `quayOrganization`: one destination Quay organization for all discovered teams.
- `quayRole`: role assigned when a team must be created, default `member`.
- `removeExtraMembers`: whether to remove Quay team members who are not present in the union of the currently discovered source groups, default false.
- `teamNameOverrides`: optional exact group-name exceptions, each with `oktaGroup` and `quayTeam`.

## Security and operational hardening before production

1. Build/test against your exact Java Operator SDK, Okta API, and Quay 3.15 versions.
2. Verify Quay API endpoints, membership payload shape, team roles, and token permissions in a test organization.
3. Add unit/integration tests with mocked Okta and Quay endpoints, including pagination, malformed names, overrides, and aggregation.
4. Add Prometheus metrics, per-team status conditions, rate-limit handling, and bounded retry/backoff.
5. Restrict Secret access to the operator namespace and use narrowly scoped credentials where supported.
6. Add leader election before running multiple replicas.
7. Consider making team creation optional if teams must be pre-approved.
