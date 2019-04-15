// Copyright (C) 2017 Google Inc
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.supermanifest;

import static com.google.gerrit.reviewdb.client.RefNames.REFS_HEADS;

import com.google.gerrit.reviewdb.client.Project;
import com.googlesource.gerrit.plugins.supermanifest.SuperManifestRefUpdatedListener.GerritRemoteReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.stream.StreamSource;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Repository;

class JiriManifestParser {
  static class ManifestItem {
    public ManifestItem(
        String repoKey, String manifest, String ref, String pKey, boolean revisionPinned) {
      this.repoKey = repoKey;
      this.manifest = manifest;
      this.ref = ref;
      this.revisionPinned = revisionPinned;
      this.projectKey = pKey;
    }

    String repoKey;
    String manifest;
    String ref;
    boolean revisionPinned;

    // In jiri if import is pinned to a revision and if
    // we have a corresponding project in the manifest, jiri would
    // pin that project to same revision. So passing key to match
    // project to import tag.
    //  For Eg, if you have manifest in manifest2 repo
    //          <manifest><projects><project name="manifest2" .../>
    //  And If you import this from your main manifest
    //          <manifest><imports><import name="manifest2" revision="A"... />
    //  jiri will pin manifest2 project to A as well.
    String projectKey;
  }

  static class RepoMap<K, V extends Repository> extends HashMap<K, V> implements AutoCloseable {
    @Override
    public void close() {
      for (Repository repo : this.values()) {
        repo.close();
      }
    }
  }

  public static JiriProjects getProjects(
      GerritRemoteReader reader, String repoKey, String ref, String manifest)
      throws ConfigInvalidException, IOException {

    try (RepoMap<String, Repository> repoMap = new RepoMap<>()) {
      repoMap.put(repoKey, reader.openRepository(repoKey));
      Queue<ManifestItem> q = new LinkedList<>();
      q.add(new ManifestItem(repoKey, manifest, ref, "", false));
      HashMap<String, HashSet<String>> processedRepoFiles = new HashMap<>();
      HashMap<String, JiriProjects.Project> projectMap = new HashMap<>();

      while (q.size() != 0) {
        ManifestItem mi = q.remove();
        Repository repo = repoMap.get(mi.repoKey);
        if (repo == null) {
          repo = reader.openRepository(mi.repoKey);
          repoMap.put(mi.repoKey, repo);
        }
        HashSet<String> processedFiles = processedRepoFiles.get(mi.repoKey);
        if (processedFiles == null) {
          processedFiles = new HashSet<>();
          processedRepoFiles.put(mi.repoKey, processedFiles);
        }
        if (processedFiles.contains(mi.manifest)) {
          continue;
        }
        processedFiles.add(mi.manifest);
        JiriManifest m;
        try {
          m = parseManifest(repo, mi.ref, mi.manifest);
        } catch (JAXBException | XMLStreamException e) {
          throw new ConfigInvalidException("XML parse error", e);
        }

        for (JiriProjects.Project project : m.projects.getProjects()) {
          project.fillDefault();
          if (mi.revisionPinned && project.Key().equals(mi.projectKey)) {
            project.setRevision(mi.ref);
          }
          if (projectMap.containsKey(project.Key())) {
            if (!projectMap.get(project.Key()).equals(project))
              throw new ConfigInvalidException(
                  String.format(
                      "Duplicate conflicting project %s in manifest %s\n%s\n%s",
                      project.Key(),
                      mi.manifest,
                      project.toString(),
                      projectMap.get(project.Key()).toString()));
          } else {
            projectMap.put(project.Key(), project);
          }
        }

        URI parentURI;
        try {
          parentURI = new URI(mi.manifest);
        } catch (URISyntaxException e) {
          throw new ConfigInvalidException("Invalid parent URI", e);
        }
        for (JiriManifest.LocalImport l : m.imports.getLocalImports()) {
          ManifestItem tw =
              new ManifestItem(
                  mi.repoKey,
                  parentURI.resolve(l.getFile()).getPath(),
                  mi.ref,
                  mi.projectKey,
                  mi.revisionPinned);
          q.add(tw);
        }

        for (JiriManifest.Import i : m.imports.getImports()) {
          i.fillDefault();
          URI uri;
          try {
            uri = new URI(i.getRemote());
          } catch (URISyntaxException e) {
            throw new ConfigInvalidException("Invalid URI", e);
          }
          String iRepoKey = new Project.NameKey(StringUtils.strip(uri.getPath(), "/")).toString();
          String iRef = i.getRevision();
          boolean revisionPinned = true;
          if (iRef.isEmpty()) {
            iRef = REFS_HEADS + i.getRemotebranch();
            revisionPinned = false;
          }

          ManifestItem tmi =
              new ManifestItem(iRepoKey, i.getManifest(), iRef, i.Key(), revisionPinned);
          q.add(tmi);
        }
      }
      return new JiriProjects(projectMap.values().toArray(new JiriProjects.Project[0]));
    }
  }

  private static JiriManifest parseManifest(Repository repo, String ref, String file)
      throws JAXBException, IOException, XMLStreamException {
    byte[] b = Utils.readBlob(repo, ref + ":" + file);
    JAXBContext jc = JAXBContext.newInstance(JiriManifest.class);

    XMLInputFactory inf = XMLInputFactory.newFactory();
    inf.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    inf.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    XMLStreamReader sr = inf.createXMLStreamReader(new StreamSource(new ByteArrayInputStream(b)));

    return (JiriManifest) jc.createUnmarshaller().unmarshal(sr);
  }
}
