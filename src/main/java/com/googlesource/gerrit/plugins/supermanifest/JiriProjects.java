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

import java.util.Arrays;
import java.util.Comparator;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;

class JiriProjects {
  @XmlElement(name = "project")
  private Project[] projects;

  public JiriProjects() {
    projects = new Project[0];
  }

  /** @return the projects */
  public Project[] getProjects() {
    return projects;
  }

  public void sortByPath() {
    Arrays.sort(projects, new SortbyPath());
  }

  public String toSubmodules() {
    StringBuffer buf = new StringBuffer();
    sortByPath();
    for (Project p : projects) {
      buf.append(p.toSubmodules());
      buf.append("\n");
    }
    return buf.toString();
  }

  public JiriProjects(Project[] projects) {
    this.projects = projects;
  }

  /*
   * For debugging purpose
   */
  @Override
  public String toString() {
    StringBuffer buf = new StringBuffer();
    if (projects.length > 0) {
      buf.append("projects:\n");
      for (Project p : projects) {
        buf.append(StringUtil.addTab(p.toString()));
      }
    }
    return buf.toString();
  }

  static class Project {
    @XmlAttribute(required = true)
    private String name;

    @XmlAttribute(required = true)
    private String path;

    @XmlAttribute(required = true)
    private String remote;

    @XmlAttribute private String remotebranch;

    @XmlAttribute private String revision;

    @XmlAttribute private int historydepth;

    /** @return the name */
    public String getName() {
      return name;
    }

    /** @return the path */
    public String getPath() {
      return path;
    }

    /** @return the remote */
    public String getRemote() {
      return remote;
    }

    /** @return the historydepth */
    public int getHistorydepth() {
      return historydepth;
    }

    public String getRef() {
      if (!revision.isEmpty()) {
        return revision;
      }
      return remotebranch;
    }

    public Project() {
      name = "";
      path = "";
      remote = "";
      remotebranch = "";
      revision = "";
      historydepth = 0;
    }

    /*
     * For debugging purpose
     */
    @Override
    public String toString() {
      return String.format(
          "project:\n\tname: %s\n\tpath: %s\n\tremote: %s\n\tremotebranch: %s\n\trevision: %s",
          name, path, remote, remotebranch, revision);
    }

    @Override
    public boolean equals(Object obj) {
      Project p = (Project) obj;

      if (!name.equals(p.name)) {
        return false;
      }
      if (!path.equals(p.path)) {
        return false;
      }
      if (!remote.equals(p.remote)) {
        return false;
      }
      if (!remotebranch.equals(p.remotebranch)) {
        // check for master, empty means master
        if (!((remotebranch.equals("master") && p.remotebranch.equals(""))
            || (p.remotebranch.equals("master") && remotebranch.equals("")))) {
          return false;
        }
      }
      if (!revision.equals(p.revision)) {
        return false;
      }

      return true;
    }

    /*
     * For debugging purpose
     */
    public String toSubmodules() {
      StringBuffer buf = new StringBuffer(String.format("[submodule \"%s\"]", name));
      buf.append("\n\tpath = " + path);
      buf.append("\n\turl = " + remote);
      String branch = "";
      if (!remotebranch.isEmpty()) {
        branch = remotebranch;
      }

      if (!revision.isEmpty()) {
        branch = revision;
      }

      if (!branch.isEmpty()) {
        buf.append("\n\tbranch = " + branch);
      }

      buf.append("\n");
      return buf.toString();
    }

    public String Key() {
      return name + "=" + remote;
    }
  }

  static class SortbyPath implements Comparator<Project> {
    @Override
    public int compare(Project a, Project b) {
      String p1 = StringUtil.stripAndaddCharsAtEnd(a.getPath(), "/");
      String p2 = StringUtil.stripAndaddCharsAtEnd(b.getPath(), "/");
      return p1.compareTo(p2);
    }
  }
}
