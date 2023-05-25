package com.googlesource.gerrit.plugins.supermanifest;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.permissions.GlobalPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.project.BranchResource;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.inject.Provider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class SuperManifestBranchConf implements RestReadView<BranchResource> {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final ConfigParser configParser;
  private final Provider<IdentifiedUser> identifiedUser;
  private final PermissionBackend permissionBackend;

  @Inject
  SuperManifestBranchConf(
      ConfigParser configParser,
      Provider<IdentifiedUser> identifiedUser,
      PermissionBackend permissionBackend) {

    this.configParser = configParser;
    this.identifiedUser = identifiedUser;
    this.permissionBackend = permissionBackend;
  }

  @Override
  public Response<?> apply(BranchResource resource)
      throws AuthException, BadRequestException, ResourceConflictException,
          PermissionBackendException {
    permissionBackend.currentUser().check(GlobalPermission.ADMINISTRATE_SERVER);
    String manifestProject = resource.getBranchKey().project().get();
    String manifestBranch = resource.getBranchKey().branch();

    logger.atInfo().log(
        "querying conf %s:%s by %d",
        manifestProject, manifestBranch, identifiedUser.get().getAccountId().get());

    List<String> parseResult = new ArrayList<>();
    Set<ConfigEntry> configs = new HashSet<>();
    try {
      configs = configParser.parseConfiguration(parseResult);
    } catch (NoSuchProjectException e) {
      parseResult.add("ERROR: Cannot load plugin configuration");
    }

    List<ConfigEntry> relevantConfigs =
        configs.stream()
            .filter(c -> c.matchesSource(manifestProject, manifestBranch))
            .collect(Collectors.toList());

    parseResult.add("RELEVANT CONFS: " + relevantConfigs.size());
    Map<String, ConfigEntry> destinations = new HashMap<>();
    for (ConfigEntry config : relevantConfigs) {
      String key = config.getDestRepoKey() + ":" + config.getActualDestBranch(manifestBranch);
      if (destinations.containsKey(key)) {
        parseResult.add(String.format("SKIP: %s. Overlap with %s", config, destinations.get(key)));
        continue;
      }
      destinations.put(key, config);
      parseResult.add("MATCH: " + config);
    }

    return Response.withStatusCode(200, parseResult);
  }
}
