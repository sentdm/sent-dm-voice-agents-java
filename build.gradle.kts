plugins {
    id("com.vanniktech.maven.publish") version "0.35.0" apply false
    id("com.diffplug.spotless") version "7.0.2" apply false
}

allprojects {
    group = "dm.sent"
    // gradle.properties keeps release-please's marker on the version line; a properties value has no comment syntax.
    version = (property("version") as String).substringBefore('#').trim()
}
