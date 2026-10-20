package com.redhat.quayoktasyncooperator;

import java.util.List;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("identity.example.com")
@Version("v1alpha1")
public class QuayOktaSync extends CustomResource<QuayOktaSync.Spec, QuayOktaSync.Status> implements Namespaced {

    public static class Spec {

        public String oktaBaseUrl;

        public String quayBaseUrl;

        public String credentialsSecret;

        public Integer intervalSeconds = 900;

        public Boolean dryRun = true;

        public String groupNamePattern = "^[^-]+-[^-]+-([^-]+)-[^-]+$";

        public String oktaUsernameAttribute = "login";

        public String quayOrganization;

        public String quayRole = "member";

        public Boolean removeExtraMembers = false;

        public List<TeamNameOverride> teamNameOverrides;

    }

    public static class TeamNameOverride {

        public String oktaGroup;

        public String quayTeam;

    }

    public static class Status {

        public String phase;

        public String message;

        public String lastSyncTime;

    }

}
