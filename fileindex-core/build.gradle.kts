plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
}


group = "org.example"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    api(kotlin("stdlib"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = "fileindex-core"
            version = project.version.toString()
        }
    }
    repositories {
        mavenLocal()
    }
}