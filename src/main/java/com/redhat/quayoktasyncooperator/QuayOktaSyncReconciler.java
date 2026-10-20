package com.redhat.quayoktasyncooperator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ControllerConfiguration(name = "quay-okta-sync")
public class QuayOktaSyncReconciler implements Reconciler<QuayOktaSync> {

    private static final Logger LOG = LoggerFactory.getLogger(QuayOktaSyncReconciler.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String DEFAULT_GROUP_PATTERN = "^[^-]+-[^-]+-([^-]+)-[^-]+$";

    private final KubernetesClient client;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public QuayOktaSyncReconciler(KubernetesClient client) {

        this.client = client;
    }

    private static String secretValue(Secret secret, String key) {

        String value = secret.getData().get(key);
      if (value == null) {
        throw new IllegalStateException("Missing Secret key: " + key);
      }
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String nextLink(String linkHeader) {

        for (String part : linkHeader.split(",")) {
            if (part.contains("rel=\"next\"")) {
                int start = part.indexOf('<');
                int end = part.indexOf('>');
              if (start >= 0 && end > start) {
                return part.substring(start + 1, end);
              }
            }
        }
        return null;
    }

    private static String trimSlash(String value) {

        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String enc(String value) {

        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Override
    public UpdateControl<QuayOktaSync> reconcile(QuayOktaSync cr, Context<QuayOktaSync> context) throws Exception {

        QuayOktaSync.Spec spec = cr.getSpec();
      if (cr.getStatus() == null) {
        cr.setStatus(new QuayOktaSync.Status());
      }
        long interval = Math.max(60, spec.intervalSeconds == null ? 900 : spec.intervalSeconds);
        try {
            validateSpec(spec);
            Secret secret = client.secrets().inNamespace(cr.getMetadata().getNamespace()).withName(spec.credentialsSecret).get();
            if (secret == null || secret.getData() == null) {
                throw new IllegalStateException("Credentials Secret not found: " + spec.credentialsSecret);
            }
            String oktaToken = secretValue(secret, "okta-api-token");
            String quayToken = secretValue(secret, "quay-api-token");
            String okta = trimSlash(spec.oktaBaseUrl);
            String quay = trimSlash(spec.quayBaseUrl);
            Pattern groupPattern = Pattern.compile(spec.groupNamePattern == null || spec.groupNamePattern.isBlank()
                    ? DEFAULT_GROUP_PATTERN : spec.groupNamePattern);
            Map<String, String> overrides = new TreeMap<>();
            if (spec.teamNameOverrides != null) {
                for (QuayOktaSync.TeamNameOverride override : spec.teamNameOverrides) {
                    if (override != null && override.oktaGroup != null && !override.oktaGroup.isBlank()) {
                        if (override.quayTeam == null || override.quayTeam.isBlank()) {
                            throw new IllegalArgumentException("teamNameOverrides entry for '" + override.oktaGroup + "' must specify quayTeam");
                        }
                        overrides.put(override.oktaGroup, override.quayTeam);
                    }
                }
            }

            List<OktaGroup> discoveredGroups = discoverRelevantGroups(okta, oktaToken, groupPattern, overrides);
            Map<String, Set<String>> desiredByTeam = new TreeMap<>();
            Map<String, Set<String>> groupsByTeam = new TreeMap<>();
            String usernameAttribute = spec.oktaUsernameAttribute == null || spec.oktaUsernameAttribute.isBlank()
                    ? "login" : spec.oktaUsernameAttribute;
            for (OktaGroup group : discoveredGroups) {
                String teamName = resolveTeamName(group.name, groupPattern, overrides);
                String key = spec.quayOrganization + "/" + teamName;
                Set<String> users = getOktaMembers(okta, oktaToken, group.id, usernameAttribute);
                desiredByTeam.computeIfAbsent(key, ignored -> new TreeSet<>()).addAll(users);
                groupsByTeam.computeIfAbsent(key, ignored -> new TreeSet<>()).add(group.name);
            }

            if (discoveredGroups.isEmpty()) {
                LOG.warn("No Okta groups matched groupNamePattern '{}' or teamNameOverrides", groupPattern.pattern());
            }
            for (Map.Entry<String, Set<String>> entry : desiredByTeam.entrySet()) {
                String key = entry.getKey();
                String teamName = key.substring(key.indexOf('/') + 1);
                try {
                    syncAggregate(quay, quayToken, spec, teamName, entry.getValue(),
                            groupsByTeam.getOrDefault(key, Set.of()), Boolean.TRUE.equals(spec.dryRun));
                } catch (Exception e) {
                    LOG.error("Team sync failed for {}/{}", spec.quayOrganization, teamName, e);
                    throw e;
                }
            }
            cr.getStatus().phase = "Ready";
            cr.getStatus().message = Boolean.TRUE.equals(spec.dryRun)
                    ? "Dry-run cycle completed; no membership mutations made. Discovered " + discoveredGroups.size() + " matching Okta groups."
                    : "Sync cycle completed for " + discoveredGroups.size() + " matching Okta groups; see operator logs for details.";
            cr.getStatus().lastSyncTime = Instant.now().toString();
        } catch (Exception e) {
            LOG.error("Reconciliation failed for {}", cr.getMetadata().getName(), e);
            cr.getStatus().phase = "Error";
            cr.getStatus().message = e.getMessage();
        }
        return UpdateControl.patchStatus(cr).rescheduleAfter(Duration.ofSeconds(interval));
    }

    private void validateSpec(QuayOktaSync.Spec spec) {

      if (spec == null) {
        throw new IllegalArgumentException("spec is required");
      }
        requireValue(spec.oktaBaseUrl, "oktaBaseUrl");
        requireValue(spec.quayBaseUrl, "quayBaseUrl");
        requireValue(spec.credentialsSecret, "credentialsSecret");
        requireValue(spec.quayOrganization, "quayOrganization");
    }

    private void requireValue(String value, String field) {

      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(field + " must be configured");
      }
    }

    private List<OktaGroup> discoverRelevantGroups(String base, String token, Pattern pattern,
                                                   Map<String, String> overrides) throws Exception {

        List<OktaGroup> relevant = new ArrayList<>();
        String url = base + "/api/v1/groups?limit=200";
        int pages = 0;
        while (url != null && pages < 1000) {
            HttpResponse<String> response = send(url, token, null, "GET", false);
            JsonNode groups = JSON.readTree(response.body());
          if (!groups.isArray()) {
            throw new IllegalStateException("Okta groups API did not return a JSON array");
          }
            for (JsonNode group : groups) {
                String name = group.path("profile").path("name").asText("");
                String id = group.path("id").asText("");
              if (name.isBlank() || id.isBlank()) {
                continue;
              }
                if (overrides.containsKey(name) || matchesGroupPattern(name, pattern)) {
                    relevant.add(new OktaGroup(id, name));
                }
            }
            url = nextLink(response.headers().firstValue("Link").orElse(""));
            pages++;
        }
      if (url != null) {
        throw new IllegalStateException("Okta group discovery exceeded the 1000-page safety limit");
      }
        relevant.sort(Comparator.comparing(OktaGroup::name));
        LOG.info("Discovered {} Okta groups matching the naming convention or explicit overrides", relevant.size());
      for (OktaGroup group : relevant) {
        LOG.info("Discovered Okta group '{}'", group.name);
      }
        return relevant;
    }

    private boolean matchesGroupPattern(String name, Pattern pattern) {

        Matcher matcher = pattern.matcher(name);
        return matcher.matches() && matcher.groupCount() >= 1 && matcher.group(1) != null && !matcher.group(1).isBlank();
    }

    private String resolveTeamName(String groupName, Pattern pattern, Map<String, String> overrides) {

        String override = overrides.get(groupName);
      if (override != null && !override.isBlank()) {
        return override;
      }
        Matcher matcher = pattern.matcher(groupName);
        if (!matcher.matches() || matcher.groupCount() < 1 || matcher.group(1) == null || matcher.group(1).isBlank()) {
            throw new IllegalArgumentException("Okta group '" + groupName + "' did not match groupNamePattern and has no teamNameOverrides entry");
        }
        return matcher.group(1);
    }

    private void syncAggregate(String quay, String token, QuayOktaSync.Spec spec,
                               String teamName, Set<String> desired, Set<String> sourceGroups, boolean dryRun) throws Exception {

        String organization = spec.quayOrganization;
        if (!teamExists(quay, token, organization, teamName)) {
            if (dryRun) {
                LOG.info("DRY RUN: would create Quay team {}/{} from Okta groups {}", organization, teamName, sourceGroups);
                return;
            }
            createTeam(quay, token, organization, teamName, spec.quayRole, sourceGroups);
        }
        Set<String> current = getQuayMembers(quay, token, organization, teamName);
        Set<String> add = new TreeSet<>(desired);
        add.removeAll(current);
        Set<String> remove = new TreeSet<>(current);
        remove.removeAll(desired);
        Set<String> actualRemove = Boolean.TRUE.equals(spec.removeExtraMembers) ? remove : Set.of();
        if (dryRun) {
            LOG.info("DRY RUN {}/{} from groups {}: add={}, remove={}", organization, teamName, sourceGroups, add, actualRemove);
            return;
        }
      for (String user : add) {
        mutateMember(quay, token, organization, teamName, user, true);
      }
      for (String user : actualRemove) {
        mutateMember(quay, token, organization, teamName, user, false);
      }
        LOG.info("Synced {}/{} from {} Okta groups: added {}, removed {}", organization, teamName, sourceGroups.size(), add.size(), actualRemove.size());
    }

    private String findGroup(String base, String token, String exactName) throws Exception {

        JsonNode groups = request("GET", base + "/api/v1/groups?q=" + enc(exactName), token, null, false);
        if (groups.isArray()) {
            for (JsonNode group : groups) {
              if (exactName.equals(group.path("profile").path("name").asText())) {
                return group.path("id").asText();
              }
            }
        }
        throw new IllegalStateException("Okta group not found by exact name: " + exactName);
    }

    private Set<String> getOktaMembers(String base, String token, String id, String attribute) throws Exception {

        Set<String> users = new TreeSet<>();
        String url = base + "/api/v1/groups/" + enc(id) + "/users?limit=200";
        int pages = 0;
        while (url != null && pages < 1000) {
            HttpResponse<String> response = send(url, token, null, "GET", false);
            JsonNode array = JSON.readTree(response.body());
          if (!array.isArray()) {
            throw new IllegalStateException("Okta group-members API did not return a JSON array for group id " + id);
          }
            for (JsonNode user : array) {
                String value = user.path("profile").path(attribute).asText("");
              if (!value.isBlank()) {
                users.add(value);
              }
            }
            url = nextLink(response.headers().firstValue("Link").orElse(""));
            pages++;
        }
      if (url != null) {
        throw new IllegalStateException("Okta group-members lookup exceeded the 1000-page safety limit for group id " + id);
      }
        return users;
    }

    private boolean teamExists(String base, String token, String org, String team) throws Exception {

        HttpResponse<String> response = send(base + "/api/v1/organization/" + enc(org) + "/team/" + enc(team), token, null, "GET", true);
      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        return true;
      }
      if (response.statusCode() == 404) {
        return false;
      }
        throw new IllegalStateException("Quay team lookup for " + org + "/" + team + " returned " + response.statusCode() + ": " + response.body());
    }

    private void createTeam(String base, String token, String org, String teamName, String role,
                            Set<String> sourceGroups) throws Exception {

        ObjectNode body = JSON.createObjectNode()
                .put("role", role == null || role.isBlank() ? "member" : role)
                .put("description", "Managed by QuayOktaSync; Okta groups: " + String.join(",", sourceGroups));
        request("PUT", base + "/api/v1/organization/" + enc(org) + "/team/" + enc(teamName), token, body, true);
    }

    private Set<String> getQuayMembers(String base, String token, String org, String team) throws Exception {

        JsonNode root = request("GET", base + "/api/v1/organization/" + enc(org) + "/team/" + enc(team), token, null, true);
        Set<String> members = new TreeSet<>();
        JsonNode memberNodes = root.path("members");
      if (memberNodes.isArray()) {
        for (JsonNode member : memberNodes) {
          members.add(member.asText());
        }
      }
        return members;
    }

    private void mutateMember(String base, String token, String org, String team, String user, boolean add) throws Exception {

        String url = base + "/api/v1/organization/" + enc(org) + "/team/" + enc(team) + "/members/" + enc(user);
        request(add ? "PUT" : "DELETE", url, token, add ? JSON.createObjectNode() : null, true);
    }

    private JsonNode request(String method, String url, String token, JsonNode body, boolean quay) throws Exception {

        HttpResponse<String> response = send(url, token, body == null ? null : JSON.writeValueAsString(body), method, quay);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(method + " " + url + " returned " + response.statusCode() + ": " + response.body());
        }
        return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
    }

    private HttpResponse<String> send(String url, String token, String body, String method, boolean quay) throws Exception {

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Authorization", (quay ? "Bearer " : "SSWS ") + token)
                .header("Accept", "application/json");
        if (body != null) {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private record OktaGroup(String id, String name) {

    }

}
