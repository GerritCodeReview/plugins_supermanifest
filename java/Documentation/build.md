BUILD
=====

This plugin must be built inside the Gerrit source tree with Bazel modules
(Bzlmod).

1. Checkout gerrit and clone this plugin inside gerrit/plugins directory:

```bash
$ git clone https://gerrit.googlesource.com/gerrit
$ cd gerrit
$ git clone https://gerrit.googlesource.com/plugins/supermanifest plugins/supermanifest
```

2. Add the following line to Gerrit's
   `plugins/external_plugin_deps.MODULE.bazel`, preserving any other plugin
   includes:

```starlark
include("//plugins/supermanifest:external_plugin_deps.MODULE.bazel")
```

   This wires the plugin module and its pinned `rules_jvm_external` repository
   into Gerrit's dependency graph. The Gerrit API and shared libraries come
   from the enclosing source tree.

3. Refresh Gerrit's module lockfile after adding the plugin, then build and test
   from the Gerrit root:

```bash
$ bazelisk mod deps --lockfile_mode=update
$ bazelisk build //plugins/supermanifest:supermanifest
$ bazelisk test //plugins/supermanifest/...
```

The plugin JAR is `bazel-bin/plugins/supermanifest/supermanifest.jar`.

To update the plugin's Maven dependency lockfile, run from the Gerrit root:

```bash
REPIN=1 bazelisk run @supermanifest_plugin_deps//:pin --lockfile_mode=update
mv supermanifest_plugin_deps.lock.json plugins/supermanifest/
```

The pin command writes the file at the Gerrit workspace root; move it into
the plugin before building again.
