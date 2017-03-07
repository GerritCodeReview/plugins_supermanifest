load("//tools/bzl:junit.bzl", "junit_tests")
load("//tools/bzl:plugin.bzl", "gerrit_plugin")

gerrit_plugin(
    name = "supermanifest",
    srcs = glob(["src/main/java/**/*.java"]),
    manifest_entries = [
        "Gerrit-PluginName: supermanifest",
        "Gerrit-Module: com.googlesource.gerrit.plugins.supermanifest.SuperManifestModule",
        "Implementation-Title: Supermanifest plugin",
        "Implementation-URL: https://gerrit-review.googlesource.com/#/todo",
    ],
    resources = glob(["src/main/**/*"]),
    deps = [ "//lib:commons-io" ],
)

junit_tests(
    name = "supermanifest_tests",
    size = "large",
    srcs = glob(["src/test/java/**/*IT.java"]),
    tags = [
        "supermanifest-plugin",
    ],
    data = [
         # plugin test handling is broken; kludge it here.
         ":supermanifest.jar",
    ],
    resources = glob(["src/test/resources/**"]),
    visibility = ["//visibility:public"],
    deps = [
        ":supermanifest__plugin",
        "//gerrit-acceptance-framework:lib",
        "//gerrit-plugin-api:lib",
    ],
)
