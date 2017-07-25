package com.googlesource.gerrit.plugins.supermanifest;

import com.googlesource.gerrit.plugins.supermanifest.SuperManifestRefUpdatedListener.GerritRemoteReader;

public interface SubModuleUpdater {

  /** Reads manifest and generates sub modules */
  void update(GerritRemoteReader reader, ConfigEntry c, String srcRef) throws Exception;
}
