package com.googlesource.gerrit.plugins.supermanifest;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project.NameKey;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.project.NoSuchProjectException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;

public class ConfigParser {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final PluginConfigFactory cfgFactory;
  private final String pluginName;
  private final AllProjectsName allProjectsName;

  @Inject
  ConfigParser(
      AllProjectsName allProjectsName,
      @PluginName String pluginName,
      PluginConfigFactory cfgFactory) {
    this.allProjectsName = allProjectsName;
    this.pluginName = pluginName;
    this.cfgFactory = cfgFactory;
  }

  /*
     [superproject "submodules:refs/heads/nyc"]
        srcRepo = platforms/manifest
        srcRef = refs/heads/nyc
        srcPath = manifest.xml
  */
  Set<ConfigEntry> parseConfiguration() throws NoSuchProjectException {
    Config cfg = cfgFactory.getProjectPluginConfig(allProjectsName, pluginName);

    Set<ConfigEntry> newConf = new HashSet<>();
    Set<String> destinations = new HashSet<>();
    Set<String> wildcardDestinations = new HashSet<>();
    Map<NameKey, NameKey> globbedMappings = new HashMap<>();
    Set<String> sources = new HashSet<>();

    for (String sect : cfg.getSections()) {
      if (!sect.equals(ConfigEntry.SECTION_NAME)) {
        logger.atWarning().log("%s.config: ignoring invalid section %s", pluginName, sect);
      }
    }
    for (String subsect : cfg.getSubsections(ConfigEntry.SECTION_NAME)) {
      try {
        ConfigEntry configEntry = new ConfigEntry(cfg, subsect);
        if (destinations.contains(configEntry.srcRepoKey.get())
            || sources.contains(configEntry.destRepoKey.get())) {
          // Don't want cyclic dependencies.
          throw new ConfigInvalidException(
              String.format("repo in entry %s cannot be both source and destination", configEntry));
        }
        if (configEntry.destBranch.equals("*")) {
          if (wildcardDestinations.contains(configEntry.destRepoKey.get())) {
            throw new ConfigInvalidException(
                String.format(
                    "repo %s already has a wildcard destination branch.", configEntry.destRepoKey));
          }
          wildcardDestinations.add(configEntry.destRepoKey.get());
        }

        // Don't allow globbed writes from two different sources to the same destination.
        // Maybe the globs do not overlap, but we don't have an easy way to find that out, so
        // better safe than sorry.
        if (configEntry.destBranch.contains("*")) {
          NameKey knownSource = globbedMappings.get(configEntry.destRepoKey);
          if (knownSource != null && !knownSource.equals(configEntry.srcRepoKey)) {
            throw new ConfigInvalidException(
                String.format(
                    "repo %s has globbled destinations from at least two sources %s and %s",
                    configEntry.destRepoKey,
                    configEntry.srcRepoKey,
                    globbedMappings.get(configEntry.srcRepoKey)));
          }
          globbedMappings.put(configEntry.destRepoKey, configEntry.srcRepoKey);
        }

        sources.add(configEntry.srcRepoKey.get());
        destinations.add(configEntry.destRepoKey.get());

        newConf.add(configEntry);
      } catch (ConfigInvalidException e) {
        logger.atSevere().log("invalid configuration: %s", e);
      }
    }

    return newConf;
  }
}
