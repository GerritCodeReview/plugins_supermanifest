load(
    "@com_googlesource_gerrit_bazlets//:gerrit_plugin.bzl",
    "gerrit_plugin",
    "gerrit_plugin_tests",
)

EXT_DEPS = [
    "com.sun.istack:istack-commons-runtime",
    "javax.xml.bind:jaxb-api",
    "org.glassfish.jaxb:jaxb-runtime",
]

PLUGIN = "supermanifest"

gerrit_plugin(
    srcs = glob(["java/**/*.java"]),
    ext_deps = EXT_DEPS,
    manifest_entries = [
        "Gerrit-PluginName: supermanifest",
        "Gerrit-Module: com.googlesource.gerrit.plugins.supermanifest.SuperManifestModule",
        "Implementation-Title: Supermanifest plugin",
        "Implementation-URL: https://gerrit-review.googlesource.com/#/todo",
    ],
    plugin = PLUGIN,
    resources = glob(["java/Documentation/**/*.md"]),
    deps = [
        "//lib/errorprone:annotations",
    ],
)

gerrit_plugin_tests(
    size = "large",
    srcs = glob([
        "javatests/**/*IT.java",
        "javatests/**/*Test.java",
    ]),
    plugin = PLUGIN,
    resources = glob(["javatests/**/*.properties"]),
    tags = [
        "supermanifest-plugin",
    ],
    visibility = ["//visibility:public"],
)
