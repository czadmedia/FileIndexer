plugins {
    application
    kotlin("jvm")
}

group = "org.example"
version = "0.1.0"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation(project(":fileindex-core"))
    implementation(kotlin("stdlib"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("org.example.fileindexdemo.Main")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`   // <-- lets your REPL read from the terminal
}
