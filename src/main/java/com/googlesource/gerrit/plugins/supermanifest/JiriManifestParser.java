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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import org.eclipse.jgit.lib.Repository;

class JiriManifestParser {
  public static JiriProjects GetProjects(Repository repo, String ref, String manifest)
      throws Exception {
    Queue<String> q = new LinkedList<>();
    q.add(manifest);
    HashSet<String> processedFiles = new HashSet<>();
    HashMap<String, JiriProjects.Project> projectMap = new HashMap<>();

    while (q.size() != 0) {
      String file = q.remove();
      if (processedFiles.contains(file)) {
        continue;
      }
      processedFiles.add(file);
      JiriManifest m = parseManifest(repo, ref, file);
      if (m.imports.getImports().length != 0) {
        throw new Exception(
            String.format("Manifest %s contains remote imports which are not supported", file));
      }

      for (JiriProjects.Project project : m.projects.getProjects()) {
        project.fillDefault();
        if (projectMap.containsKey(project.Key())) {
          if (!projectMap.get(project.Key()).equals(project))
            throw new Exception(
                String.format(
                    "Duplicate conflicting project %s in manifest %s\n%s\n%s",
                    project.Key(),
                    file,
                    project.toString(),
                    projectMap.get(project.Key()).toString()));
        } else {
          projectMap.put(project.Key(), project);
        }
      }

      URI parentURI = new URI(file);
      for (JiriManifest.LocalImport l : m.imports.getLocalImports()) {
        q.add(parentURI.resolve(l.getFile()).getPath());
      }
    }
    return new JiriProjects(projectMap.values().toArray(new JiriProjects.Project[0]));
  }

  private static JiriManifest parseManifest(Repository repo, String ref, String file)
      throws JAXBException, IOException {
    byte b[] = Utils.readBlob(repo, ref + ":" + file);
    JAXBContext jc = JAXBContext.newInstance(JiriManifest.class);
    Unmarshaller unmarshaller = jc.createUnmarshaller();
    InputStream is = new ByteArrayInputStream(b);
    JiriManifest manifest = (JiriManifest) unmarshaller.unmarshal(is);
    return manifest;
  }
}
