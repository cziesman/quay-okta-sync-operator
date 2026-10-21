package com.redhat.quayoktasyncooperator;

import java.util.List;

public class QuayOktaSyncSpec {

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
