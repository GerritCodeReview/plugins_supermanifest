BUILD
=====

This plugin must be built inside gerrit source tree with bazel.

1. Checkout gerrit and clone this plugin inside gerrit/plugins directory:

```bash
$ git clone https://gerrit.googlesource.com/gerrit
$ cd gerrit
$ git clone https://....  plugins/supermanifest
```

2. This plugin has external dependencies. We need to tell gerrit
   about it overwriting the external_plugin_deps.bzl:

```bash
rm plugins/external_plugin_deps.bzl
ln -s  supermanifest/external_plugin_deps.bzl plugins/external_plugin_deps.bzl
```

3. Now it should build with

```bash
$ bazel build //plugins/supermanifest:supermanifest
```
