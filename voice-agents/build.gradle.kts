// The library: the runtime, and the generated frames package.
import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar

plugins {
    `java-library`
    id("com.vanniktech.maven.publish")
    id("com.diffplug.spotless")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    // The one dependency: the JSON tree model and parser the frames are read and written with.
    api("com.fasterxml.jackson.core:jackson-databind:2.18.3")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Javadoc>().configureEach {
    // The generated records document the wire fields that have a description; the rest stay undocumented.
    (options as StandardJavadocDocletOptions).apply {
        addBooleanOption("Xdoclint:none", true)
        quiet()
    }
}

tasks.test {
    useJUnitPlatform()
    // The protocol's own fixtures, copied into tests/fixtures by the lane at render time.
    systemProperty("sent.fixtures", rootProject.file("tests/fixtures").absolutePath)
}

spotless {
    java {
        target("src/**/*.java")
        // The generated package is shaped by the emitter.
        targetExclude("src/main/java/dm/sent/voice/agents/frames/**")
        googleJavaFormat("1.25.2").aosp()
    }
}

// Publishing to Maven Central: the Central Portal, every publication signed with the key the workflow hands the
// plugin as Gradle properties (ORG_GRADLE_PROJECT_signingInMemoryKey and ...KeyPassword). With -PpublishLocal
// the same publications are built into a local folder, unsigned, so CI proves they build.
mavenPublishing {
    configure(JavaLibrary(javadocJar = JavadocJar.Javadoc(), sourcesJar = true))
    coordinates(project.group.toString(), project.name, project.version.toString())
    if (!project.hasProperty("publishLocal")) {
        signAllPublications()
        publishToMavenCentral()
    }
    pom {
        name.set("Sent Voice Agents for Java")
        description.set("An AI agent that takes and places phone calls through Sent.")
        url.set("https://docs.sent.dm")
        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }
        developers {
            developer {
                name.set("Sent")
                email.set("support@sent.dm")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/sentdm/sent-dm-voice-agents-java.git")
            developerConnection.set("scm:git:git://github.com/sentdm/sent-dm-voice-agents-java.git")
            url.set("https://github.com/sentdm/sent-dm-voice-agents-java")
        }
    }
}

publishing {
    repositories {
        if (project.hasProperty("publishLocal")) {
            maven {
                name = "LocalFileSystem"
                url = uri("${rootProject.layout.buildDirectory.get()}/local-maven-repo")
            }
        }
    }
}
